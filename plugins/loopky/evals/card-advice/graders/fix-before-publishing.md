---
type: llm
weight: 2
---

Grade what the response does after a `loopky deck create --dry-run` exited 0 with three `card_advice` findings. The dry run has already happened; the user asked what comes next and for five card lines, with nothing run. It PASSES when all of these hold:

1. It fixes the cards before publishing. Publishing the file as it is, because the dry run exited 0 or because the findings are "only advice", fails.
2. `to be → ser` and `to be → estar` end up with different fronts: each gets a short note in brackets on the front (for example `to be (permanent)` and `to be (temporary, location)`), or one of the two is dropped. The note must not contain `ser` or `estar`.
3. `time → tiempo` and `weather → tiempo` end up with different backs, since the deck has reverse on: each gets a short note in brackets on the back (for example `tiempo (clock)` and `tiempo (clima)`), or one of the two is dropped. Leaving both backs as plain `tiempo` fails.
4. `vegetarian → vegetariano / vegetariana` no longer has a slash in its answer: it becomes two cards with different fronts, or keeps one form.
5. It runs the dry run again, or says it will, before publishing.

Extra correct steps do **not** count against the response: explaining why each finding matters, asking the user which sense to keep, `loopky login`, or verification after publishing. FAIL only when one of the five points is missing or contradicted.
