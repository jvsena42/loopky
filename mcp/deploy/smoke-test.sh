#!/usr/bin/env bash
# Boots an image the way compose.yml runs it — non-root, read-only root, tmpfs /tmp, a 0600 master
# key it does not own the directory of — and probes what answers without a homeserver.
#
#   bash mcp/deploy/smoke-test.sh <image>
set -euo pipefail

IMAGE="${1:?usage: smoke-test.sh <image>}"
WORK="$(mktemp -d)"
NAME="loopky-mcp-smoke-$$"
PORT=18080
# Ownership changes go through a root container rather than sudo, so this runs the same on a
# runner and on a laptop without a password prompt.
as_root() { docker run --rm --user 0 --entrypoint "$1" -v "$WORK:/w" "$IMAGE" "${@:2}"; }
cleanup() {
  docker logs "$NAME" 2>&1 | tail -50 || true
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  as_root rm -rf /w/data /w/secrets >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

mkdir -p "$WORK/data" "$WORK/secrets"
head -c 32 /dev/urandom | base64 > "$WORK/secrets/master_key"
chmod 0600 "$WORK/secrets/master_key"
as_root chown -R 10001 /w/data /w/secrets/master_key

# The constraints compose runs it under. `serve` starts the server; `admin …` runs one admin command
# in a one-off container, the way mcp/deploy/admin.sh does on the VPS.
container() {
  docker run \
    --read-only --tmpfs /tmp:rw,exec,nosuid,size=64m \
    --cap-drop ALL --security-opt no-new-privileges \
    -v "$WORK/data:/data" \
    -v "$WORK/secrets/master_key:/run/secrets/master_key:ro" \
    -e LOOPKY_MCP_ENV=staging \
    -e LOOPKY_MCP_PUBLIC_URL=https://mcp.smoke.invalid \
    -e LOOPKY_MCP_MASTER_KEY_FILE=/run/secrets/master_key \
    "$@"
}
serve() { container -d --name "$NAME" -p "127.0.0.1:$PORT:8080" "$IMAGE" >/dev/null; }
wait_healthy() {
  for _ in $(seq 1 60); do
    curl -fsS "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

serve

wait_healthy

curl -fsS "http://127.0.0.1:$PORT/healthz"; echo

meta="$(curl -fsS "http://127.0.0.1:$PORT/.well-known/oauth-authorization-server")"
jq -e '.issuer == "https://mcp.smoke.invalid" and (.code_challenge_methods_supported | index("S256"))' <<<"$meta" >/dev/null
jq -e '.resource == "https://mcp.smoke.invalid/mcp"' \
  <<<"$(curl -fsS "http://127.0.0.1:$PORT/.well-known/oauth-protected-resource")" >/dev/null

# No token: a 401 that tells Claude where to start the OAuth dance, never a tool result.
headers="$(curl -sS -o /dev/null -D - -X POST "http://127.0.0.1:$PORT/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  --data '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')"
grep -qE '^HTTP/[0-9.]+ 401' <<<"$headers"
grep -qi '^www-authenticate: Bearer .*resource_metadata=' <<<"$headers"

[ "$(docker inspect -f '{{.State.Health.Status}}' "$NAME" 2>/dev/null || echo starting)" != unhealthy ]

# The incident runbook's mechanics (admin.sh): the server holds the data directory, so admin
# commands run against a stopped one, and a rotated key is the only key the server then accepts.
docker stop "$NAME" >/dev/null && docker rm "$NAME" >/dev/null
container --rm "$IMAGE" admin revoke-all
head -c 32 /dev/urandom | base64 > "$WORK/secrets/next_master_key"
chmod 0400 "$WORK/secrets/next_master_key"
as_root chown 10001 /w/secrets/next_master_key
container --rm -v "$WORK/secrets/next_master_key:/run/secrets/next_master_key:ro" "$IMAGE" \
  admin rotate-key /run/secrets/next_master_key
as_root sh -c 'cp /w/secrets/next_master_key /w/secrets/master_key && chown 10001 /w/secrets/master_key && chmod 0400 /w/secrets/master_key'
serve
wait_healthy
curl -fsS "http://127.0.0.1:$PORT/healthz" >/dev/null
echo "smoke test passed"
