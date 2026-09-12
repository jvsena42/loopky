#!/bin/sh
#
# `loopky` installer.
#
#   curl -fsSL https://github.com/jvsena42/loopky/releases/latest/download/install.sh | sh
#
# **From the release, not from `main`.** This file is published as a release asset at the tag, and
# that is the URL to document (#209). The obvious alternative —
# `raw.githubusercontent.com/jvsena42/loopky/main/cli/install.sh` — pipes whatever `main` happens
# to be at that second into `sh`: an unreviewed commit, a half-finished branch merge or a rewritten
# history all reach the user before anyone has released them. Every other artifact here is pinned
# to a tag; the script that fetches them should be too. It still lives in the repository, and
# running it from a checkout is fine — what changes is what the README tells strangers to pipe.
#
# There is nothing here you cannot do by hand, and doing it by hand is a supported answer — the
# binary is one file and the release page lists a direct URL per host (#210):
#
#   curl -fsSL <url> -o ~/.local/bin/loopky && chmod +x ~/.local/bin/loopky
#
# What this adds is picking the right file, checking its digest, and refusing a host there is no
# build for with a message rather than a 404.
#
# POSIX sh on purpose: the sandbox this is written for may have no bash.
set -eu

REPO="${LOOPKY_REPO:-jvsena42/loopky}"
VERSION="${LOOPKY_VERSION:-latest}"
# `~/.local/bin` because the target is a **non-root** sandbox. Nothing here needs sudo, and an
# installer that asks for it cannot run where this is meant to run.
INSTALL_DIR="${LOOPKY_INSTALL_DIR:-$HOME/.local/bin}"

die() { printf '%s\n' "loopky: $*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "$1 is required and was not found"; }

# The host matrix, and it is the same one the binary itself refuses outside of — see
# `cli/src/main/kotlin/com/github/jvsena42/loopky/cli/SupportedHost.kt`, which has to stay in
# step with this. An unshipped host is told what it is and why, never handed a 404 from a URL it
# was never going to find.
asset_for_host() {
    os="$(uname -s)"
    arch="$(uname -m)"
    case "$os:$arch" in
        Linux:x86_64|Linux:amd64) printf 'loopky-linux-x86-64' ;;
        Darwin:arm64|Darwin:aarch64) printf 'loopky-macos-aarch64' ;;
        Darwin:x86_64)
            die "there is one macOS build and it is for Apple Silicon. An Intel Mac is not a
target — see cli/README.md. (If this *is* an Apple Silicon Mac, you are in a Rosetta shell.)" ;;
        Linux:aarch64|Linux:arm64)
            die "no Linux arm64 build yet. What is missing is a libpubkycore for that host, not
this client — see shared/src/jvmMain/resources/README.md." ;;
        # Reached under Git Bash, MSYS or Cygwin, where `uname -s` answers with one of these rather
        # than the Windows anyone would type. There *is* a Windows build and this is not how you
        # get it: a POSIX shell here says nothing about where the binary will be run from, and the
        # install directory, the PATH advice and the digest check all differ. Named rather than
        # dropped into the catch-all, which would say no build exists at all.
        MINGW*|MSYS*|CYGWIN*)
            die "there is a Windows build, and PowerShell installs it:
    irm https://github.com/$REPO/releases/latest/download/install.ps1 | iex
This script installs the POSIX rows only — see cli/README.md (#301)." ;;
        *) die "no build for $os $arch. The builds are Linux x86_64, macOS on Apple Silicon and Windows x86_64." ;;
    esac
}

need curl
need uname

ASSET="$(asset_for_host)"
if [ "$VERSION" = "latest" ]; then
    BASE="https://github.com/$REPO/releases/latest/download"
else
    BASE="https://github.com/$REPO/releases/download/$VERSION"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT INT TERM

printf 'loopky: downloading %s\n' "$ASSET" >&2
curl -fsSL "$BASE/$ASSET" -o "$TMP/loopky" \
    || die "could not download $BASE/$ASSET"

# The digest is checked when a tool for it exists and skipped, loudly, when none does. Failing the
# install on a missing `sha256sum` would be worse than saying so: the sandbox this targets is
# frequently minimal, and the alternative the user falls back to is a plain curl with no check at
# all.
if curl -fsSL "$BASE/$ASSET.sha256" -o "$TMP/loopky.sha256" 2>/dev/null; then
    EXPECTED="$(cut -d' ' -f1 < "$TMP/loopky.sha256")"
    if command -v sha256sum >/dev/null 2>&1; then
        ACTUAL="$(sha256sum "$TMP/loopky" | cut -d' ' -f1)"
    elif command -v shasum >/dev/null 2>&1; then
        ACTUAL="$(shasum -a 256 "$TMP/loopky" | cut -d' ' -f1)"
    else
        ACTUAL=""
        printf 'loopky: no sha256sum or shasum on this host — digest NOT checked\n' >&2
    fi
    if [ -n "$ACTUAL" ] && [ "$ACTUAL" != "$EXPECTED" ]; then
        die "checksum mismatch: expected $EXPECTED, got $ACTUAL"
    fi
else
    printf 'loopky: no published checksum for %s — digest not checked\n' "$ASSET" >&2
fi

mkdir -p "$INSTALL_DIR"
chmod +x "$TMP/loopky"
# `mv` within the same filesystem where possible, so the binary appears whole or not at all rather
# than half-written under a name something else may already be running.
mv "$TMP/loopky" "$INSTALL_DIR/loopky" 2>/dev/null || {
    cp "$TMP/loopky" "$INSTALL_DIR/loopky.new" && mv "$INSTALL_DIR/loopky.new" "$INSTALL_DIR/loopky"
}

printf 'loopky: installed to %s\n' "$INSTALL_DIR/loopky" >&2
"$INSTALL_DIR/loopky" --version

case ":$PATH:" in
    *":$INSTALL_DIR:"*) ;;
    *) printf 'loopky: %s is not on your PATH — add it, or call the binary by its full path\n' \
           "$INSTALL_DIR" >&2 ;;
esac

# Told, not done. Enabling completions means writing to a shell rc file or a system directory, and
# an installer that edits `~/.bashrc` behind you is one you cannot cleanly undo — on a box where
# the whole install is "copy one file", that is the wrong trade. The line is one command away.
printf 'loopky: tab completion is available — `loopky completion bash|zsh|fish`, see cli/README.md\n' >&2
