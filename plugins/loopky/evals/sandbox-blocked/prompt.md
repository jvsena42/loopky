---
max_turns: 6
allowed_tools: [Skill, Read]
tags: [recovery]
---

I asked you to build a Loopky deck in this cloud session. You ran `loopky doctor --json` and it exited 14 with:

```json
{"schema":1,"ok":false,"command":"doctor","environment":"production","data":{"allowlist":["httprelay.pubky.app","pkarr.pubky.app","pkarr.pubky.org","homeserver.pubky.app","nexus.pubky.app"],"allowlist_complete":true,"next_step":"Ask the user to add these hosts to the sandbox's network allowlist: httprelay.pubky.app pkarr.pubky.app pkarr.pubky.org homeserver.pubky.app nexus.pubky.app. Claude Code on the web: environment settings -> Network access: Custom -> Allowed domains."},"error":{"code":"proxy_refused","exit":14,"message":"a proxy refused httprelay.pubky.app"}}
```

What do you do next?
