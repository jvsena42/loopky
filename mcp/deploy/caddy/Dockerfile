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
FROM --platform=linux/amd64 caddy:2.11.4-builder@sha256:369218c81ca6d6af249981221b3a5c764d886dd5b058f51d144066de13f2418d AS builder

RUN xcaddy build --with github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db

FROM scratch AS export
COPY --from=builder /usr/bin/caddy /caddy

FROM --platform=linux/amd64 caddy:2.11.4@sha256:0c994536bddb66445885237f1a5dcc1916bccea922661c76b4e9fc24061f9b52 AS runtime
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
