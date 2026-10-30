---
type: llm
---

The user asked for a plan with nothing run yet. It PASSES when all of these hold:

1. It uses the connector's tools (`deck_create` or `import`, then `card_add` calls that append), with a `dry_run` before writing, and does not plan to install or run the `loopky` CLI.
2. It splits the 4,500 cards across several calls well under the 2,000-card limit — a few hundred cards a call (anything from about 100 to 500 passes) — rather than two or three calls near the limit.
3. It verifies by reading every stored card back (`card_list`, all pages) and comparing each card's front, back and picture against the local file, rather than spot-checking a sample or relying on the card count alone.
4. It says what it would do with a mismatch it finds: fix those cards with `card_edit` (or remove and re-add them).

Extra correct steps do **not** count against the response: one call at a time, waiting for each call to return, re-reading after a timeout, `deck_show`, a declared language pair, keeping edits small. FAIL only when one of the four points is missing or contradicted.
