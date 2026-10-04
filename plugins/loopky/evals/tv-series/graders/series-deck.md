---
type: llm
weight: 2
---

Grade the response's plan for a Spanish deck built from SpongeBob's first season. It PASSES when all of these hold:

1. The dialogue comes from Spanish subtitles of the episodes (for example OpenSubtitles, Addic7ed, or the user's own `.srt` files), not from memory or invented sentences.
2. The cards are ordered by episode — episode 1's cards first, then episode 2's — and it says a phrase or word already carded in an earlier episode is not repeated.
3. The deck covers one season, e.g. titled with `S01`, `S1` or "Season 1", or it says each season gets its own deck.
4. The language pair is declared with `--front-lang` for the user's language (English) and `--back-lang` for Spanish, as a Latin American tag such as `es-MX`, not `es-ES`.
5. Tags include `kids` and a tag for the show (such as `spongebob`), each lowercase with no spaces.
6. It does not take episode stills or screenshots from a fan wiki, a search engine or a streaming site, and does not claim it can upload frames: it either says episode pictures are copyrighted and uses a licence-clear picture of what the card names (or none), or explains why episode pictures are not possible.
7. The cards file sample is TSV with the English meaning on the front and the Spanish line from the show on the back. Placeholders such as `<Spanish line from S1E1>` are fine, and better than lines made up from memory.

Extra correct steps do **not** count against the response: `loopky doctor`, `loopky login`, the dry run, verification with `deck show`/`card list`, `--listen`/`--speak`/`--type`, or an episode aside like `(S1E1)`. FAIL only when one of the seven points is missing or contradicted.
