#!/usr/bin/env bash
# Rolls one environment to an image digest, and back if it does not come up healthy.
#
#   deploy.sh <staging|production> ghcr.io/jvsena42/loopky-mcp@sha256:<digest>
#
# CD reaches this as an SSH *forced command*, one key per environment, with the environment
# fixed in the command itself (`command="/opt/loopky-mcp/deploy.sh production"`, see README.md).
# The environment is taken from that argv and never from SSH_ORIGINAL_COMMAND, which whoever holds
# the key chooses: otherwise the staging key, readable by any workflow, could roll production and
# skip its required reviewers. The caller sends only the digest, validated here.
set -euo pipefail

ROOT=/opt/loopky-mcp
STATE=/srv/loopky-mcp
IMAGE_RE='^ghcr\.io/jvsena42/loopky-mcp@sha256:[0-9a-f]{64}$'

usage() { echo "usage: deploy.sh <staging|production> <image@digest>" >&2; exit 2; }

if [ -n "${SSH_ORIGINAL_COMMAND:-}" ]; then
  FIXED="${1:-}"
  [ "$#" -eq 1 ] || { echo "this key's forced command must name exactly one environment" >&2; exit 2; }
  set -f
  # shellcheck disable=SC2086
  set -- $SSH_ORIGINAL_COMMAND
  [ "${1:-}" = deploy ] && shift
  [ "$#" -eq 2 ] || usage
  [ "$1" = "$FIXED" ] || { echo "this key deploys $FIXED only, not $1" >&2; exit 2; }
fi
[ "$#" -eq 2 ] || usage

ENVIRONMENT="$1"
IMAGE="$2"
case "$ENVIRONMENT" in
  production) HOST_PORT=8081 ;;
  staging) HOST_PORT=8082 ;;
  *) usage ;;
esac
[[ "$IMAGE" =~ $IMAGE_RE ]] || { echo "image must be ghcr.io/jvsena42/loopky-mcp@sha256:<64 hex>" >&2; exit 2; }

# shellcheck source=/dev/null
source "/etc/loopky-mcp/$ENVIRONMENT/env"   # LOOPKY_MCP_PUBLIC_URL, and nothing secret
export LOOPKY_MCP_ENV="$ENVIRONMENT" HOST_PORT LOOPKY_MCP_PUBLIC_URL

exec 9>"$STATE/$ENVIRONMENT/deploy.lock"
flock -n 9 || { echo "a deploy to $ENVIRONMENT is already running" >&2; exit 75; }

CURRENT_FILE="$STATE/$ENVIRONMENT/image"
PREVIOUS="$(cat "$CURRENT_FILE" 2>/dev/null || true)"

up() {
  IMAGE="$1" docker compose -f "$ROOT/compose.yml" up -d --remove-orphans --wait --wait-timeout 90
}

healthy() {
  for _ in $(seq 1 30); do
    curl -fsS "http://127.0.0.1:$HOST_PORT/healthz" >/dev/null 2>&1 && return 0
    sleep 2
  done
  return 1
}

docker pull --quiet "$IMAGE" >/dev/null
if up "$IMAGE" && healthy; then
  echo "$IMAGE" > "$CURRENT_FILE"
  echo "$ENVIRONMENT now runs $IMAGE"
  docker image prune -f --filter "label!=keep" >/dev/null 2>&1 || true
  exit 0
fi

echo "$IMAGE did not come up healthy on $ENVIRONMENT" >&2
if [ -n "$PREVIOUS" ]; then
  echo "rolling back to $PREVIOUS" >&2
  up "$PREVIOUS" && healthy || echo "ROLLBACK FAILED: $ENVIRONMENT is down" >&2
fi
exit 1
