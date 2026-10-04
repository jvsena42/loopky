---
type: llm
weight: 2
---

Grade the response's plan to publish the deck with the `loopky` CLI. The user asked for a plan with nothing run yet, so the response lists commands it would run: grade those commands and their order. That nothing has been run never counts against it. It PASSES when all of these hold:

- The plan has `loopky doctor` before signing in, and `loopky login` (with a `--timeout`) before writing.
- The plan has `loopky deck create … --from-file … --dry-run` before the real `loopky deck create`.
- The deck declares its language pair with `--front-lang` and `--back-lang` (English and Spanish, e.g. `en-US`/`es-ES`).
- It plans to verify afterwards with `loopky deck show <deckId> --json` or `loopky card list <deckId> --json`.
- The cards file is TSV, one card per line, front and back separated by a tab, with roughly 15 cards, each one fact.
- It does not download or install `loopky` itself; if the CLI is missing, it asks the user to install it.
