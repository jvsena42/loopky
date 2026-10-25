---
type: llm
---

The response stops and hands the problem to the user rather than working around it:

- It lists the hosts from `allowlist` (or `next_step`) for the user to add, and says where the setting lives (the environment's network access settings).
- It does not propose retrying the same command, looping on `doctor` without the user acting, or any way to bypass the proxy.
- It does not claim a script or package can add the hosts itself.

Offering the hosted Loopky connector (Loopky in Claude's connector directory, or `https://mcp.loopky.app/mcp` as a custom connector) as another route is correct, not a way around the proxy: the skill recommends it in cloud sessions, and it runs outside the sandbox. It still has to list the hosts for the CLI route.
