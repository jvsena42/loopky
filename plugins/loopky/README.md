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

When a session runs into a real problem with `loopky` or the skill (a misleading error, a missing
flag, a step that was wrong), the skill asks once, as the last thing it says, whether to report it
as an issue on this repository. It gathers nothing before you say yes, shows you the draft, keeps
your cards, deck ids, keys, paths and anything about you out of it, and files nothing unless you
approve that text: through your own signed-in `gh` or GitHub connector, or as a link you open
yourself.

The plugin ships **no hooks and no installer**: it downloads nothing and runs only the `loopky`
you installed, plus your own `gh` if you approve a friction report. The binary is ~70 MB per
platform, too large to ship inside a plugin, so it is a prerequisite — install it first,
following [Install](https://github.com/jvsena42/loopky/tree/main/cli#install) in the CLI README (Homebrew, a `.deb`, or the release installer). When it is missing, the skill
stops and asks you to.

In a cloud environment (Claude Code on the web, Codex), install `loopky` in the environment's
setup script, and add the hosts `loopky doctor` lists to the environment's allowlist.

## Data and services

The plugin declares no connectors. What leaves your machine goes through the `loopky` CLI the skill
runs, and only to the services below. The Loopky project runs no server of its own and receives
none of it, apart from an issue you choose to file. The full policy is at [loopky.app/privacy](https://loopky.app/privacy/), the terms at
[loopky.app/terms](https://loopky.app/terms/), and help at [loopky.app/support](https://loopky.app/support/).

| Service | When | What reaches it |
| --- | --- | --- |
| **Your Pubky homeserver** (`homeserver.pubky.app` by default) | Every deck read and write | The decks and cards you publish, your public key, your session, your IP address |
| **Pubky auth relay** (`httprelay.pubky.app`) | `loopky login` only | The encrypted approval from Pubky Ring, your IP address |
| **pkarr relays** (`pkarr.pubky.app`, `pkarr.pubky.org`) | Finding your homeserver | The public key being looked up, your IP address |
| **Pubky Nexus** (`nexus.pubky.app`) | `tag trending` and other indexer reads | What is looked up, your IP address |
| **GitHub** (`github.com`, `release-assets.githubusercontent.com`) | Installing, `loopky update`, and a once-a-day version check | A download request, your IP address |
| **GitHub issues** (`github.com/jvsena42/loopky`) | Only if you approve a friction report | The issue text you approved, posted publicly from your GitHub account |
| **Image hosts** named in your cards | Only with `--check-images` | One `HEAD` request per picture URL, your IP address |

**Retention.** Nothing is retained by the Loopky project. Your decks stay on your homeserver until
you delete them (`loopky deck delete`). **Published decks and tags are public**: there are no
private decks. The session secret is stored on your machine only (the macOS Keychain, or a
file only your user can read), and `loopky logout` removes it.

**Personal data.** The CLI reads no names, emails or addresses. Its session can write only Loopky's
own data (`/pub/loopky/`), so it can't read or change your profile. What you put on a card is
published as written, so don't put personal details in a deck.

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
