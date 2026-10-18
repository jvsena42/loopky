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
FROM --platform=linux/amd64 caddy:2.11.7-builder@sha256:d842facfd63b960922e57444ff76fd8f0793a66dfdcf481af8026e1f4e6a39b4 AS builder

RUN xcaddy build --with github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db

FROM scratch AS export
COPY --from=builder /usr/bin/caddy /caddy

FROM --platform=linux/amd64 caddy:2.11.7@sha256:685ae3d7c34d9b5f8caeddc5d14051db7bbd88ff1f5a2fa0c16bd8ca328fb7af AS runtime
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
