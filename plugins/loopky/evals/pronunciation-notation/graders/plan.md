---
type: llm
weight: 2
---

Grade the response's plan for a 15-card Korean deck for English speakers, with typing on, where the user asked to see pronunciation "the way a Korean dictionary writes it" without having to type it. The user asked for a plan with nothing run yet, so grade the cards file and the command it shows; that nothing has been run or published never counts against it. It PASSES when all of these hold:

1. The Korean answer is the standard Hangul spelling, not romanization.
2. The pronunciation is written in Hangul in a bracketed aside after the word, the Standard Korean Language Dictionary's convention (`같이 [가치]`, `읽다 [익따]`), and **only on the cards where pronunciation differs from spelling**: a card that restates its own spelling (`물 [물]`) fails this point. Round brackets also pass. Revised Romanization **in place of** the Hangul pronunciation fails this point; romanization added alongside it does not.
3. No pronunciation is written between slashes (`/가치/`) or bare in the answer text, and no bracket lists two forms separated by `/`.
4. It declares the pair with a Korean back (`ko-KR` or `ko`) and turns typing on (`--type`).

Extra correct steps do **not** count against the response: a dry run, `--listen`/`--speak`/`--reverse`, tags, an English front, or a note that most words need no pronunciation. FAIL only when one of the four points is missing or contradicted.
