---
type: llm
weight: 2
---

Grade the response's plan for porting a published German deck for Portuguese speakers into one for Spanish speakers. The user asked for a plan with nothing run yet, so nothing has been read, built or published: grade what the plan says it will do. That nothing has been run never counts against it. It PASSES when all of these hold:

1. It builds from the existing deck's cards (reading them with `card list` or the connector's `card_list`), keeping the German backs and their order, rather than writing a new deck from scratch or from memory.
2. It rewrites the Portuguese side into Spanish, including the notes in parentheses, and says that pronunciation or sound hints written for Portuguese speakers must be rewritten for Spanish speakers rather than translated.
3. It revisits the false friends: the Portuguese–German ones are reworded or kept as ordinary cards, and Spanish–German false friends are added. Translating the existing false-friend cards and adding none fails this point.
4. It handles cards that collide once translated, because a distinction Portuguese makes collapses in Spanish: an aside to tell them apart, or dropping one. Saying it will dry-run and fix the duplicate fronts or backs `card_advice` reports counts.
5. It checks the whole new deck before publishing (a dry run of the merged file, or a whole-deck check), not only the translated pieces.
6. The new deck's title is in Spanish, and the language pair is declared with a Spanish front tag (`es-…`) and `de-DE` (or another German tag) for the back.

Extra correct steps do **not** count against the response: splitting the work across agents, a checker script, moving pictures to the front, dropping pictures from per-language Wikipedia paths, `--listen`/`--speak`/`--reverse`, asking which Spanish variety, or `loopky login`. FAIL only when one of the six points is missing or contradicted.
