---
type: llm
weight: 2
---

Grade the response's plan for a 12-card Russian deck for English speakers with typing on. The user did not ask for pronunciation. The user asked for a plan with nothing run yet, so grade the cards file and the command it shows; that nothing has been run or published never counts against it. It PASSES when all of these hold:

1. The cards do not carry IPA or a romanization/transliteration on them, since the user did not ask. Stress marks written on the Russian word itself (`молоко́`) are allowed, and so is a one-line offer to add pronunciation if the user wants it.
2. Pronunciation is not presented as required: the response does not tell the user a Russian deck needs pronunciation, and does not refuse or delay the deck over it.
3. It declares the pair with a Russian back (`ru-RU` or `ru`) and turns typing on (`--type`).

Extra correct steps do **not** count against the response: a dry run, `--listen`/`--speak`/`--reverse`, tags, an English front. FAIL only when one of the three points is missing or contradicted.
