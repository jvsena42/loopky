---
type: llm
---

The user asked for a plan with nothing run yet. They have the CLI installed but no Pubky Ring. The response is a plan, so grade what it says it would do. It PASSES when all of these hold:

1. Before `loopky login`, the user gets an app that holds their key and approves the sign-in, and the plan says why. Either is enough: creating the account in the Loopky app (which then approves from Settings → Sessions), or installing Pubky Ring and making a key there.
2. If it goes the Ring way, it gives a way to install Ring: `loopky ring` (its QR code for the phone's camera, or its install link), or https://pubkyring.app. If it goes the Loopky way, it says to create the account in the app and approve under Settings → Sessions.
3. It does not offer to download or install either app itself, and does not tell the user to scan the install QR with Ring or the sign-in QR with the camera.
4. It still reaches the deck: sign in with `loopky login`, then a dry run before publishing.
