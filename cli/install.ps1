# `loopky` installer for Windows.
#
#   irm https://github.com/jvsena42/loopky/releases/latest/download/install.ps1 | iex
#
# **From the release, not from `main`** — the same rule `install.sh` states and for the same reason
# (#209): this file is published as an asset at the tag, beside the binary it fetches. The obvious
# alternative, `raw.githubusercontent.com/.../main/cli/install.ps1`, pipes whatever `main` happens
# to be at that second into a shell that runs as the user.
#
# **No `-ExecutionPolicy Bypass` in the documented one-liner.** `irm | iex` never touches the
# execution policy — that only gates *files* on disk — so telling people to add it buys nothing,
# trains them to disable a safety control for anything calling itself an installer, and is a
# pattern EDR products flag. If you saved this file and want to run it that way, that is your
# choice to make with the flag on your own command line rather than one this project asks for.
#
# There is nothing here you cannot do by hand, and doing it by hand is supported — the artifact is
# one file, and the release page lists a direct URL:
#
#   irm <url> -OutFile $env:LOCALAPPDATA\Programs\loopky\loopky.exe
#
# What this adds is picking the right file, checking its digest, refusing a host there is no build
# for, and saying up front when the machine cannot run the binary at all.

# The default is `Continue`, which makes a PowerShell script the exact thing a `sh` script without
# `set -e` is: every failure scrolls past and the last line still claims success.
$ErrorActionPreference = 'Stop'

$Repo        = if ($env:LOOPKY_REPO)        { $env:LOOPKY_REPO }        else { 'jvsena42/loopky' }
$Version     = if ($env:LOOPKY_VERSION)     { $env:LOOPKY_VERSION }     else { 'latest' }
# `%LOCALAPPDATA%\Programs` is where a per-user, non-elevated install belongs on Windows, and
# `Local` rather than `Roaming` is deliberate: a roaming profile copies its contents to a domain
# server at logoff, and a 66 MB binary has no business being replicated — the same reason
# `ConfigHome` picks `%LOCALAPPDATA%` for the session (#301).
$InstallDir  = if ($env:LOOPKY_INSTALL_DIR) { $env:LOOPKY_INSTALL_DIR } else { "$env:LOCALAPPDATA\Programs\loopky" }

function Die([string]$Message) {
    Write-Host "loopky: $Message" -ForegroundColor Red
    exit 1
}

# The host matrix, and it is the same one the binary refuses outside of — see
# `cli/src/main/kotlin/com/github/jvsena42/loopky/cli/SupportedHost.kt`, which has to stay in step
# with this and with `install.sh`. An unshipped host is told what it is and why, never handed a 404
# from a URL it was never going to find.
$arch = $env:PROCESSOR_ARCHITECTURE
if ($env:PROCESSOR_ARCHITEW6432) { $arch = $env:PROCESSOR_ARCHITEW6432 }
switch ($arch) {
    'AMD64' { $Asset = 'loopky-windows-x86-64.exe' }
    'ARM64' {
        # The x64 binary does run here under emulation, but it would be the wrong thing to install:
        # the JVM inside it reports `aarch64` and cannot load an x64 `pubkycore.dll` into its own
        # process, so it would fail at the first homeserver call rather than at the install.
        Die "there is no ARM64 Windows build. The x64 binary runs under emulation but cannot load the x64 native library, so it is not offered here — see cli/README.md."
    }
    default {
        Die "no Windows build for $arch. The builds are Linux x86_64, macOS on Apple Silicon and Windows x86_64."
    }
}

# **Refused before anything is downloaded, rather than after.** `loopky.exe` imports
# `VCRUNTIME140.dll` and `VCRUNTIME140_1.dll` — the only two of its imports that are not in-box on
# Windows 10+ — so without the Visual C++ redistributable Windows refuses to start the process at
# all. Installing a binary that cannot run and then reporting success is the failure this project
# exists to avoid; most machines already have these, which makes it fail for the unlucky rather
# than for everyone, and that is exactly why it is worth checking rather than assuming.
$missing = @('vcruntime140.dll', 'vcruntime140_1.dll') |
    Where-Object { -not (Test-Path (Join-Path $env:SystemRoot "System32\$_")) }
if ($missing) {
    Die @"
the Visual C++ redistributable is missing ($($missing -join ', ')), and loopky.exe cannot start without it.
Install it once from https://aka.ms/vs/17/release/vc_redist.x64.exe and run this again.
(This is a stated gap rather than an oversight — see cli/README.md.)
"@
}

$Base = if ($Version -eq 'latest') {
    "https://github.com/$Repo/releases/latest/download"
} else {
    "https://github.com/$Repo/releases/download/$Version"
}

# Windows PowerShell 5.1 still defaults to TLS 1.0/1.1 on some hosts, which github.com refuses.
# Harmless where a newer default is already in place.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Tmp = Join-Path ([IO.Path]::GetTempPath()) ("loopky-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $Tmp -Force | Out-Null
try {
    $exe = Join-Path $Tmp 'loopky.exe'
    Write-Host "loopky: downloading $Asset"
    try {
        Invoke-WebRequest -Uri "$Base/$Asset" -OutFile $exe -UseBasicParsing
    } catch {
        Die "could not download $Base/$Asset - $($_.Exception.Message)"
    }

    # **Mandatory here, unlike `install.sh`.** That script degrades to an unchecked install when the
    # host has neither `sha256sum` nor `shasum`, because the minimal sandbox it targets may have
    # neither and the alternative the user falls back to is a bare `curl` with no check at all.
    # There is no such host here: `Get-FileHash` ships with PowerShell 4.0, so there is nobody to
    # degrade for and a skipped digest would be a choice rather than a limitation.
    $sha = Join-Path $Tmp 'loopky.exe.sha256'
    try {
        Invoke-WebRequest -Uri "$Base/$Asset.sha256" -OutFile $sha -UseBasicParsing
    } catch {
        Die "no published checksum for $Asset, so the download cannot be verified - $($_.Exception.Message)"
    }
    $expected = ((Get-Content -Raw $sha).Trim() -split '\s+')[0]
    $actual = (Get-FileHash -Algorithm SHA256 -Path $exe).Hash
    if ($actual -ne $expected) {
        Die "checksum mismatch: expected $expected, got $actual"
    }

    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    $target = Join-Path $InstallDir 'loopky.exe'
    try {
        Move-Item -Path $exe -Destination $target -Force
    } catch {
        # A running executable cannot be replaced on Windows, and the message for it is otherwise
        # an access-denied that reads as a permissions problem — which sends the reader to an
        # elevated prompt that will not help either.
        Die "could not write $target - $($_.Exception.Message). If loopky is running, close it and run this again."
    }

    Write-Host "loopky: installed to $target"
    & $target --version

    # Told, not done — the same trade `install.sh` makes about `~/.bashrc`. Editing the user's PATH
    # means writing to their registry environment, and an installer that does that behind you is
    # one you cannot cleanly undo on a machine where the whole install is "copy one file".
    $onPath = ($env:PATH -split ';' | Where-Object { $_.TrimEnd('\') -eq $InstallDir.TrimEnd('\') })
    if (-not $onPath) {
        Write-Host ""
        Write-Host "loopky: $InstallDir is not on your PATH. Add it for this user with:"
        Write-Host "  [Environment]::SetEnvironmentVariable('PATH', `"`$env:PATH;$InstallDir`", 'User')"
        Write-Host "  (open a new terminal afterwards), or call the binary by its full path."
    }
    Write-Host "loopky: tab completion is available - `loopky completion powershell`, see cli/README.md"
} finally {
    Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
}
