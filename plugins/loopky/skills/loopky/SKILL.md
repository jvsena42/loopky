---
name: loopky
description: Build and manage Loopky flashcard decks with the `loopky` CLI - make Anki-style spaced-repetition cards from any topic, import an Anki .apkg or a TSV, add, edit or illustrate cards, publish a language deck with Listen/Speak. Use whenever the user asks for flashcards, Anki cards, a study, vocabulary or exam deck, spaced repetition, or mentions Loopky, even if they do not name the CLI.
---

# Loopky flashcards through the `loopky` CLI

Loopky is a free spaced-repetition flashcards app (Android, iOS). `loopky` is its headless client:
it writes decks to the user's own account, and they study them on their phone. It manages decks and
cards; it does not study.

**If the user needs a file for Anki itself** (an `.apkg` to open in Anki), Loopky is not that:
say so, and offer Loopky as the option rather than substituting it. Loopky *imports* Anki decks;
it does not export them.

**The binary is the reference, this file is the workflow.** Flags, operands, result shapes and exit
codes come from `loopky commands --json` and `loopky <command> --help`. Read them rather than
guessing, and trust them over anything here.

## Workflow

1. **Install, if `loopky --version` fails,** with the installer shipped in this skill's `scripts/`
   folder (paths are relative to the skill's directory):

   ```shell
   sh scripts/install.sh
   ```

   Windows: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install.ps1`.
   It downloads the latest release binary, checks its published SHA-256, and puts it in
   `~/.local/bin` (Windows: `%LOCALAPPDATA%\Programs\loopky`); add that to `PATH` if the shell
   cannot find it, then run `loopky --version`. Never pipe a downloaded script into a shell, and
   never install from `raw.githubusercontent.com`.

2. **Read the surface:** `loopky commands --json`. No session, no network.

3. **Check the network:** `loopky doctor --json`. On exit 14 or 15, **stop**: the sandbox's
   allowlist blocks hosts Loopky needs, and only the user can change it. Show them `data.next_step`
   and `data.allowlist` verbatim — the hosts, and where their product keeps that setting. No
   package, script or retry can add a host for them.

4. **Sign in:** `loopky login --json --timeout 300`. The user approves with Pubky Ring on their
   phone. Show them the QR code **and** the `auth_url` from the output in a code block — on the
   device they chat from, they may open the link instead of scanning. The link is a login secret
   until approved: show it only to them, nowhere else. Exit 13 means nobody approved in time; run
   `login` again for a fresh code. A session lasts about an hour.

5. **Write the cards to a file** (TSV: `front<TAB>back`, optionally two more columns of `https`
   image URLs, one per side; JSONL when a side holds a tab or a newline). Show the user the list
   before publishing anything.

6. **Dry-run every write first:**

   ```shell
   loopky deck create --title "<Title>" --from-file cards.tsv --dry-run --json
   ```

   `--dry-run` exists on `deck create`, `card add` and `import`, and runs that command's own
   parser. Read `image_advice` in the result and fix what it names.

7. **Publish:**

   ```shell
   loopky deck create --title "<Title>" --tag <topic> --from-file cards.tsv --json
   ```

   Add `--id <word> --if-not-exists` when a retry must not publish a second deck.

8. **Verify from what was stored, not what was sent:** `loopky deck show <deckId> --json` and
   `loopky card list <deckId> --json`. Results sit under `data`; a card's `front` is an object with
   `.text` and `.image`, not a string. Tell the user the deck is on their phone under the title.

**Several commands in a row belong in `loopky batch`.** Each invocation pays ~2s of start-up and a
session round trip; a batch pays once. One JSON line per operation:
`{"argv": ["card", "add", "<deckId>", "--front", "…", "--back", "…"]}`, run with
`loopky batch ops.ndjson --json`. It is not transactional: on failure, fix the cause and re-run the
same file — `card add`, `card edit --from-file` and `deck create --id --if-not-exists` skip what
already landed.

**Already have an Anki deck?** `loopky import deck.apkg --dry-run --json` first — check which
fields became front and back (`--front-field`/`--back-field` override it) and `images.bytes`, since
an `.apkg` is the one import that uploads pictures against a 1 GB quota. Then
`loopky import deck.apkg --title "<Title>"`.

## Exit codes

Branch on the exit code (or `error.code` in the JSON) before reading the message.

| Exit | Name | What to do |
| --- | --- | --- |
| 0 | ok | Carry on. |
| 1 | internal | Report it as a bug; do not loop on it. |
| 2 | usage | Fix the command line with `loopky <command> --help`. Never retry unchanged. |
| 3 | not_signed_in | Run `loopky login` with the user. |
| 4 | session_expired | Stop and ask the user to approve a new `loopky login`, then re-run what failed. Writes die after about an hour while reads still work. |
| 5 | network | Retry as-is, a few times with a pause. |
| 6 | not_found | Check the id with `loopky deck list --json`. |
| 7 | storage_full | Terminal: the account is out of quota. Never retry; tell the user. |
| 8 | environment_mismatch | The session belongs to the other network; re-run with the matching `--env`. |
| 9 | bad_input | Fix the file or operand the message names. Never retry unchanged. |
| 10 | unsupported_host | No build for this OS/architecture. Nothing to retry. |
| 11 | update_unsupported | `loopky update` refused and **nothing was updated**; run the command its message names (brew, dpkg…). |
| 12 | server_error | Homeserver 5xx; the write may have landed. Retry — the idempotent commands skip what did. |
| 13 | timeout | Nobody approved the login in time; run `loopky login` again. |
| 14 | proxy_refused | Allowlist problem. Back to `loopky doctor` and hand the user its host list. Retrying will not help. |
| 15 | tls_untrusted | A proxy is re-signing TLS. Back to `loopky doctor`; the user must exempt the hosts or name the CA with `SSL_CERT_FILE`. |
| 16 | homeserver_unresolved | Transient name lookup failure; retry. Not an allowlist problem. |

## Writing good cards

- **One fact per card.** A back that needs "and" is two cards, and so is one with alternatives
  (`vegetariano / vegetariana`): split it, or keep one form. Short fronts, short backs.
- **Fix a card before showing the list, never annotate it.** If you notice a card breaks a rule
  here, rewrite it; the user reviews cards, not caveats about them.
- **Parenthesized asides are notes, not answers.** `hola (informal)`, `猫（ねこ）`: the card shows
  the note, but typing and Speak grade only `hola`/`猫`. Unbracketed, the note becomes part of the
  expected answer.
- **Language decks declare their pair.** `--front-lang en-US --back-lang es-ES` (BCP-47) is what
  makes `--listen` and `--speak` work, and it also tags the deck `language` and `spanish` so learners
  find it. Without the pair the phone reads Spanish in an English voice, so both switches stay off.
  `--type` (typed answers) and `--reverse` (ask both directions) need no pair.
- **Pictures are `https` URLs, never uploads.** Wikimedia thumbnails exist only at 120, 250, 330,
  500, 960, 1280 and 1920 px — any other `NNNpx-` width is a blank card. SVG, TIFF, WebM and STL do
  not render; for an SVG use its `/thumb/…/500px-….svg.png` render. Add `--check-images` to the
  dry-run when the URLs came from anywhere you have not fetched.
- **Tags are public** and indexed network-wide. Use a few honest topic words; never copy an Anki
  deck's tags or description without reading them.
- **Do not pad.** Make the cards the user asked for, at the count they asked for.

## Limits worth knowing

- The session can write only Loopky's own data (`/pub/loopky/`). It cannot post, follow or edit a
  profile, and widening that is not an option.
- Published decks are public. There are no private decks.
- A headless box with nobody at a phone can use `LOOPKY_SESSION`, minted with
  `loopky login --export` on a machine where the user can approve. It is a bearer token: never echo
  it, log it or commit it.
