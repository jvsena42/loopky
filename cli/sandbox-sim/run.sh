#!/usr/bin/env bash
# Run the CLI through one agent-sandbox network shape and tabulate exit code, error and time.
#
#   ./run.sh claude-trusted          Claude Code on the web, default "Trusted" network access
#   ./run.sh claude-custom           the same list plus `loopky doctor`'s hosts ("Custom" + defaults)
#   ./run.sh codex-common            Codex cloud, "Common dependencies" preset (TLS-intercepting)
#   ./run.sh codex-custom            the same preset plus `loopky doctor`'s hosts
#   ./run.sh codex-custom-readonly   ...and "restrict to GET, HEAD, OPTIONS" switched on
#   ./run.sh authenticated           claude-custom behind a proxy that wants Basic credentials
#   ./run.sh intercepting            a proxy re-signing all TLS, its CA trusted system-wide
#   ./run.sh offline                 no egress: Codex's default agent phase, Claude's "None"
#
# Uses ../build/install/loopky (./gradlew :cli:installDist) unless LOOPKY_DIST points elsewhere.
# Set LOOPKY_SESSION to add the signed-in rows; nothing here stores or prints it. Set
# PUBKYCORE_DIR to a directory holding a libpubkycore.so to try an FFI build before it ships.
# JVM_TRUSTS_PROXY_CA=1 also hands the jar's JVM a trust store with the proxy's CA, as a Claude
# Code cloud session does through JAVA_TOOL_OPTIONS.
set -euo pipefail
cd "$(dirname "$0")"

# Squid's dstdomain: `.example.com` is the domain and its subdomains, a bare name that host alone —
# and Squid refuses to start on an entry another one already covers, so those are dropped.
squid_list() {
  grep -hv '^#' "$@" | sed 's/^\*\./\./' | sort -u | python3 -c '
import sys
entries = [l.strip() for l in sys.stdin if l.strip()]
wild = {e[1:] for e in entries if e.startswith(".")}
covered = lambda h: any(h == w or h.endswith("." + w) for w in wild)
for e in entries:
    if e.startswith(".") and any(e[1:] != w and e[1:].endswith("." + w) for w in wild):
        continue
    if not e.startswith(".") and covered(e):
        continue
    print(e)'
}
# Every entry also covering its subdomains, as the Codex preset's bare domains are read.
suffix_list() { grep -hv '^#' "$@" | sed 's/^\*\.//; s/^\([^.]\)/.\1/' | sort -u; }

export CODEX_READ_ONLY=0
case "${1:-}" in
  claude-trusted)  profile=allowlist; squid_list allowlists/claude-trusted.txt > allowlist.active.txt ;;
  claude-custom)   profile=allowlist; squid_list allowlists/claude-trusted.txt allowlists/loopky.txt > allowlist.active.txt ;;
  authenticated)   profile=authenticated; squid_list allowlists/claude-trusted.txt allowlists/loopky.txt > allowlist.active.txt ;;
  codex-common)    profile=codex; grep -hv '^#' allowlists/codex-common.txt > allowlist.active.txt ;;
  codex-custom)    profile=codex; grep -hv '^#' allowlists/codex-common.txt allowlists/loopky.txt > allowlist.active.txt ;;
  codex-custom-readonly)
                   profile=codex; CODEX_READ_ONLY=1
                   grep -hv '^#' allowlists/codex-common.txt allowlists/loopky.txt > allowlist.active.txt ;;
  intercepting)    profile=intercepting ;;
  offline)         profile="" ;;
  *) sed -n '2,19p' "$0"; exit 2 ;;
esac

# The proxies reach the internet through this machine. If its own egress re-signs TLS — a Claude
# Code cloud session's does — every profile silently turns into `intercepting` and the table
# describes the host, not the change. Checked with the stock CA store, before anything else.
PREFLIGHT='
import sys, urllib.request, urllib.error
try:
    urllib.request.urlopen("https://pkarr.pubky.app/", timeout=15)
except urllib.error.HTTPError:
    pass  # any HTTP answer means TLS verified against the stock store
except Exception as e:
    sys.exit(f"{type(e).__name__}: {e}")
'
if ! docker run --rm --entrypoint python3 mitmproxy/mitmproxy:latest -c "$PREFLIGHT" 2>/tmp/sandbox-sim-preflight.err; then
  if grep -qiE 'certificate|SSL' /tmp/sandbox-sim-preflight.err; then
    echo "run.sh: this machine's own egress intercepts TLS ($(head -c 200 /tmp/sandbox-sim-preflight.err))." >&2
    echo "run.sh: every profile would measure that, not loopky. Run it on a host with plain egress." >&2
    exit 3
  fi
  echo "run.sh: preflight could not reach pkarr.pubky.app directly: $(head -c 200 /tmp/sandbox-sim-preflight.err)" >&2
  exit 3
fi

if [ -n "$profile" ]; then
  export PROXY_HOST="$profile-proxy" SIM_PROXY_URL="http://$profile-proxy:3128"
  # htpasswd's throwaway pair, percent-encoded as a real proxy URL would carry it.
  [ "$profile" = authenticated ] && SIM_PROXY_URL="http://agent:p%40ss%3Aword@$profile-proxy:3128"
  docker compose --profile "$profile" up -d --force-recreate --wait "$profile-proxy" >/dev/null 2>&1 || {
    echo "run.sh: $profile-proxy did not start:" >&2
    docker compose --profile "$profile" logs "$profile-proxy" 2>&1 | grep -iE 'fatal|error' | head -5 >&2
    exit 1
  }
fi
case "$profile" in intercepting|codex) export EXTRA_CA=/ca/mitmproxy-ca-cert.pem ;; esac

docker compose build -q client >/dev/null
docker compose run --rm -T client bash -s <<'SCRIPT'
row() {
  local label=$1; shift
  local start=$(date +%s%N)
  /opt/loopky/bin/loopky "$@" --json >/tmp/out 2>/tmp/err; local code=$?
  local ms=$(( ($(date +%s%N) - start) / 1000000 ))
  local err=$(grep -o '"code":"[a-z_]*"' /tmp/out | head -1 | cut -d'"' -f4)
  local msg=$(grep -o '"message":"[^"]*' /tmp/out | head -1 | cut -d'"' -f4 | cut -c1-70)
  local noise=$(grep -c 'Could not bootstrap' /tmp/err || true)
  printf '%-22s %4s %-16s %6sms  %3s  %s\n' "$label" "$code" "${err:-ok}" "$ms" "$noise" "$msg"
}
printf '%-22s %4s %-16s %8s  %3s  %s\n' command exit code time dht message
row "import --dry-run"      import /fixtures/cards.tsv --title Sim --dry-run
row "doctor"                doctor
row "tag trending"          tag trending --limit 3
row "login (5s)"            login --url-only --timeout 5
if [ -n "${LOOPKY_SESSION:-}" ]; then
  row "whoami"              whoami
  row "deck list"           deck list
  # A fixed id, deleted straight after, so a run leaves nothing on the account. These two are the
  # rows a read-only (GET/HEAD/OPTIONS) policy exists to stop.
  row "deck create"         deck create --title "sandbox-sim" --id sandboxsim01 --if-not-exists --from-file /fixtures/cards.tsv
  row "deck delete"         deck delete sandboxsim01
fi
SCRIPT
