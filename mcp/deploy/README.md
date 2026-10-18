# Deploying the MCP server

`mcp-deploy.yml` builds one image per push to `main`, rolls **staging** to it, and rolls
**production** to the same digest once the `production` environment's reviewers approve. This
directory is the VPS half. Nothing here is secret, and no secret goes in this repository: the
master keys live only on the box, and the deploy key lives only in GitHub Environment secrets.

## One-time VPS setup

As root, on a Debian/Ubuntu box with Docker (with the compose plugin) and Caddy installed:

```sh
# The files CD runs.
install -d -m 0755 /opt/loopky-mcp
install -m 0644 compose.yml /opt/loopky-mcp/compose.yml
install -m 0755 deploy.sh   /opt/loopky-mcp/deploy.sh
install -m 0700 admin.sh    /opt/loopky-mcp/admin.sh

# A deploy user that can run docker and nothing else interactive.
useradd --create-home --shell /bin/bash deploy
usermod -aG docker deploy

for env in staging production; do
  # Data: owned by the container's uid (10001). Never backed up — losing it costs a re-link (#486).
  install -d -m 0700 -o 10001 -g 10001 /srv/loopky-mcp/$env/data
  install -d -m 0755 -o deploy /srv/loopky-mcp/$env

  # The master key: generated here, readable by the container uid alone, never copied off the box.
  install -d -m 0755 /etc/loopky-mcp/$env
  head -c 32 /dev/urandom | base64 > /etc/loopky-mcp/$env/master_key
  chown 10001:10001 /etc/loopky-mcp/$env/master_key
  chmod 0400 /etc/loopky-mcp/$env/master_key
done
echo 'LOOPKY_MCP_PUBLIC_URL=https://mcp.staging.loopky.app' > /etc/loopky-mcp/staging/env
echo 'LOOPKY_MCP_PUBLIC_URL=https://mcp.loopky.app'         > /etc/loopky-mcp/production/env
```

Open 22, 80 and 443 (`ufw allow 22/tcp && ufw allow 80,443/tcp`); 80 only redirects, and lets
Caddy answer an HTTP-01 challenge.

### The edge (Caddy)

Caddy terminates TLS and is the flood guard (#518): body caps, header and body read timeouts,
and a per-request rate. `Caddyfile.example` needs the `rate_limit` module, which stock Caddy and
the apt package lack, so install Caddy from its apt repository (for the systemd unit) and swap in
the build from `caddy/Dockerfile` the way Caddy's docs describe for a custom binary — the apt
package stays installed, its binary diverted, so an `apt upgrade` cannot put the module-less one
back:

```sh
# On the box, with mcp/deploy/caddy/Dockerfile copied to ./caddy/
docker build --target export --output type=local,dest=caddy/out caddy
dpkg-divert --divert /usr/bin/caddy.default --rename /usr/bin/caddy
install -m 0755 caddy/out/caddy /usr/bin/caddy.custom
update-alternatives --install /usr/bin/caddy caddy /usr/bin/caddy.default 10
update-alternatives --install /usr/bin/caddy caddy /usr/bin/caddy.custom 50
caddy list-modules | grep rate_limit                 # http.handlers.rate_limit
install -m 0644 Caddyfile.example /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile && systemctl restart caddy
```

A Dependabot bump of `caddy/Dockerfile` reaches CI only: rebuild and re-`install` the binary on
the box the same way (the `dpkg-divert` and `update-alternatives` lines are once only).

What it limits, and why it keys the way it does:

| | |
|---|---|
| Body | 2 MiB on `/mcp`, 16 KiB elsewhere — the server's own ceilings, refused before the JVM reads them |
| Timeouts | 10s to send headers, 30s for a body, 2 min idle keep-alive; no write or upstream timeout, which would cut an idle MCP stream |
| Per address | 50 requests/10s (IPv6 per /64), outside Anthropic's outbound range |
| Anthropic's range | 100 requests/s per address, all callers together, anonymous or not |
| Per bearer | 50 requests/5s per `Authorization` value, inside that range |

Claude's connector calls all come from Anthropic's published outbound range (`160.79.104.0/21`,
[IP addresses](https://platform.claude.com/docs/en/api/ip-addresses)), so an address there is
every Loopky user at once: a per-address limit sized for one caller would let one user throttle
the rest. The range gets a ceiling sized for all of them instead, which also bounds anonymous
calls and made-up bearers from Anthropic's infrastructure (any API customer can point a connector
here), and a per-bearer budget under it. If Anthropic changes the range, update the default in
`Caddyfile.example`. `edge-test.sh` checks every row in CI against the image, including that two
bearers do not share a budget. The module is pinned by commit, which no Dependabot ecosystem
tracks; see `.github/dependabot.yml`.

### The deploy keys

**One key per environment, with the environment fixed in its forced command.** `deploy.sh` takes
the environment from that command and only the digest from the caller, so a key can roll its own
environment and nothing else. Sharing one key, or leaving the environment to the caller, would let
any workflow that can read staging's secret roll production past its reviewers.

```sh
ssh-keygen -t ed25519 -f mcp-deploy-staging    -N ''
ssh-keygen -t ed25519 -f mcp-deploy-production -N ''
```

Then in `/home/deploy/.ssh/authorized_keys`:

```
command="/opt/loopky-mcp/deploy.sh staging",no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding ssh-ed25519 AAAA… mcp-deploy-staging
command="/opt/loopky-mcp/deploy.sh production",no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding ssh-ed25519 AAAA… mcp-deploy-production
```

### GitHub

In **Settings → Environments**, create `staging` and `production`:

| | staging | production |
|---|---|---|
| secret `VPS_HOST` | the box | the box |
| secret `VPS_USER` | `deploy` | `deploy` |
| secret `VPS_SSH_KEY` | `mcp-deploy-staging` | `mcp-deploy-production` — never the same key |
| secret `VPS_KNOWN_HOSTS` | `ssh-keyscan <host>` output, checked by hand | same |
| var `MCP_PUBLIC_URL` | `https://mcp.staging.loopky.app` | `https://mcp.loopky.app` |
| protection | branch `main` only | **required reviewers**, branch `main` only |

The image is `ghcr.io/jvsena42/loopky-mcp`. Make the package public, or `docker login ghcr.io` once
as `deploy` with a read-only token.

## Day to day

- **Deploy**: merge to `main`. Staging follows automatically; approve the `production` job.
- **Roll back**: run *MCP deploy* from the Actions tab with the previous `digest`. `deploy.sh` also
  rolls back by itself when a new digest does not come up healthy.
- **What is running**: `cat /srv/loopky-mcp/<env>/image`.
- **Mass revocation** (a leaked database or master key): as root, `/opt/loopky-mcp/admin.sh <env>
  revoke-all`, then `/opt/loopky-mcp/admin.sh <env> rotate-key`. Each stops the service (the
  server holds the data directory, so no admin command can run beside it), runs the command in a
  one-off container from the image that was running, and starts the service again. `rotate-key`
  generates the new key on the box, re-wraps every credential under it and swaps the key file.
  CI's smoke test runs these same steps against a stopped container on every PR; rehearse them
  on staging once before launch.
