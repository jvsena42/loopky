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
FROM --platform=linux/amd64 caddy:2.11.8-builder@sha256:d4eb070c36154bd2d4c94b40359a03c6168346d5af5404963c74691ab77fb63f AS builder

RUN xcaddy build --with github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db

FROM scratch AS export
COPY --from=builder /usr/bin/caddy /caddy

FROM --platform=linux/amd64 caddy:2.11.8@sha256:4fcafa98afd1c2e642e353e3c5cf9ddbe892fcee14bc990e7ba2c5b78e51f9cf AS runtime
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
