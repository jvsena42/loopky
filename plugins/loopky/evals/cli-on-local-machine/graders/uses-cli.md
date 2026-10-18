---
type: llm
---

The user asked for a plan with nothing run yet, on their own machine. It PASSES when all of these hold:

1. The plan uses the `loopky` CLI: it checks `loopky --version` first (and asks the user to install it from the CLI's install instructions if it is missing), then signs in with `loopky login`.
2. It dry-runs the deck (`--dry-run`) before publishing, and verifies from what was stored (`deck show` or `card list`).
3. It does not steer the user to the hosted connector instead of the CLI. Mentioning the connector as an alternative for someone who would rather not install anything is fine.
