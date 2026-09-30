# Loopky for Claude Code and Codex — Anki-style flashcards, built by your agent

Build [Loopky](https://loopky.app) flashcard decks by asking Claude or Codex for them — from a topic, your
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

Codex reads the same plugin:

```shell
codex plugin marketplace add jvsena42/loopky
codex plugin add loopky@loopky
```

Then ask for what you want to learn: *"Make me 30 Spanish flashcards for ordering at a restaurant"*.

## What it contains

- **`skills/loopky`** — when to reach for Loopky and the workflow. It deliberately does not copy
  the command reference: `loopky commands --json` is the source of truth for flags, result shapes
  and exit codes, and the skill points there.
The plugin ships **no hooks**. The skill installs `loopky` when it is missing, from the release's
installer on `github.com`. A hook that did it at session start would download and run code before
the user asked for anything, which plugin directories hold for review. In a cloud environment
(Claude Code on the web, Codex), putting the install line in the environment's setup script saves
that step, with the hosts `loopky doctor` lists added to its allowlist:

```shell
curl -fsSL https://github.com/jvsena42/loopky/releases/latest/download/install.sh | sh
```

## What it cannot do for you

- **Add hosts to a sandbox's network allowlist.** In a cloud session, `loopky` needs the Pubky hosts
  `loopky doctor` lists. Only you can add them (environment settings → *Network access: Custom*);
  the skill tells Claude to stop and show you the list rather than retry.
- **Approve the login.** You scan the QR code (or open the link) with
  [Pubky Ring](https://pubkyring.app) on your phone.

## Keeping it in step with the binary

`cli/src/test/.../AgentPluginTest.kt` fails the build when the skill names a command or flag the
binary does not have, when its exit-code table disagrees with `ExitCode`, or when the plugin's
version drifts from `loopkyCliVersion`. CI also runs `claude plugin validate --strict`, installs the plugin into Codex and checks the
skill reaches the model's prompt (`codex debug prompt-input`). `evals/` holds behavioural cases for `claude plugin eval`: they cost API credit,
so CI runs them once per release (beside the build, gating nothing) and on demand; run them
locally with `claude plugin eval plugins/loopky --model sonnet --ablation none` before merging a
skill change.
