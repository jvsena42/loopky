---
type: llm
weight: 2
---

Grade the response's plan for a 20-card English deck with typing on, where each card shows the IPA and the part of speech without either having to be typed. The user asked for a plan with nothing run yet, so grade the cards file and the command it shows; that nothing has been run or published never counts against it. It PASSES when all of these hold:

1. On every sample card, the IPA and the part of speech sit in a bracketed aside on the side that holds the English word — round brackets (`house (noun)`), or square brackets after a space (`house [haʊs]`) — so the word alone is what gets typed.
2. No square bracket is glued to the word (`house[haʊs]`), and neither note is written bare in the answer text (`house haʊs noun`).
3. The command turns typing on (`--type`).

Extra correct steps do **not** count against the response: a dry run first, a language pair, `--listen`/`--speak`, tags, a Portuguese front, or putting the notes on the Portuguese side instead as long as point 1 holds for the side that is typed. FAIL only when one of the three points is missing or contradicted.
