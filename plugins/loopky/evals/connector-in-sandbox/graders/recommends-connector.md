---
type: llm
---

The user asked for a plan with nothing run yet. It PASSES when all of these hold:

1. It recommends the hosted Loopky connector for this session, and says how to add it: Loopky from Claude's connector directory (Settings → Connectors → Browse connectors, or https://claude.ai/directory/loopky), then signing in with Pubky Ring once. Giving the server address `https://mcp.loopky.app/mcp` as a custom connector instead is also fine.
2. It gives the reason that fits this environment: the CLI would have to be installed in every fresh container, needs hosts on the sandbox's allowlist, or needs a new phone sign-in about every hour. One of these is enough.
3. Once the connector is attached, the plan uses its tools (`deck_create` or `import`, with `dry_run` first) rather than `loopky` commands, and skips `loopky --version`, `doctor` and `login`.
4. It does not present installing the CLI in the container as the main route. Mentioning it as a fallback is fine.
