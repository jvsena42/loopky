---
max_turns: 6
allowed_tools: [Skill, Read]
tags: [feedback]
---

Yes, please draft that Loopky issue. For reference, here's what happened while you made my "Maria's ENEM Biology" deck: `loopky card add 7f3kq9 --front "Mitocôndria" --back "Produz ATP" --image-front /home/maria/fotos/cel.png` exited 1 with `{"error": {"code": "internal", "message": "cannot read /home/maria/fotos/cel.png for pubky://8xk3m1qzt9w7oa4hd5yzbr6ufjneiks7gxcp3tqm1o9ywa4xd8no/pub/loopky/decks/7f3kq9"}}`. You worked out that `--image-front` only takes an `https` URL and switched to Wikimedia. I'm on macOS 15 on an M2, `loopky --version` says 1.3.1. Use the Loopky skill.
