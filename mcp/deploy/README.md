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

Caddy terminates TLS: start from `Caddyfile.example`. Open only 443 and 22 (`ufw allow 22,443/tcp`).

### The deploy key

Generate a key pair for CD (`ssh-keygen -t ed25519 -f mcp-deploy -N ''`) and add the public half to
`/home/deploy/.ssh/authorized_keys` as a **forced command**. The key CI holds can then do one thing,
which is to roll an environment to a digest of the one image:

```
command="/opt/loopky-mcp/deploy.sh",no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding ssh-ed25519 AAAA… mcp-deploy
```

### GitHub

In **Settings → Environments**, create `staging` and `production`:

| | staging | production |
|---|---|---|
| secret `VPS_HOST` | the box | the box |
| secret `VPS_USER` | `deploy` | `deploy` |
| secret `VPS_SSH_KEY` | the private half above | same, or a second key |
| secret `VPS_KNOWN_HOSTS` | `ssh-keyscan <host>` output, checked by hand | same |
| var `MCP_PUBLIC_URL` | `https://mcp.staging.loopky.app` | `https://mcp.loopky.app` |
| protection | — | **required reviewers**, branch `main` only |

The image is `ghcr.io/jvsena42/loopky-mcp`. Make the package public, or `docker login ghcr.io` once
as `deploy` with a read-only token.

## Day to day

- **Deploy**: merge to `main`. Staging follows automatically; approve the `production` job.
- **Roll back**: run *MCP deploy* from the Actions tab with the previous `digest`. `deploy.sh` also
  rolls back by itself when a new digest does not come up healthy.
- **What is running**: `cat /srv/loopky-mcp/<env>/image`.
- **Mass revocation** (a leaked database and key): `docker compose -p loopky-mcp-<env> exec mcp
  /opt/loopky-mcp/bin/loopky-mcp admin revoke-all`, then rotate the master key with
  `admin rotate-key`. Read the server's `--help` for the exact steps before you need them.
