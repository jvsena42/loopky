# sandbox-sim

The network an agent sandbox gives `loopky`, reproduced in Docker, so a change to anything that
talks to the network can be checked against it before an agent finds out (#212).

```shell
./gradlew :cli:installDist
cli/sandbox-sim/run.sh sandbox-default    # allowlist proxy, package managers only
cli/sandbox-sim/run.sh sandbox-loopky     # the same, with `loopky doctor`'s hosts added
cli/sandbox-sim/run.sh authenticated      # sandbox-loopky, behind a proxy that wants credentials
cli/sandbox-sim/run.sh intercepting       # TLS-intercepting proxy, its CA trusted system-wide
cli/sandbox-sim/run.sh offline            # no egress at all
```

`LOOPKY_DIST` points the client at another build — a directory with `bin/loopky` in it, which is
how the native binary gets the same rows as the jar. Each run prints one row per command — exit code, error code, time, and how many DHT bootstrap
errors reached stderr. `LOOPKY_SESSION=… run.sh …` adds the signed-in rows; `PUBKYCORE_DIR=<dir>`
loads that directory's `libpubkycore.so` instead of the bundled one, to try an FFI change first —
the jar only: the native binary embeds its library and ignores it.

## What it models

The client container drops **every** outbound packet except to the proxy — dropped, not refused,
because a path that ignores the proxy should hang the way it does in a real sandbox rather than
fail fast and look fine. UDP goes with it, so the DHT is unreachable.

- **Allowlist** is Squid answering a blocked CONNECT with `403` and `X-Deny-Reason:
  host_not_allowed`, which is what the Claude code-execution sandbox was observed doing in #212.
  `allowlist.sandbox-default.txt` is a package-manager list of the kind such sandboxes start
  with; the exact list differs per product.
- **Intercepting** is mitmproxy re-signing every TLS connection with its own CA, installed in the
  client's system store and pointed at by `SSL_CERT_FILE` — the shape of corporate proxies and of
  sandboxes that inspect traffic. Whether a given product intercepts is its own business; this
  profile answers what happens to `loopky` if it does.
- **Offline** is no proxy at all.

## What it found (2026-09-28)

Exit codes, jar build, with the FFI from pubky-core-ffi-fork's error-chain and relay-only changes
(`PUBKYCORE_DIR`). The bundled `libpubkycore` differs only where marked.

| | sandbox-default | sandbox-loopky | authenticated | intercepting | offline |
| --- | --- | --- | --- | --- | --- |
| `import --dry-run` | 0 | 0 | 0 | 0 | 0 |
| `doctor` | 14 | 0 | 0 | 5 | 5 |
| `tag trending` | 14 | 0 | 0 | 1 (TLS) | 5 (15s) |
| `login --timeout 5` | 14 (bundled: 5) | 13 | 13 | 13 | 13 (!) |
| DHT errors on stderr | 0 (bundled: 1 per 2s) | 0 | 0 | 0 | 2–3 |

The native binary (`LOOPKY_DIST` at a directory holding `bin/loopky`) gives the same codes with its
bundled FFI, credentials included.

`intercepting` fails on both stacks because neither trusts the proxy's CA; `offline` login reports
13 because the relay resume keeps retrying a relay it never reached. Both are written up in
Architecture.md §13.17. `doctor`'s probes time out at 5s each, so a slow host reads as
unreachable — run it again before believing a single 5.
