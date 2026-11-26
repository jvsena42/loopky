# Caddy with the caddy-ratelimit module, the MCP server's edge (#518). Stock Caddy, the apt
# package included, has no rate limiting, so the VPS runs this build instead:
#
#   docker build --target export --output type=local,dest=out mcp/deploy/caddy   # -> out/caddy
#
# mcp/deploy/README.md has the swap. The `runtime` stage is what edge-test.sh boots in CI.
#
# Bases by digest, like mcp/Dockerfile: Dependabot moves both lines together, and xcaddy builds
# the builder image's own CADDY_VERSION, so the binary always matches the runtime's version. The
# module is pinned to a commit because its only tag predates the syntax the Caddyfile uses.
FROM --platform=linux/amd64 caddy:2.11.9-builder@sha256:cda07fe9d82728c7ac44d844306d435e98131423dc7b114f05a8a8ecfff94707 AS builder

RUN xcaddy build --with github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db

FROM scratch AS export
COPY --from=builder /usr/bin/caddy /caddy

FROM --platform=linux/amd64 caddy:2.11.9@sha256:4851e54a46b206517c574821bb5cc67f170d2156041e9fbc4c9193b45e84a75b AS runtime
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
