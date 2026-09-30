#!/bin/sh
# Installs the `loopky` binary for the loopky skill, on Linux x86_64 and macOS on Apple Silicon.
#
#   sh scripts/install.sh            # from the skill's directory
#
# Shipped inside the plugin so the skill never pipes a download into a shell: plugin directories
# flag that as code that can change after review (#405). It fetches the release binary and its
# published SHA-256, refuses a mismatch or a missing checksum, and moves the binary into place. It
# runs nothing it downloaded — `loopky --version` afterwards is the agent's step, not this one's.
#
# Same assets, install directory and host matrix as the release's cli/install.sh, which
# AgentPluginTest holds it to. POSIX sh: the sandbox this is written for may have no bash.
set -eu

REPO="jvsena42/loopky"
BASE="https://github.com/$REPO/releases/latest/download"
INSTALL_DIR="${LOOPKY_INSTALL_DIR:-$HOME/.local/bin}"

die() { printf 'loopky: %s\n' "$*" >&2; exit 1; }

command -v curl > /dev/null 2>&1 || die "curl is required and was not found"

case "$(uname -s):$(uname -m)" in
    Linux:x86_64|Linux:amd64) ASSET=loopky-linux-x86-64 ;;
    Darwin:arm64|Darwin:aarch64) ASSET=loopky-macos-aarch64 ;;
    Darwin:x86_64) die "there is one macOS build and it is for Apple Silicon; an Intel Mac is not a target." ;;
    MINGW*|MSYS*|CYGWIN*) die "this is Windows: run scripts/install.ps1 from PowerShell instead." ;;
    *) die "no build for $(uname -s) $(uname -m). The builds are Linux x86_64, macOS on Apple Silicon and Windows x86_64." ;;
esac

if command -v sha256sum > /dev/null 2>&1; then
    digest() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum > /dev/null 2>&1; then
    digest() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
    die "sha256sum or shasum is required to verify the download, and neither was found"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT INT TERM

printf 'loopky: downloading %s\n' "$ASSET" >&2
curl -fsSL "$BASE/$ASSET" -o "$TMP/loopky" \
    || die "could not download $BASE/$ASSET. Behind an allowlist, github.com and release-assets.githubusercontent.com have to be allowed."
curl -fsSL "$BASE/$ASSET.sha256" -o "$TMP/loopky.sha256" \
    || die "could not download the published checksum for $ASSET, so the binary cannot be verified"

EXPECTED="$(cut -d' ' -f1 < "$TMP/loopky.sha256")"
ACTUAL="$(digest "$TMP/loopky")"
[ "$ACTUAL" = "$EXPECTED" ] || die "checksum mismatch: expected $EXPECTED, got $ACTUAL"

mkdir -p "$INSTALL_DIR"
chmod 755 "$TMP/loopky"
mv "$TMP/loopky" "$INSTALL_DIR/loopky"
printf 'loopky: installed to %s (sha256 %s)\n' "$INSTALL_DIR/loopky" "$ACTUAL" >&2

case ":$PATH:" in
    *":$INSTALL_DIR:"*) ;;
    *) printf 'loopky: %s is not on PATH; add it, or call %s\n' "$INSTALL_DIR" "$INSTALL_DIR/loopky" >&2 ;;
esac
