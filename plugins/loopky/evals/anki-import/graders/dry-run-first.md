---
type: llm
weight: 2
---

Grade the response's plan for importing `japanese-core-2000.apkg` into Loopky. It PASSES when all four of these hold:

1. It runs `loopky import japanese-core-2000.apkg --dry-run` (with or without `--json`) **before** any import without `--dry-run`.
2. It says it will check, in the dry-run result, which Anki fields became the front and back. Mentioning `--front-field` / `--back-field` as the fix is enough, and so is describing the check in words.
3. It says it will check the image size or quota before publishing (for example `images.bytes`, the upload size, or the 1 GB limit).
4. The publishing import comes after the dry run and passes `--title`.

Extra correct steps do **not** count against the response: checking `loopky --version`, asking the user to install the CLI, `loopky doctor`, `loopky login`, `--help`, verifying with `deck show` or `card list`, notes about language pairs, public decks or tags. Wording and ordering of the explanation don't matter either. FAIL only when one of the four points above is missing or contradicted.
