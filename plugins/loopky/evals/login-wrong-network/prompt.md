---
max_turns: 6
allowed_tools: [Skill, Read]
tags: [recovery]
---

I scanned the Loopky login QR with Pubky Ring and approved it. `loopky login --json --timeout 300` then exited 8 with:

```json
{"schema":1,"ok":false,"command":"login","environment":"production","error":{"code":"environment_mismatch","exit":8,"message":"Ring approved an identity on staging (homeserver ufibwbmed6jeq9k4p583go95wofakh9fwpp4k734trq79pd9u1uy), but this login ran for production. The session is stored, so no new scan is needed: run the next commands with --env staging (or LOOPKY_ENV=staging). To use production instead, run `loopky login` again and approve with an identity on that network."}}
```

What now?
