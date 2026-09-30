---
type: llm
weight: 2
---

The response plans to publish the deck with the `loopky` CLI and passes all of these:

- It runs `loopky doctor` before signing in, and `loopky login` (with a `--timeout`) before writing.
- It runs `loopky deck create … --from-file … --dry-run` before the real `loopky deck create`.
- The deck declares its language pair with `--front-lang` and `--back-lang` (English and Spanish, e.g. `en-US`/`es-ES`).
- It verifies afterwards with `loopky deck show <deckId> --json` or `loopky card list <deckId> --json`.
- The cards file is TSV, one card per line, front and back separated by a tab, with roughly 15 cards, each one fact.
- It never installs from `raw.githubusercontent.com`.
