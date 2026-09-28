#!/usr/bin/env bash
# Run the CLI through one agent-sandbox network shape and tabulate exit code, error and time.
#
#   ./run.sh sandbox-default   allowlist proxy, package managers only — the out-of-the-box sandbox
#   ./run.sh sandbox-loopky    the same proxy with `loopky doctor`'s allowlist added
#   ./run.sh authenticated     sandbox-loopky behind a proxy that wants Basic credentials
#   ./run.sh intercepting      a TLS-intercepting proxy whose CA the sandbox trusts system-wide
#   ./run.sh offline           no egress at all
#
# Uses ../build/install/loopky (./gradlew :cli:installDist) unless LOOPKY_DIST points elsewhere.
# Set LOOPKY_SESSION to add the signed-in rows; nothing here stores or prints it. Set
# PUBKYCORE_DIR to a directory holding a libpubkycore.so to try an FFI build before it ships.
set -euo pipefail
cd "$(dirname "$0")"

case "${1:-}" in
  sandbox-default) profile=allowlist; export ALLOWLIST=./allowlist.sandbox-default.txt ;;
  sandbox-loopky)  profile=allowlist; export ALLOWLIST=./allowlist.combined.txt
                   cat allowlist.sandbox-default.txt allowlist.loopky.txt | grep -v '^#' \
                     | grep -v '^release-assets' | sort -u > allowlist.combined.txt ;;
  authenticated)   profile=authenticated
                   cat allowlist.sandbox-default.txt allowlist.loopky.txt | grep -v '^#' \
                     | grep -v '^release-assets' | sort -u > allowlist.combined.txt ;;
  intercepting)    profile=intercepting ;;
  offline)         profile="" ;;
  *) sed -n '2,14p' "$0"; exit 2 ;;
esac

if [ -n "$profile" ]; then
  export PROXY_HOST="$profile-proxy" SIM_PROXY_URL="http://$profile-proxy:3128"
  # htpasswd's throwaway pair, percent-encoded as a real proxy URL would carry it.
  [ "$profile" = authenticated ] && SIM_PROXY_URL="http://agent:p%40ss%3Aword@$profile-proxy:3128"
  docker compose --profile "$profile" up -d --wait "$profile-proxy" >/dev/null 2>&1
fi
[ "$profile" = intercepting ] && export EXTRA_CA=/ca/mitmproxy-ca-cert.pem

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
fi
SCRIPT
