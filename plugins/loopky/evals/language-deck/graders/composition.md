---
type: llm
weight: 2
---

Grade the response's plan for a general English deck of about 3,000 cards for Brazilian Portuguese speakers. The user asked for a plan with nothing run yet, so no list has been downloaded and no card file has been built: grade what the plan says it will do, and the few sample lines it shows. That nothing has been run, measured or published never counts against it. It PASSES when all of these hold:

1. The plan says the deck's words will be chosen and ranked from frequency data (a frequency list, a subtitle or corpus count, a CEFR-graded word list), not from memory or the model's own judgement of what is common. The few sample lines may be written by hand, since no list has been fetched yet, and saying so does not fail this point.
2. The deck mixes single words with phrases or sentences, it gives a share for the phrases and sentences that is between 30% and 50% of the cards, and it says the phrases are spread through the deck (placed near the words they use, or the share checked per stretch of cards) rather than all gathered at the end.
3. Words like "the", "will" and "went" are taught inside a short phrase or sentence. A card the plan intends to publish whose Portuguese side is a grammar label such as "artigo definido", "auxiliar de futuro" or "passado de ir" fails this point, in the plan or in the sample lines. Quoting such a card as an example of what it will **not** write does not fail it.
4. The Portuguese is on the front and the English on the back, and the language pair is declared with `--front-lang pt-BR` and `--back-lang` set to an English tag such as `en-US` or `en-GB`.
5. The deck's title is written in Portuguese.
6. It says what the user will be shown in place of the full list: counts or shares of words and phrases, or a sample of the cards, or both. Offering only "the full file" or only the first few cards fails this point.

Extra correct steps do **not** count against the response: `loopky doctor`, `loopky login`, the dry run, verification with `deck show`/`card list`, an opening block of survival phrases, citing research, `--listen`/`--speak`/`--type`/`--reverse`, tags, or asking the user to confirm the size or the English variety. FAIL only when one of the six points is missing or contradicted.
