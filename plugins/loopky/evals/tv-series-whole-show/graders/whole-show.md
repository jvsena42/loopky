---
type: llm
weight: 2
---

Grade the response's plan for Brazilian Portuguese decks covering every season of SpongeBob. Check each point on its own; any one of the approaches listed under a point is enough for it.

1. **Count.** It gives a per-episode card count (a number or a range) and says the cards are a selection, not every line of the episode.
2. **Dialogue.** The cards come from Portuguese subtitles or transcripts of the episodes (OpenSubtitles, the show's fan wiki, the user's own files, or similar), not from memory or invented sentences.
3. **Mechanical check.** A script or other automated check confirms each back appears in that episode's dialogue before anything is published. A promise to "be careful" or a manual read alone does not count.
4. **No repeats across seasons.** Any of: de-duplicating across seasons in season order, filtering out what earlier seasons' decks already hold, or a checker that rejects cards already published.
5. **Deck names.** One deck per season, titled so they sort in season order (such as `S01 · SpongeBob · Portuguese`).
6. **Language.** The learned side is Brazilian Portuguese, `pt-BR`, in any form (`--back-lang pt-BR`, `en-US → pt-BR`).

Asking the user to confirm details, and extra correct steps (doctor, login, dry runs, tags, settings, pictures), do **not** count against the response. PASS when all six points hold; FAIL only when one of them is missing or contradicted, and name which.
