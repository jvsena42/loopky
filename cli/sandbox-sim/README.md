# sandbox-sim

The networks agent sandboxes give `loopky`, reproduced in Docker, so a change to anything that
talks to the network can be checked against them before an agent finds out (#212).

```shell
./gradlew :cli:installDist
cli/sandbox-sim/run.sh claude-trusted          # Claude Code on the web, default "Trusted"
cli/sandbox-sim/run.sh claude-custom           # ...plus loopky's hosts ("Custom", defaults kept)
cli/sandbox-sim/run.sh codex-common            # Codex cloud, "Common dependencies" preset
cli/sandbox-sim/run.sh codex-custom            # ...plus loopky's hosts
cli/sandbox-sim/run.sh codex-custom-readonly   # ...with "GET, HEAD and OPTIONS only" on
cli/sandbox-sim/run.sh authenticated           # claude-custom, behind a proxy wanting credentials
cli/sandbox-sim/run.sh intercepting            # every connection re-signed, CA trusted system-wide
cli/sandbox-sim/run.sh offline                 # no egress: Codex's default, Claude's "None"
```

Each run prints one row per command — exit code, error code, time, and how many DHT bootstrap
errors reached stderr.

- `LOOPKY_SESSION=… run.sh …` adds the signed-in rows: `whoami`, `deck list`, and a create and
  delete of a fixed-id deck, so a run leaves nothing on the account. Nothing stores or prints it.
- `LOOPKY_DIST=<dir>` runs another build — a directory holding `bin/loopky`, which is how the native
  binary gets the same rows as the jar.
- `PUBKYCORE_DIR=<dir>` loads that directory's `libpubkycore.so` instead of the bundled one, to try
  an FFI change first. The jar only: the native binary embeds its library.
- `JVM_TRUSTS_PROXY_CA=1` also hands the jar's JVM a trust store holding the proxy's CA, as a Claude
  Code cloud session does through `JAVA_TOOL_OPTIONS`.

## What it models

The client container drops **every** outbound packet except to the proxy — dropped, not refused,
because a path that ignores the proxy should hang the way it does in a real sandbox rather than
fail fast and look fine. UDP goes with it, so the DHT is unreachable.

- **`claude-*`** is Squid holding Claude Code on the web's *Trusted* list verbatim
  (`allowlists/claude-trusted.txt`, with its source), answering a blocked CONNECT with `403` and
  `X-Deny-Reason: host_not_allowed` as #212 observed. `*.x` matches subdomains; a bare name, that
  host only — the docs' own rule.
- **`codex-*`** is mitmproxy holding Codex's *Common dependencies* preset verbatim
  (`allowlists/codex-common.txt`), each entry matching its subdomains, through `codex_policy.py`.
  It intercepts TLS on purpose: the preset's optional method restriction can only be enforced on
  HTTPS by decrypting it, so a Codex environment that offers it is taken to intercept. Its CA is
  installed in the client's system store, as a sandbox would.
- **`intercepting`** is mitmproxy with no allowlist — corporate proxies and inspecting gateways.
- **`offline`** is no proxy at all.

What is *not* modelled: Claude Code's separate GitHub proxy, which carries `git` and `gh` and
serves release assets only for repositories attached to the session. `github.com` here is an
ordinary allowlisted host.

**The proxies need plain egress from the machine running Docker.** Inside a Claude Code cloud
session that egress is itself TLS-intercepted, and every profile would silently become
`intercepting` — describing the host, not the change. `run.sh` checks that first, against the stock
CA store, and refuses to run if it fails — except for `offline`, which uses no proxy and runs
anywhere.

## What it found (2026-09-28)

Exit codes, jar build, bundled `libpubkycore`.

| | `import --dry-run` | `doctor` | `tag trending` | `login --timeout 5` |
| --- | --- | --- | --- | --- |
| claude-trusted | 0 | 14 | 14 | 5 |
| claude-custom | 0 | 0 | 0 | 13 |
| codex-common | 0 | 14 | 14 | 5 |
| codex-custom | 0 | 15 | 15 | 5 |
| codex-custom-readonly | 0 | 15 | 15 | 5 |
| authenticated | 0 | 0 | 0 | 13 |
| intercepting | 0 | 15 | 15 | 5 |
| offline | 0 | 5 | 5 (15s) | 13 (!) |
| codex-custom, `JVM_TRUSTS_PROXY_CA=1` | 0 | 15 | 0 | 5 |

- **The two defaults refuse Pubky; adding `doctor`'s hosts is the whole fix** where the proxy does
  not intercept. `login` 13 there is correct: it reached the relay and waited for a phone.
- **Anything intercepting fails**, allowlisted or not, because the pubky SDK trusts only its bundled
  roots (pubky/pubky-homeserver#648). The last row is why `doctor` compares the stacks: the JVM,
  given the CA, passes, while the SDK still cannot resolve anything.
- **`login` under a refusal reads 5**, not 14, until the bundled FFI reports its error chain
  (pubky-core-ffi-fork#9); with `PUBKYCORE_DIR` at that build it is 14. **`login` offline reads
  13** because the relay resume keeps retrying a relay it never reached.
- `doctor`'s probes time out at 5s each, so one slow host reads as unreachable — re-run it before
  believing a single 5.
