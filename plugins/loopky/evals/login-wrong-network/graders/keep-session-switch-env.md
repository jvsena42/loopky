---
type: llm
---

The response recovers from a login that landed on the other network without wasting the approval:

- It explains that the identity the user approved is on staging while the command ran for production, and that the session was already stored.
- It continues (or proposes continuing) with `--env staging` / `LOOPKY_ENV=staging` on the next commands, rather than asking the user to scan a new QR code as the only way forward.
- It may offer signing in again with a production identity as the alternative if the user meant production, but it does not retry `loopky login` unchanged under production as if the failure were transient.
