#!/usr/bin/env bash
# Runs a `loopky-mcp admin` command against one environment, as root on the VPS.
#
#   admin.sh <staging|production> revoke-all   # a leaked database or key: revoke every grant
#   admin.sh <staging|production> rotate-key   # re-wrap every credential under a fresh key
#
# The server holds the data directory's lock, so the command cannot run beside it: this stops
# the service, runs the command in a one-off container from the image that was running, and
# starts the service again whatever happened. After a leak, run revoke-all and then rotate-key.
set -euo pipefail

ENVIRONMENT="${1:-}"
COMMAND="${2:-}"
case "$ENVIRONMENT" in
  production) HOST_PORT=8081 ;;
  staging) HOST_PORT=8082 ;;
  *) echo "usage: admin.sh <staging|production> <revoke-all|rotate-key>" >&2; exit 2 ;;
esac
case "$COMMAND" in revoke-all | rotate-key) ;; *) echo "command must be revoke-all or rotate-key" >&2; exit 2 ;; esac

ROOT=/opt/loopky-mcp
STATE=/srv/loopky-mcp
KEYS="/etc/loopky-mcp/$ENVIRONMENT"

# shellcheck source=/dev/null
source "$KEYS/env"
IMAGE="$(cat "$STATE/$ENVIRONMENT/image")"
export LOOPKY_MCP_ENV="$ENVIRONMENT" HOST_PORT LOOPKY_MCP_PUBLIC_URL IMAGE
compose() { docker compose -f "$ROOT/compose.yml" "$@"; }

# Never beside a deploy, which would start the server again underneath the command.
exec 9>"$STATE/$ENVIRONMENT/deploy.lock"
flock -n 9 || { echo "a deploy to $ENVIRONMENT is running; try again when it ends" >&2; exit 75; }

compose stop mcp
trap 'compose up -d --wait --wait-timeout 90 || echo "STARTING $ENVIRONMENT FAILED: it is down" >&2' EXIT

case "$COMMAND" in
  revoke-all)
    compose run --rm --no-deps mcp admin revoke-all
    ;;
  rotate-key)
    NEXT="$KEYS/master_key.next"
    head -c 32 /dev/urandom | base64 > "$NEXT"
    chown 10001:10001 "$NEXT"
    chmod 0400 "$NEXT"
    compose run --rm --no-deps -v "$NEXT:/run/secrets/next_master_key:ro" mcp \
      admin rotate-key /run/secrets/next_master_key
    # The database is under the new key now and the server refuses the old one.
    mv -f "$NEXT" "$KEYS/master_key"
    ;;
esac
