#!/usr/bin/env bash
# Boots the server image behind the VPS's Caddyfile and checks the edge refuses what it should
# before the JVM sees it (#518): an oversized body, a burst from one address, a stalled header.
#
#   bash mcp/deploy/edge-test.sh <server-image> <caddy-image>
#
# The Caddy image is mcp/deploy/caddy/Dockerfile's `runtime` stage. It shares the server
# container's network namespace, so nothing here binds a host port but the one probed.
set -euo pipefail

IMAGE="${1:?usage: edge-test.sh <server-image> <caddy-image>}"
CADDY="${2:?usage: edge-test.sh <server-image> <caddy-image>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
NAME="loopky-mcp-edge-$$"
PORT=18443
URL="http://127.0.0.1:$PORT"
as_root() { docker run --rm --user 0 --entrypoint "$1" -v "$WORK:/w" "$IMAGE" "${@:2}"; }
cleanup() {
  docker logs "$NAME-caddy" 2>&1 | tail -20 || true
  docker rm -f "$NAME-caddy" "$NAME" >/dev/null 2>&1 || true
  as_root rm -rf /w/data /w/secrets >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT
fail() { echo "edge test: $*" >&2; exit 1; }

mkdir -p "$WORK/data" "$WORK/secrets"
head -c 32 /dev/urandom | base64 > "$WORK/secrets/master_key"
chmod 0600 "$WORK/secrets/master_key"
as_root chown -R 10001 /w/data /w/secrets/master_key

docker run -d --name "$NAME" -p "127.0.0.1:$PORT:$PORT" \
  --read-only --tmpfs /tmp:rw,exec,nosuid,size=64m \
  --cap-drop ALL --security-opt no-new-privileges \
  -v "$WORK/data:/data" \
  -v "$WORK/secrets/master_key:/run/secrets/master_key:ro" \
  -e LOOPKY_MCP_ENV=staging \
  -e LOOPKY_MCP_PUBLIC_URL=https://mcp.edge.invalid \
  -e LOOPKY_MCP_MASTER_KEY_FILE=/run/secrets/master_key \
  "$IMAGE" >/dev/null
# LOOPKY_MCP_SHARED_EGRESS is the range Caddy treats as Anthropic's; the second boot claims every address.
edge() {
  docker rm -f "$NAME-caddy" >/dev/null 2>&1 || true
  docker run -d --name "$NAME-caddy" --network "container:$NAME" \
    -v "$HERE/Caddyfile.example:/etc/caddy/Caddyfile:ro" \
    -e LOOPKY_MCP_PRODUCTION_SITE="http://:$PORT" \
    -e LOOPKY_MCP_PRODUCTION_UPSTREAM=127.0.0.1:8080 \
    -e LOOPKY_MCP_STAGING_SITE=http://:18444 \
    ${1:+-e LOOPKY_MCP_SHARED_EGRESS="$1"} \
    "$CADDY" >/dev/null
  for _ in $(seq 1 60); do
    [ "$(curl -sS -o /dev/null -w '%{http_code}' "$URL/healthz" 2>/dev/null)" = 200 ] && return 0
    sleep 1
  done
  return 1
}
edge || fail "the server never answered through Caddy"

# The status is what is asserted, never curl's exit: on a 413 Caddy may close the connection
# before curl has sent the whole body, and curl then fails after printing the code.
code() { curl -sS -o /dev/null -w '%{http_code}' "$@" 2>/dev/null || true; }
# N requests at once, one status per line; the ceiling on Anthropic's range is per second, which
# one request at a time cannot reach.
burst() {
  local n="$1"; shift
  curl -sS -Z --parallel-max 20 "$@" -w '\n%{http_code}\n' "$URL/healthz?n=[1-$n]" 2>/dev/null \
    | grep -xE '[0-9]{3}' || true
}
count() { grep -cx "$1" <<<"$2" || true; }

# A small unauthenticated call still reaches the server and gets its 401. The same call with a
# body one byte over the limit is a 413, which only the edge gives: the server would say 401.
small="$(code -X POST "$URL/mcp" -H 'Content-Type: application/json' --data '{}')"
[ "$small" = 401 ] || fail "a small /mcp call answered $small, not the server's 401"
head -c $((2 * 1024 * 1024 + 1)) /dev/zero > "$WORK/big"
big="$(code -X POST "$URL/mcp" -H 'Content-Type: application/json' --data-binary "@$WORK/big")"
[ "$big" = 413 ] || fail "a 2 MiB + 1 body on /mcp answered $big, not 413"
head -c $((16 * 1024 + 1)) /dev/zero > "$WORK/form"
form="$(code -X POST "$URL/token" -H 'Content-Type: application/x-www-form-urlencoded' --data-binary "@$WORK/form")"
[ "$form" = 413 ] || fail "a 16 KiB + 1 body on /token answered $form, not 413"

# A header that never finishes is dropped at read_header (10s), not held open. It also lets the
# requests above leave the per-address window before the burst below.
start=$SECONDS
exec 3<>"/dev/tcp/127.0.0.1/$PORT"
printf 'GET /healthz HTTP/1.1\r\nHost: x\r\n' >&3
timeout 30 cat <&3 >/dev/null || true
exec 3<&-
[ $((SECONDS - start)) -lt 25 ] || fail "a stalled header was held for $((SECONDS - start))s"

# One address outside Anthropic's range: 50 requests per 10s, then 429 with Retry-After. The
# server does not limit /healthz, so the refusal is the edge's.
codes="$(for _ in $(seq 1 60); do code "$URL/healthz"; echo; done)"
ok="$(count 200 "$codes")"
[ "$ok" -ge 45 ] && [ "$ok" -le 50 ] || fail "$ok of 60 requests from one address got through, not 50"
[ "$(count 429 "$codes")" -ge 10 ] || fail "a burst from one address was not refused"
headers="$(curl -sS -o /dev/null -D - "$URL/healthz" 2>/dev/null || true)"
grep -qi '^retry-after:' <<<"$headers" || fail "a 429 without Retry-After"

# Anthropic's range: a ceiling per address, so neither anonymous calls nor made-up bearers are
# unbounded there, and a budget per bearer inside it. A key that collapsed the bearers would
# throttle every Claude user at once.
edge 0.0.0.0/0 || fail "Caddy did not come back with every address as shared egress"
sleep 1
[ "$(count 429 "$(burst 400)")" -gt 0 ] || fail "400 anonymous requests at once from shared egress were not refused"
sleep 6
codes="$(for _ in $(seq 1 60); do code -H 'Authorization: Bearer one' "$URL/healthz"; echo; done)"
ok="$(count 200 "$codes")"
[ "$ok" = 50 ] || fail "bearer one got $ok of 60 requests through, not 50"
[ "$(code -H 'Authorization: Bearer two' "$URL/healthz")" = 200 ] || fail "bearer two shared bearer one's budget"

echo "edge test passed"
