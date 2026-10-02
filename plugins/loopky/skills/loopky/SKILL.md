---
name: loopky
description: Build and manage Loopky flashcard decks with the `loopky` CLI - make Anki-style spaced-repetition cards from any topic, import an Anki .apkg or a TSV, add, edit or illustrate cards, publish a language deck with Listen/Speak, or learn a language from a TV series' episodes. Use whenever the user asks for flashcards, Anki cards, a study, vocabulary or exam deck, spaced repetition, or mentions Loopky, even if they do not name the CLI.
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

1. **Check the CLI is there:** `loopky --version`. If it fails, **stop** and ask the user to
   install `loopky`, pointing them at <https://github.com/jvsena42/loopky/tree/main/cli#install>
   (Homebrew, a `.deb`, or the release installer for Linux, macOS and Windows). Do not download or
   install it yourself — this plugin runs only code that was reviewed with it. In a cloud session,
   suggest installing it in the environment's setup script so the next session has it. Once they
   have, run `loopky --version` again; if the shell cannot find it, it is usually in
   `~/.local/bin` (Windows: `%LOCALAPPDATA%\Programs\loopky`), which has to be on `PATH`.

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

9. **If the CLI or this skill got in the way, ask whether to report it**, once, at the end, as
   the last section describes. Nothing is gathered before a yes, and nothing is sent until the
   user approves the exact text.

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
- **Pictures are `https` URLs, never uploads.** SVG, TIFF, WebM and STL do not render on either
  phone, whatever the host: use a JPEG, PNG or WebP. Add `--check-images` to the dry-run when the
  URLs came from anywhere you have not fetched. Which source to take them from is the next section.
- **Tags are public** and indexed network-wide. Use a few honest topic words; never copy an Anki
  deck's tags or description without reading them.
- **Do not pad.** Make the cards the user asked for, at the count they asked for.

## Where to find pictures

A picture is a prompt, so it goes on the side that asks: the thing on the front, its name on the
back. Every deck is public and a card has no room for a credit line, so the licence decides the
source as much as the topic does.

**Try the sources in this order, and move to the next when one has no picture that shows exactly
the card's fact, its host is blocked (`loopky doctor` lists them under `recommended`), or the
picture's licence is not one of those below.** When none has one, leave the card without a
picture: a near miss teaches the wrong thing.

1. **A source made for the topic**, when there is one. All public domain, nothing to credit.

   | Topic | Find it | The URL that goes on the card |
   | --- | --- | --- |
   | Country flags | the ISO 3166 code, no lookup | `https://flagcdn.com/w640/<code>.png`. Widths double from `w20` to `w2560`; anything else 404s. |
   | Space, astronomy, NASA missions | `images-api.nasa.gov/search?q=…&media_type=image` | `https://images-assets.nasa.gov/image/<nasa_id>/<nasa_id>~medium.jpg`, or another size `images-api.nasa.gov/asset/<nasa_id>` lists. Only items credited to NASA: a `photographer` or `secondary_creator` from outside NASA, or a © in the description, is someone else's copyright. |
   | Paintings, artefacts | `api.artic.edu/api/v1/artworks/search?q=…&fields=id,title,image_id,is_public_domain` | `https://www.artic.edu/iiif/2/<image_id>/full/843,/0/default.jpg`, only where `is_public_domain` is true. |
   | Paintings, artefacts | `collectionapi.metmuseum.org/public/collection/v1/search?hasImages=true&q=…`, then `…/objects/<id>` | The object's `primaryImageSmall`, only where `isPublicDomain` is true. |

2. **Wikimedia Commons**, for anything with a name. Read the licence before using a file:
   `commons.wikimedia.org/w/api.php?action=query&format=json&titles=File:<name>&prop=imageinfo&iiprop=url|extmetadata&iiurlwidth=500`
   gives `thumburl` and `extmetadata.LicenseShortName`. Take only addresses under
   `upload.wikimedia.org/wikipedia/commons/`: `/wikipedia/en/` and other per-language paths hold
   non-free files a Wikipedia article may use and a deck may not. Thumbnails exist only at 120,
   250, 330, 500, 960, 1280 and 1920 px; any other `NNNpx-` width is a blank card. For an SVG use
   its `/thumb/…/500px-….svg.png` render, since the original never renders.

3. **Openverse**, which searches Flickr, museums and other open collections, with no key:
   `api.openverse.org/v1/images/?q=…&license=cc0,pdm` keeps to pictures that need no credit (add
   `,by,by-sa` only when the credit fits, below). Each result's `url` goes on the card; its
   `license` and `attribution` say what to credit.

4. **Unsplash**, for an everyday object or scene nothing above shows: the photo's
   `https://images.unsplash.com/photo-…?w=1080&fm=jpg` address, never the web page's and never
   `plus.unsplash.com`, which is paid and licensed separately. The Unsplash Licence needs no
   credit.

**Licences.** Public domain, PDM and CC0 need nothing. CC BY and CC BY-SA need a credit, and the
deck's `--description` is the only place for one: `Pictures: <author>, <licence>, via <source>`,
per picture, within the description's 500 characters. When the credits will not fit, use a
public-domain picture for those cards or leave them without one. Never use a picture marked NC,
ND, "fair use", "all rights reserved" or with no licence at all, and never take an address from a
search engine's results, Pinterest, Instagram, or a host that forbids hotlinking (Pixabay does):
the phone fetches each picture from where it is hosted, for every learner, for as long as the deck
exists.

A host the sandbox cannot reach can still be right, because the phone fetches the picture, not
you. `--check-images` cannot vouch for those, so tell the user which URLs went unchecked.

## Decks from a TV series

"A deck from SpongeBob season 1" is a language deck: the goal is to understand the show in the
language the user watches it in, so every card comes from the show's own dialogue.

**Propose one deck per season before writing anything**, titled with the show, season and
language (`SpongeBob S1 · Spanish`). A season is a few hundred cards, and a deck per season lets
the user stop, or start at the season they are watching. Ask which language they watch it in and
which dub: a Latin-American and a Castilian dub use different words, and the deck's
`--back-lang` (`es-MX`, `es-ES`) has to match the one they hear.

**Get the dialogue from subtitles in the dub's language, never from memory.** Use the user's own
`.srt` files when they have them, then try, in order:

| Source | Good for | How |
| --- | --- | --- |
| OpenSubtitles | Most shows, most languages | `rest.opensubtitles.org/search/episode-<n>/query-<show>/season-<n>/sublanguageid-<spa\|por\|fre\|ger\|jpn…>` with the header `User-Agent: TemporaryUserAgent`, no key; path segments stay in that alphabetical order, and dropping `episode-<n>` lists the season. Each result's `SubDownloadLink` (on `dl.opensubtitles.org`) is a gzipped `.srt`, often Latin-1 rather than UTF-8. |
| Addic7ed | TV episodes, mostly English | `www.addic7ed.com/search.php?search=<show>` lists `serie/<Show>/<season>/<episode>/<Title>` pages; each subtitle's `/original/…` or `/updated/…` link downloads with that page as the `Referer`. |
| Jimaku — `jimaku.cc` | Anime in Japanese | Each `jimaku.cc/entry/<id>` page links its files under `/entry/<id>/download/…`, no key. |
| Kitsunekko — `kitsunekko.net` | Anime in Japanese | `kitsunekko.net/dirlist.php?dir=subtitles%2Fjapanese%2F` lists shows; files are `.ass` or `.srt`, sometimes zipped. |

Coverage is uneven — OpenSubtitles had Spanish for three of SpongeBob's first-season segments —
so search each episode, and say which ones had nothing.

Prefer a subtitle marked as the dub's own transcript (often "SDH" or "for the hearing impaired")
over a translation of the original: a translated subtitle does not say what the voices say. Check
the episode list (season, episode number, segment title) against the show's episode guide so
episode 3 really is episode 3 — many cartoons split an episode into segments (`s1e01c - Tea at
the Treedome`), so match on the segment title — and tell the user which files you used.
`loopky doctor` lists these hosts under `recommended`; when the one you need is blocked, ask the
user to allow it, as for the required hosts. If no source is reachable or
none matches the dub, stop and ask for the files: a deck built from the wrong subtitle teaches
lines the show never says.

**Clean the lines before choosing from them.** Drop timing, speaker labels, `[sound cues]`,
cues the uploader added (an episode title card, a credit, an advertisement for a website),
`♪` song lyrics, the opening and closing theme, and character and place names on their own. Join a
sentence split across two subtitle cues.

**Pick what is worth learning, not the transcript.** Every deck is public, so a season's dialogue
published in order is the show's script republished. Take each episode's useful words and phrases
— the ones a learner needs to follow it and would hear again — and leave the rest. Cards go from
the user's language to the one they are learning: the front is the meaning in their language,
translated for that scene, and the back is the line or word exactly as the show says it
(`--front-lang en-US --back-lang es-MX`). The user recalls the show's words, and Speak and typing
grade them. Nothing from outside the show: no invented example sentences, no "related" vocabulary,
and a word gets a card only in a form the episode uses.

**Order the file by episode, and never repeat.** Cards are studied new-first in file order, so
episode 1's cards come first, then episode 2's, and the user meets each episode's language before
they watch it. Within an episode, a word before the phrases that use it. A line or word already
carded in an earlier episode — or in an earlier season's deck, which you read with
`loopky card list <deckId> --json` — is not carded again; compare ignoring case, punctuation and
`...`. Put the episode on the front as an aside, `I'm ready! (S1E1) → ¡Estoy listo!`: the reader
sees where it comes from, and nothing grades it. Publish the first episodes
with `deck create`, and add later ones in order with `card add <deckId> --from-file`, which appends.

**Pictures from the episode only when their licence allows it, which is rarely.** Stills, frames
and screenshots of a commercial show are the studio's copyright, and fan wikis host them as fair
use, which the licence rules above exclude; a frame from the user's own copy would be an upload,
and Loopky takes only URLs. So an episode picture is limited to shows whose frames are public
domain or freely licensed on Wikimedia Commons (some early cartoons). Otherwise picture what the
card names — an object, an animal, a place — from the usual sources, or leave it without one.
Tell the user which it was.

**Tags.** Add the show's name (lowercase, no spaces, at most 20 characters: `spongebob`), and
`tv-series` or `anime`, plus `cartoon` where it is one. A show made for children also gets `kids`.
The language pair adds the language tags on its own. Turn on `--listen` and `--speak`, so the user
hears and says the lines, and `--type` when they want to spell them.

## Limits worth knowing

- The session can write only Loopky's own data (`/pub/loopky/`). It cannot post, follow or edit a
  profile, and widening that is not an option.
- Published decks are public. There are no private decks.
- A headless box with nobody at a phone can use `LOOPKY_SESSION`, minted with
  `loopky login --export` on a machine where the user can approve. It is a bearer token: never echo
  it, log it or commit it.

## Reporting friction to Loopky

Loopky improves from what agents trip over, and only the user can decide to tell the project. So
when the work is done, and only if this session hit **real friction in `loopky` or this skill**, ask
once whether to open a GitHub issue on `jvsena42/loopky`. Friction is: an error or exit code that
misled you, a step here that was wrong or missing, a flag or command you needed and the binary did
not have, a workaround you had to invent, or output you could not parse. It is **not** the user's
own network allowlist, a session expiring after its hour, a picture source with nothing fitting, or
anything the exit-code table already says how to handle. No real friction, no question.

1. **Ask only at the end, and collect nothing until the user says yes.** Never mid-task, not even
   right after the friction happens: finish what the user asked, verify it, and make this the last
   thing in your final message. In one short question, name the problem in general words
   ("`card add` failed on a newline"), say it would become a public issue on `jvsena42/loopky`,
   and ask whether they want one. Leave the rest for after a yes. Until they say yes, run nothing
   for it, check no GitHub account, and write no draft. A no, or no answer, ends it for the rest
   of the session.
2. **After a yes, gather only what reproduces the problem:** `loopky --version`, the OS and
   architecture, the command's shape with every operand replaced by a placeholder, the exit code
   and `error.code`, what you expected, what happened, and the workaround if there was one.
3. **Keep the user's data out.** The issue is public, so it never carries card or deck content,
   titles, descriptions or tags, deck or card ids, public keys, `pubky://` addresses, image or
   homeserver URLs, file or directory names, user, host or machine names, IP addresses, the
   `auth_url`, `LOOPKY_SESSION` or any other token, environment variables, or anything the user
   said about themselves. Describe error messages in your own words rather than pasting them,
   since they can carry ids and paths. Re-read the draft against this list before showing it.
   If the problem cannot be explained without one of these, leave it out and say so.
4. **Show the draft, title and body, and file it only after the user approves that text.** If the
   session has a signed-in GitHub tool (`gh auth status` succeeds, or a GitHub connector is
   available), use it, searching the repository's open issues first and adding to a matching one
   rather than opening a duplicate:

   ```shell
   gh issue create --repo jvsena42/loopky --title "<title>" --body-file <draft file>
   ```

   The draft file is a temporary one; delete it afterwards. Otherwise, or if they prefer, give
   them the link to file it themselves, `https://github.com/jvsena42/loopky/issues/new`, with the
   draft to paste. Issues are public, and are filed from the user's own account.

