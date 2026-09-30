# Loopky for Claude Code — Anki-style flashcards, built by Claude

Build [Loopky](https://loopky.app) flashcard decks by asking Claude for them — from a topic, your
notes, or an existing Anki `.apkg`. Loopky is a free spaced-repetition app, an Anki alternative for
Android and iOS with listening and speaking practice built in. The plugin teaches
Claude the `loopky` CLI workflow — install, check the network, sign in with Pubky Ring, dry-run,
publish, verify — and how to write cards that work with Loopky's typing, Listen and Speak modes.
You study the result in the Loopky app on Android or iOS.

## Install

```text
/plugin marketplace add jvsena42/loopky
/plugin install loopky@loopky
```

Then ask for what you want to learn: *"Make me 30 Spanish flashcards for ordering at a restaurant"*.

## What it contains

- **`skills/loopky`** — when to reach for Loopky and the workflow. It deliberately does not copy
  the command reference: `loopky commands --json` is the source of truth for flags, result shapes
  and exit codes, and the skill points there.
- **A SessionStart hook** that installs `loopky` in Claude Code on the web containers
  (`CLAUDE_CODE_REMOTE=true`) and does nothing anywhere else. It uses the release's installer from
  `github.com`, which the web's default *Trusted* network level allows.

## What it cannot do for you

- **Add hosts to a sandbox's network allowlist.** In a cloud session, `loopky` needs the Pubky hosts
  `loopky doctor` lists. Only you can add them (environment settings → *Network access: Custom*);
  the skill tells Claude to stop and show you the list rather than retry.
- **Approve the login.** You scan the QR code (or open the link) with
  [Pubky Ring](https://pubkyring.app) on your phone.

## Keeping it in step with the binary

`cli/src/test/.../AgentPluginTest.kt` fails the build when the skill names a command or flag the
binary does not have, when its exit-code table disagrees with `ExitCode`, or when the plugin's
version drifts from `loopkyCliVersion`. CI also runs `claude plugin validate --strict` and the
install hook in a simulated cloud container, and `evals/` holds behavioural cases run with
`claude plugin eval`.
