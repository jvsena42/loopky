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
FROM --platform=linux/amd64 caddy:2.11.7-builder@sha256:c4cd20eaa0b69112fd5d4f721ffc9671687e1d5d0b155c8620cf7546dbef94c0 AS builder

RUN xcaddy build --with github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db

FROM scratch AS export
COPY --from=builder /usr/bin/caddy /caddy

FROM --platform=linux/amd64 caddy:2.11.7@sha256:36c24ad91a7ff7bc4c87fe22bfa8df3c4b18da0773d73fc12ee2c52b98c0bb7e AS runtime
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
