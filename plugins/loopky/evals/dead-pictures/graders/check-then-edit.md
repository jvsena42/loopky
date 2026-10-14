---
type: llm
weight: 2
---

Grade the response's plan for finding and fixing dead picture URLs in a published Loopky deck. The user asked for a plan with nothing run yet: grade what the plan says it will do, and never count it against the response that nothing has been run. Check each point on its own:

1. **Finds them with the CLI's own check.** It uses `loopky card check-images birds-eu` (with or without `--json`) to ask every stored picture URL. A hand-written script that requests each URL itself does not count.
2. **Fixes the cards it names.** The cards behind each bad URL (the result's `card_ids`) get a working picture through `loopky card edit`, one card or a `--from-file` batch, or have the picture removed. Deleting and re-creating the deck or the cards does not count.
3. **Unverified is not dead.** If the plan mentions URLs the check could not verify (rate limits, timeouts), it treats them as unknown and asks again later rather than replacing them. A plan that does not mention them passes this point.

Asking the user to confirm details, and extra correct steps (doctor, login, choosing replacement pictures and their licences, `--check-images` on the edit), do **not** count against the response. PASS when all three points hold; FAIL only when one of them is missing or contradicted, and name which.
