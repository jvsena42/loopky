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
  Code cloud session does through `JAVA_TOOL_OPTIONS`. Since #362 it changes nothing measurable: the
  client exports `SSL_CERT_FILE`, which the JVM half now trusts on its own.
- `SIM_SUMMARY=<file>` also writes the rows there as TSV — profile, command, exit, error code, ms —
  for anything that compares them rather than reads them.

## In CI

`check.sh` runs profiles through `run.sh` and fails on any exit code that differs from
[`expected.tsv`](expected.tsv), which is **the source of truth** for every profile's rows, for the
jar and the native binary.

```shell
cli/sandbox-sim/check.sh jar                                  # every profile in expected.tsv
LOOPKY_DIST=<dir holding bin/loopky> cli/sandbox-sim/check.sh native
cli/sandbox-sim/check.sh jar offline                          # just the profiles named
```

The `cli-sandbox-sim` job in `.github/workflows/ci.yml` runs both on every change under `cli/`, on
Linux only — the macOS and Windows native binaries stay unverified behind a proxy. A profile that
drifts is run a second time before it fails, because `doctor` reads one slow production host as
unreachable (#369). A change that moves an exit code on purpose changes `expected.tsv` in the same
PR.

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

## What it found (2026-09-29)

Exit codes for the jar and the Linux binary, bundled `libpubkycore` at pubky-core-ffi-fork@9e8dbec
(#384). Every row is in [`expected.tsv`](expected.tsv) now, so what follows is why the numbers are
what they are rather than the numbers.

- **The two defaults refuse Pubky; adding `doctor`'s hosts is the whole fix.** `claude-trusted` and
  `codex-common` read 14 everywhere but `import --dry-run`. `login` 13 on the `*-custom` profiles
  is correct: it reached the relay and waited for a phone.
- **An intercepting proxy works once its CA is trusted system-wide.** `run.sh` exports
  `SSL_CERT_FILE` as a sandbox would, the JVM half trusts it (#362) and so does `libpubkycore` —
  the SDK's ICANN client through pubky/pubky-homeserver#649, and the fork's own relay client
  beside it (#384). `intercepting` and `codex-custom` read what `claude-custom` does. Until #384
  they read 15 on `doctor` and `login`, because the SDK trusted only its bundled roots.
- **`codex-custom-readonly`'s `doctor` reads 14**: the unauthenticated `PUT` meets the method
  filter (`method_blocked`, #363). Reads work, so `tag trending` is 0.
- **`login` asks the relay before it shows a QR** (#360), with the same probe `doctor` uses, so a
  refused relay reads 14 and an unreachable one 5. Past that probe the FFI reports its error chain
  (pubky-core-ffi-fork#9), so a refused homeserver reads 14 rather than 5.
- No DHT errors reach stderr behind any proxy (pubky-core-ffi-fork#10). `offline` still shows a
  few, since with no proxy configured the DHT is tried as on an open network.
- `doctor`'s probes time out at 5s each, and a timed-out probe is asked once more before its host
  reads as unreachable (#369), so a 5 has already survived one retry.
- `offline`'s `tag trending` takes ~15s to read 5.
