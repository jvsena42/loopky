---
type: llm
---

The user asked for a plan with nothing run yet. They have the CLI installed but no Pubky Ring. The response is a plan, so grade what it says it would do. It PASSES when all of these hold:

1. Getting Pubky Ring comes before `loopky login`, and the plan says why: Ring is what approves the sign-in.
2. It gives the user a way to install Ring: `loopky ring` (its QR code for the phone's camera, or its install link), or the link https://pubkyring.app. Either is enough.
3. It does not offer to download or install Ring itself, and does not tell the user to scan the install QR with Ring or the sign-in QR with the camera.
4. It still reaches the deck: sign in with `loopky login`, then a dry run before publishing.
