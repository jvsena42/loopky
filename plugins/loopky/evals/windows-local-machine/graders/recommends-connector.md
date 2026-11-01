---
type: llm
---

The user asked for a plan with nothing run yet, on their own Windows machine, where there is no `loopky` build. It is grading a plan, so nothing has to have been run. It PASSES when all of these hold:

1. It recommends the hosted Loopky connector for this, and says how to add it: Loopky from Claude's connector directory (Settings → Connectors → Browse connectors, or https://claude.ai/directory/loopky), or `https://mcp.loopky.app/mcp` as a custom connector, then signing in with Pubky Ring.
2. It does not tell the user to download or install a Windows `loopky` binary, run a PowerShell installer, or install the CLI natively on Windows. Mentioning the Linux build inside WSL as an alternative is fine.
3. The deck plan dry-runs before publishing (`dry_run: true` or `--dry-run`) and verifies from what was stored afterwards.
