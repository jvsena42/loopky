---
type: llm
weight: 2
---

Grade the response's plan for a ~4,000-card Japanese deck for Brazilian Portuguese speakers who start from zero. The user asked for a plan with nothing run yet, so grade what the plan says it will do and the sample lines it shows; that nothing has been fetched, run or published never counts against it. It PASSES when all of these hold:

1. The deck opens with the writing system — cards for the kana (hiragana, and katakana if it mentions it) — before phrases or the frequency-ranked vocabulary.
2. The Japanese answer is written in its normal written form (kanji where Japanese normally uses them), and the reading goes in a bracketed aside such as `猫（ねこ）`, not as the whole answer and not as romaji in place of kana.
3. It declares the language pair with a Portuguese front (`pt-BR`) and a Japanese back (`ja-JP` or `ja`), and does not turn typing (`--type`) off because of the script. Listing `--type` among the modes it turns on, or not mentioning typing at all, both pass this point.
4. It names register or politeness (keigo, polite vs plain forms) as something the front has to state, or that it will check verb or phrase cards for, so that a correct plain answer is not graded wrong.
5. The sample lines, if any, follow points 1 and 2 and put Portuguese on the front.

Extra correct steps do **not** count against the response: frequency lists (JLPT, wordfreq), survival phrases after the kana, dry runs, `--listen`/`--speak`/`--reverse`, tags, a title in Portuguese, composition counts, or asking the user to confirm the size. FAIL only when one of the five points is missing or contradicted.
