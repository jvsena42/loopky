# Installs the `loopky` binary for the loopky skill, on Windows x86_64.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install.ps1   # from the skill's directory
#
# Shipped inside the plugin so the skill never pipes a download into a shell: plugin directories
# flag that as code that can change after review (#405). `-ExecutionPolicy Bypass` is scoped to
# that one process and to this file, which is part of the reviewed plugin; Windows' default policy
# refuses every script otherwise.
#
# It fetches the release binary and its published SHA-256, refuses a mismatch, and moves the binary
# into place. It runs nothing it downloaded — `loopky --version` afterwards is the agent's step.
# Same asset, install directory and Visual C++ check as the release's cli/install.ps1, which
# AgentPluginTest holds it to.
$ErrorActionPreference = 'Stop'

$Repo = 'jvsena42/loopky'
$Base = "https://github.com/$Repo/releases/latest/download"
$InstallDir = if ($env:LOOPKY_INSTALL_DIR) { $env:LOOPKY_INSTALL_DIR } else { "$env:LOCALAPPDATA\Programs\loopky" }

function Die([string]$Message) { throw "loopky: $Message" }

$arch = $env:PROCESSOR_ARCHITECTURE
if ($env:PROCESSOR_ARCHITEW6432) { $arch = $env:PROCESSOR_ARCHITEW6432 }
if ($arch -ne 'AMD64') {
    Die "no Windows build for $arch. The builds are Linux x86_64, macOS on Apple Silicon and Windows x86_64."
}
$Asset = 'loopky-windows-x86-64.exe'

# loopky.exe cannot start without the Visual C++ runtime, which is not part of Windows.
$system = if (Test-Path "$env:SystemRoot\Sysnative\kernel32.dll") { "$env:SystemRoot\Sysnative" } else { "$env:SystemRoot\System32" }
$missing = @('vcruntime140.dll', 'vcruntime140_1.dll') | Where-Object { -not (Test-Path (Join-Path $system $_)) }
if ($missing) {
    Die "the Visual C++ redistributable is missing ($($missing -join ', ')). Install it once from https://aka.ms/vc14/vc_redist.x64.exe and run this again."
}

[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$Tmp = Join-Path ([IO.Path]::GetTempPath()) ("loopky-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $Tmp -Force | Out-Null
try {
    $exe = Join-Path $Tmp 'loopky.exe'
    $sha = Join-Path $Tmp 'loopky.exe.sha256'
    Write-Host "loopky: downloading $Asset"
    try { Invoke-WebRequest -Uri "$Base/$Asset" -OutFile $exe -UseBasicParsing }
    catch { Die "could not download $Base/$Asset - $($_.Exception.Message)" }
    try { Invoke-WebRequest -Uri "$Base/$Asset.sha256" -OutFile $sha -UseBasicParsing }
    catch { Die "could not download the published checksum for $Asset, so the binary cannot be verified" }

    $expected = ((Get-Content -Raw $sha).Trim() -split '\s+')[0]
    $actual = (Get-FileHash -Algorithm SHA256 -Path $exe).Hash
    if ($actual -ne $expected) { Die "checksum mismatch: expected $expected, got $actual" }

    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    $target = Join-Path $InstallDir 'loopky.exe'
    try { Move-Item -Path $exe -Destination $target -Force }
    catch { Die "could not write $target - $($_.Exception.Message). If loopky is running, close it and run this again." }
    Write-Host "loopky: installed to $target (sha256 $actual)"

    $onPath = $env:PATH -split ';' | Where-Object { $_.TrimEnd('\') -eq $InstallDir.TrimEnd('\') }
    if (-not $onPath) { Write-Host "loopky: $InstallDir is not on PATH; add it, or call $target" }
} finally {
    Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
}
