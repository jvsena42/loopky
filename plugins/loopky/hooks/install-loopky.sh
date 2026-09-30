#!/bin/sh
# SessionStart hook: puts `loopky` on PATH in a Claude Code on the web container (#388).
#
# Only there. On a laptop, installing a binary because a plugin loaded would be a surprise, and the
# skill already says how. A cloud container is rebuilt for every session, so without this the
# first thing every session does is the same install.
#
# It never fails the session: every path exits 0 and says what happened in one line, which the
# session reads as context. The installer is the release's own, not `main`'s (cli/README.md).
# github.com and release-assets.githubusercontent.com are on the web's default Trusted list, so
# this works before the user has touched the allowlist — the Pubky hosts are what `loopky doctor`
# asks them for afterwards.

INSTALLER=${LOOPKY_INSTALLER_URL:-https://github.com/jvsena42/loopky/releases/latest/download/install.sh}
BIN_DIR="$HOME/.local/bin"

[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0

persist_path() {
  case ":$PATH:" in *":$BIN_DIR:"*) return ;; esac
  # CLAUDE_ENV_FILE is sourced before each Bash command, so PATH set here outlives this hook.
  [ -n "${CLAUDE_ENV_FILE:-}" ] || return
  line="export PATH=\"$BIN_DIR:\$PATH\""
  grep -qxF "$line" "$CLAUDE_ENV_FILE" 2>/dev/null || printf '%s\n' "$line" >> "$CLAUDE_ENV_FILE"
}

if command -v loopky >/dev/null 2>&1 || [ -x "$BIN_DIR/loopky" ]; then
  persist_path
  echo "loopky is installed: $(PATH="$BIN_DIR:$PATH" loopky --version --no-update-check 2>/dev/null)"
  exit 0
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "loopky is not installed and this container has no curl; see the loopky skill's install step."
  exit 0
fi

# Downloaded first, never `curl | sh`: without pipefail a failed download pipes nothing into `sh`,
# which exits 0, and the session would be told loopky is installed when nothing was fetched.
tmp=$(mktemp) || exit 0
trap 'rm -f "$tmp"' EXIT
if curl -fsSL "$INSTALLER" -o "$tmp" && sh "$tmp" >&2 \
  && version=$("$BIN_DIR/loopky" --version --no-update-check 2>/dev/null); then
  persist_path
  echo "Installed $version to $BIN_DIR."
else
  echo "Installing loopky failed. If github.com answered 403, attach jvsena42/loopky to this session or install it in the environment's setup script; otherwise follow the loopky skill's install step."
fi
exit 0
