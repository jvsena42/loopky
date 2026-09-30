---
type: llm
weight: 2
---

The response plans to import the file with `loopky import japanese-core-2000.apkg` and:

- Runs `loopky import … --dry-run --json` first, and says it will check which fields became the front and back (overridable with `--front-field` / `--back-field`) and how many bytes of images will be uploaded against the quota.
- Publishes with a `--title` only after that check.
- Does not copy the Anki deck's own tags or description onto the published deck without reviewing them.
