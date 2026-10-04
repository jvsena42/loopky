---
type: llm
weight: 2
---

Grade the response's plan for putting about 300 late cards with their episodes in the deck `spongebob-s01`. The user asked for a plan with nothing run yet, so the response lists commands it would run: grade those commands. That nothing has been run never counts against it. It PASSES when all four of these hold:

1. It reorders the whole deck with `loopky card reorder spongebob-s01 --from-file <file>`.
2. The file comes from `loopky card list spongebob-s01` (with or without `--json`), put into episode order.
3. It runs the reorder with `--dry-run` before the real one, or says it will check the result with `card list` afterwards.
4. It says study progress is kept, for the user and for the followers.

It FAILS when its way of moving the cards is hundreds of `card mv` commands, deleting and re-adding cards (`card rm` then `card add`), or deleting and rebuilding the deck.

Extra correct steps do **not** count against the response: `loopky --version`, `loopky doctor`, `loopky login`, `loopky commands --json`, saying to run the same command again if it fails partway, or asking the user to confirm the order. FAIL only when one of the four points is missing or contradicted.
