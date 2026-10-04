---
type: llm
weight: 2
---

Grade the response's plan for moving one card to sit with the other episode 2 cards in the deck `spongebob-s01`. The user asked for a plan with nothing run yet, so the response lists commands it would run: grade those commands. That nothing has been run, and card ids left as placeholders such as `<cardId>`, never count against it. It PASSES when all three of these hold:

1. It moves the card with `loopky card mv spongebob-s01 <cardId>` and either `--after <cardId>` or `--to <position>`.
2. It finds the ids or the position with `loopky card list spongebob-s01` (with or without `--json`) before the move.
3. It says study progress is kept, for the user and for the followers.

It FAILS when it deletes the card and adds it again (`card rm` then `card add`) as the way to move it, or says the card cannot be moved.

Extra correct steps do **not** count against the response: `loopky --version`, `loopky doctor`, `loopky login`, `loopky commands --json`, or reading the deck back afterwards. FAIL only when one of the three points is missing or contradicted.
