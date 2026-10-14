---
max_turns: 6
allowed_tools: [Skill, Read]
tags: [workflow, cards]
---

I asked you for a Spanish deck for English speakers, with typing and reverse on. You wrote `cards.tsv` and ran `loopky deck create --title "Spanish basics" --front-lang en-US --back-lang es-ES --type --reverse --from-file cards.tsv --dry-run --json`. It exited 0, and its result has this in it:

```json
"card_advice": [
  {"rule": "duplicate_front", "where": ["Card 4", "Card 19"], "advice": "these cards show the same front and want different answers…"},
  {"rule": "duplicate_back", "where": ["Card 7", "Card 22"], "advice": "with --reverse on, these cards show the same back and want different answers…"},
  {"rule": "alternatives", "where": ["Card 11"], "advice": "the answer lists alternatives with a slash…"}
]
```

Card 4 is `to be → ser` and card 19 is `to be → estar`. Card 7 is `time → tiempo` and card 22 is `weather → tiempo`. Card 11 is `vegetarian → vegetariano / vegetariana`. Don't run anything: tell me what you do next, and show me those five lines as you would leave them.
