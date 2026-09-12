# `loopky` — the headless client

A terminal binary that creates and manages Loopky decks against the same homeserver layout the
Android and iOS apps use. The point is not a nicer keyboard UX: **it is that an agent can drive
Loopky.** Agents already write good flashcards; until this, the only way into a Loopky deck was a
phone screen.

Design decisions and their reasoning live in [`docs/Architecture.md` §13](../docs/Architecture.md).
This file is how to build and use it.

## Install

One file, no JRE, nothing else on the machine.

```shell
curl -fsSL https://github.com/jvsena42/loopky/releases/latest/download/install.sh | sh
```

That picks the right build, checks its digest and drops it in `~/.local/bin` — no root anywhere.

**The installer comes from the release, not from `main`.** It is published as an asset at the tag
alongside the binaries it fetches. `raw.githubusercontent.com/.../main/cli/install.sh` is the
obvious URL and the wrong one: it pipes whatever `main` is at that second into `sh`, which is a
moving target for the one command here that runs unreviewed shell as the user.

Homebrew serves both rows:

```shell
brew install jvsena42/loopky/loopky
```

That is the one install `loopky update` refuses, with exit 11 — a Cellar file is not ours to
overwrite and the next upgrade would revert it anyway, so `brew upgrade loopky` is the command
there.

By hand is a supported answer and is one line, because the artifact really is a single binary:

```shell
curl -fsSL https://github.com/jvsena42/loopky/releases/latest/download/loopky-linux-x86-64 \
  -o ~/.local/bin/loopky && chmod +x ~/.local/bin/loopky
```

| | |
| --- | --- |
| Linux x86_64 | `loopky-linux-x86-64` · needs glibc 2.34+ (Debian 12, Ubuntu 22.04, RHEL 9 and newer) |
| macOS, Apple Silicon | `loopky-macos-aarch64` |
| Container | `docker run --rm -e LOOPKY_SESSION ghcr.io/jvsena42/loopky deck list --json` |
| Debian/Ubuntu | `loopky_<version>_amd64.deb` on the release page — `dpkg -i`. Depends on `libc6 (>= 2.34)` and `zlib1g`, which is the whole of it: no JRE, and nothing else |
| Homebrew | `brew install jvsena42/loopky/loopky` — above |
| Windows x86_64 | **build from source, for now.** `libpubkycore` loads there and the whole shared suite runs on it, but nothing Windows is published yet — no `loopky-windows-x86-64.exe`, and no jar distribution is a release asset on any row. `./gradlew :cli:windowsDistZip` is the answer until the release row lands (#301), and it needs a JRE 17, which is exactly what the binary exists to remove. |

**An Intel Mac is not a target**, by decision rather than omission (#54): there is one
`darwin-aarch64` row of `libpubkycore` and no `lipo`. **ARM64 Windows** is not one either — the x64
binary runs there under emulation, but a JVM reporting `aarch64` cannot load an x64 DLL into its own
process. Both are refused with a message naming the builds that do exist, rather than failing at the
first homeserver call with something that reads as "that deck does not exist".

There is **no hosted apt repository today**, so the `.deb` is a file rather than a source: nothing
tracks it and `apt upgrade` will never move it — a new version means downloading the next one, which
is what `loopky update` says when it refuses on a `dpkg`-owned file. Adding a third-party apt repo
is four privileged commands and a GPG keyring on a machine that may not have root, where the same
binary is one `curl` and needs none — and a signing key is an obligation with no end date. That
trade-off is unsettled rather than closed: **#247** holds what it would take and what it would cost.

## Build

```shell
./gradlew :cli:nativeCompile        # -> cli/build/native/nativeCompile/loopky, the shipped artifact
./gradlew :cli:installDist          # -> cli/build/install/loopky/bin/loopky, jar + start script
./gradlew :cli:linuxDistTar         # -> cli/build/distributions/loopky-linux-x86-64.tar
./gradlew :cli:macosDistTar         # -> cli/build/distributions/loopky-darwin-aarch64.tar
./gradlew :cli:windowsDistZip       # -> cli/build/distributions/loopky-win32-x86-64.zip
./gradlew :cli:test                 # the CLI's own unit tests
./gradlew :shared:jvmTest           # the whole shared suite on the jvm() target, plus the
                                    # FFI smoke test that proves libpubkycore actually loads
```

`nativeCompile` needs a **GraalVM for JDK 25** and takes it from `GRAALVM_HOME` (or `JAVA_HOME`);
compilation itself still runs on the toolchain's JDK 17. `native-image` does **not** cross-compile,
so the machine you build on is the machine the binary runs on. For Linux that means a container,
which is also how CI and the release do it — one recipe, no second one to drift:

```shell
docker build -f cli/Dockerfile --target export \
  --output type=local,dest=cli/build/native/linux-x86-64 .
docker build -f cli/Dockerfile --target runtime -t loopky .
```

**Ubuntu 22.04 is the base on purpose.** A native image links against the glibc of the machine that
built it, and the floor is not ours to choose — `libpubkycore.so` already needs 2.34. Building on a
newer runner produces a binary that will not start on hosts the library is perfectly happy on.

The jar distributions are still built and are still worth having: they need a JRE 17, but they are
produced for **any** row from **any** host, where a binary cannot be. `installDist` carries all
three native rows because cross-row is the point of a developer build; `linuxDistTar`,
`macosDistTar` and `windowsDistZip` carry one each, so a Linux box no longer hauls 11 MB of macOS
dylib it can never load. None of them is published as a release asset.

The native library ships inside the jar under JNA's resource layout, so nothing is installed by
hand. Rebuild it in the fork with `./build_desktop.sh linux|macos|windows|all` and copy
`bindings/desktop/` into `shared/src/jvmMain/resources/`; see the README there.

**`all` cannot produce the Windows row here.** `x86_64-pc-windows-msvc` needs the Microsoft linker
and the Windows SDK, so unlike Linux there is no container that cross-builds it from a Mac — that
row comes from the fork's `desktop-windows.yml` and nowhere else.

## Use

**Your key lives in Pubky Ring, so install it before the first login.** `loopky login` has no
password to take and no key of its own: it prints a QR, and the phone app on the other end of that
scan is what approves the session. So the order is Ring first, then this — install [Pubky
Ring](https://pubkyring.app), put your recovery phrase into it, and keep that phrase somewhere the
phone is not. Losing it loses the account, and there is nobody to ask for it back.

```shell
loopky login                        # QR for Pubky Ring, then waits for approval
loopky login --export               # also prints the session secret, for LOOPKY_SESSION
loopky login --timeout 120          # give up after two minutes instead of blocking forever
loopky whoami --json
loopky logout

loopky commands --json              # the whole surface as JSON. No session, no network.

loopky update --check            # is there a newer release?
loopky update                    # fetch it, check its digest, replace this binary

loopky deck list
loopky deck create --title "Capitais" --tag geografia --tag "português" --from-file cards.tsv
loopky deck create --title "Capitais" --id capitais0001 --if-not-exists   # safe to re-run
loopky deck create --title "Capitais" --from-file cards.tsv --dry-run     # pre-flight, no write
loopky deck show <deckId> --json
loopky deck edit <deckId> --cover-url https://…/capitais.jpg --tag geografia --tag capitais
loopky deck sync <deckId>
loopky deck compact <deckId>
loopky deck delete <deckId>

loopky card list <deckId> --json
loopky card list <deckId> --json --missing-image --limit 50    # a page, not the whole deck
loopky card add <deckId> --front "Brasília" --back "Capital do Brasil"
loopky card add <deckId> --from-file more.tsv          # appended in groups of 100
loopky card add <deckId> --from-file more.tsv --dry-run
loopky card edit <deckId> --from-file edits.jsonl
loopky card rm <deckId> <cardId>

loopky import cards.tsv --title "Biomas e Sub-ecossistemas Brasileiros" --resume
cat cards.tsv | loopky import - --title "…" --separator tab

loopky import deck.apkg --dry-run --json     # look before you publish. No session needed.
loopky import deck.apkg --title "Japanese Core 2000" --front-field Expression --back-field Meaning

loopky tag trending --limit 20

loopky batch ops.ndjson --json       # a sequence of commands against one session
cat ops.ndjson | loopky batch - --json

loopky completion bash               # a completion script on stdout, for eval or a file
```

`loopky --help` is the full surface. Every command takes `--json`.

**`--json` puts the command's own shape under `data`, never at the top level.** Every result is one
line:

```jsonc
{"schema":1,"ok":true,"command":"card list","environment":"production","indexer":"…",
 "update_available":null,
 "data":{"deck_id":"…","count":1210,"card_count":1210,"next_cursor":null,
         "cards":[{"id":"…","front":{"text":"…","image":{"url":"…","mime":"…"}},"back":{…}}]}}
```

A failure is the same object with `"ok":false` and an `error` `{code, exit, message}` — on stdout
too, so one stream carries both outcomes. Two things worth knowing before writing the `jq`: it is
`data.cards[]`, not `cards`, and a card's `front` is an **object** with `.text`, not a string.
`loopky --help` lists what `data` holds for the other reads.

**A flag a command does not take is refused, by name.** The parser used to accept any `--long` and
drop the ones it did not want, so the command failed for a second, unrelated reason:

```
$ loopky import --dry-run --check-images --from-file deck.tsv
loopky: Unknown option --from-file for 'import'. --from-file belongs to `deck create`, `card add`,
`card edit`. `import` takes its file as a positional operand: loopky import <file>. It takes:
--title, --description, …
```

A command the binary does not have is refused the same way. The message names the near miss when
exactly one command is within two edits, or lists the group's verbs when the noun was right. Where
two commands are equally close it names neither, because a wrong guess is worse than no guess:

```
$ loopky command --json
… "message": "Unknown command 'command'. Did you mean `commands`? Try `loopky --help`."
$ loopky card delete d1 c1
loopky: Unknown command 'card delete'. `card` takes one of: list, add, edit, rm. Try `loopky --help`.
```

The usage block still follows a usage error, with the message **repeated underneath it** — a
terminal keeps its last lines, and sixty lines of manual is exactly how the one that mattered got
scrolled away.

### Language decks

A deck that declares a language pair is also **labelled** with it, and the label is the whole
reason to declare one beyond the audio:

```shell
loopky deck create --title "Verbos" --front-lang en-US --back-lang es-ES --listen --speak
# tags: language, english, spanish
```

`--front-lang`/`--back-lang` in the manifest are what `Deck.speechReady` gates Listen and Speak on
— without them the OS engines fall back to the *reader's* device locale, and a Spanish deck is read
aloud with English phonetics. But a manifest is not something a network-wide index can answer
questions about; a **tag record** is. So the pair also contributes `"spanish"` and the `"language"`
umbrella (base subtag, named not coded — `es-ES` and `es-MX` are both `"spanish"`), which is what
puts the deck in front of someone learning it through tag browse and `tag trending`. This is what
the apps do on the language pick; the CLI did not until #225, and a deck published before that
carries the pair and no labels.

Four rules:

- **A deck that declares no pair gets nothing**, umbrella included. Most decks are not language
  decks, and `"language"` on a deck of capital cities would be a lie the index repeats.
- **They are ordinary tags you can remove.** Nothing reserved: `deck edit --tag verbos` replaces
  the set and drops them, deliberately, because `--tag` means "the tags are exactly these".
- **Retyping swaps them.** Moving a deck from Spanish to French takes `"spanish"` off as it adds
  `"french"`, or the deck stays listed as Spanish forever.
- **Naming the pair reconciles them**, whether or not it moved — which is the repair for a deck
  published before this existed, and for one whose labels a `--tag` replaced:

  ```shell
  loopky deck edit <deckId> --front-lang en-US --back-lang es-ES --json   # restate; labels return
  ```

  Naming *no* pair leaves them alone, so `--clear-tags` on a language deck really does empty it.

## Anki `.apkg`

Bulk Anki import is the job this tool was built for (#46). `import` takes an `.apkg` on the same
command and through the same parser spine as a text file — the shared reader turns the collection
into notes and `parseBulkNotes` applies the same dedupe, caps and drop policy — so a deck imported
here and one imported on a phone come out the same.

```shell
loopky import deck.apkg --dry-run --json
loopky import deck.apkg --title "Japanese Core 2000" --front-field Expression --back-field Meaning
```

**Look first.** `--dry-run` reads the archive, reports what publishing it would do and writes
nothing; it needs no session, because reading a local file is not a homeserver operation. It is
worth doing every time, for three reasons that are all one reason — an `.apkg` is the import where
being wrong is expensive and invisible:

- **Which two fields become the card.** Anki decks routinely have fields called "Field 3". The
  reader scores them and usually chooses well, but 9000 Spanish Sentences once imported 9,213 cards
  reading `2528426` → `2760065`, because its first two fields are database ids. `--dry-run` shows
  every field with a real sample value; `--front-field` / `--back-field` take a name or a **1-based**
  number and disagree with the choice.
- **What did not become a card.** `dropped` breaks the notes down into `empty`, `half_empty` and
  `missing_media`. Reported rather than subtracted: 1,458 notes in and 1,338 cards out used to be
  explained as "1 duplicate".
- **What it will spend.** This is the **only** command that uploads bytes. Everywhere else a card's
  picture is a URL (#167) — no quota at all — but an `.apkg`'s pictures are blobs, written against
  a 1 GB homeserver allowance with no endpoint that reports what is left and a 507 that is
  terminal. And `loopky` ships no image codec, so unlike the apps it cannot shrink them: they go up
  **at full resolution**. `images.bytes` is that total, before it is spent.

Anki's own deck description and note tags are **reported and never adopted** — the description is
AnkiWeb boilerplate more often than not, and a tag is a public record indexed network-wide. Pass
them back as `--description` / `--tag` if they are right. A reversed Anki note type does set
`--reverse` on by default, since that is a fact about the deck rather than a label on the network;
`--no-reverse` turns it off.

Two failures have their own advice under exit code 9. A collection this build cannot unpack
(`collection.anki21b`, zstd — see "not in scope" below) and an export holding only Anki's legacy
compatibility stub both mean "re-export with *Support older Anki versions* ticked, or as Notes in
Plain Text"; a corrupt archive means "download it again". They used to be one message that sent
people hunting for a format problem they might not have had.

`.apkg` cannot come from stdin: a SQLite driver opens a path, not a stream. `collection.anki21b`
stays unsupported and reported — it is the one variant that would need a real dependency.

## Card and import files

```
TSV     front <TAB> back <TAB> front_image_url <TAB> back_image_url    (last two optional)
JSONL   {"id":"…","front":"…","back":"…","front_image_url":"…","back_image_url":"…"}
JSONL   {"id":"…","front":{"text":"…","image":{"url":"…"}},"back":{…}}    what card list emits
```

**Both JSONL shapes are read, which is what makes the round trip work.** `card list --json` emits a
card with sides as objects and the picture as `{"url":…}`; a card file takes flat `front` and
`front_image_url`. Answering "has this row already been applied?" across that gap needed a shape
check *and* a percent-decode, and getting it wrong silently rewrites every row on every pass. So a
deck can now be read, edited with `jq` and fed straight back:

```shell
loopky card list <deckId> --json \
  | jq -c '.data.cards[] | select(.front.image == null) | {id, front: {image: {url: ("https://…/" + .front.text + ".jpg")}}}' \
  > edits.jsonl
loopky card edit <deckId> --from-file edits.jsonl
```

The tri-state survives the translation: an **absent** key leaves that field alone, an explicit
`null` clears it. `card list --json` writes explicit nulls, so feeding its output back sets every
field to exactly what it read. The one thing a card file cannot name is a **blob** picture — an
image with a `sha256` and no `url`, which is what an `.apkg` import produces — so those are left
unchanged rather than cleared, and counted in a note on stderr.

An image column must be an `http(s)` URL — a third column holding prose is refused rather than
stored as a picture, because a 3-column Anki export (Front / Back / Example sentence) would
otherwise publish every card with an image ref pointing at a sentence. `loopky import` is more
forgiving with the same file: it falls through to the text parser instead, since there the third
column is content somebody wants imported. Blank lines and `# ` comments are skipped in TSV — hash
**plus whitespace**, so a card whose front is `#1 ranked` survives.

The format is chosen by extension, then by content, never by a flag. JSONL is for the two things
TSV cannot hold: a side containing a tab or a newline, and — for `card edit` — naming which card to
change and which fields to leave alone. A field that is absent in an edit row is left unchanged;
clearing a side takes an explicit empty value.

The image columns matter more than they look. Both bulk paths in the apps carry a picture only when
a field is *nothing but* that image, so "this side has text **and** a picture" had no
representation at all — which is why ~190 images had to go through the card editor by hand even
though the decks imported in one shot. An image here is a **URL**, so no bytes cross the wire and
no media quota is spent.

### What can be checked about a picture, and what cannot

Nothing is fetched, which is what makes an image column free — and it is also the one place this
client cannot tell you it worked. Every other way into Loopky puts a human in front of the picture
before it is stored: the image sheet's Done button waits on a preview that actually loaded. `loopky`
has no such moment, so a deck of 200 cards can be built entirely out of addresses that answer 403,
with `--json` reporting success on every one. That is not hypothetical — it is where these checks
came from.

Making it a crawler is not the fix: a hundred round trips against hosts that rate-limit, and still
nothing said about the ones that answer 200 today. So the line is drawn at what a string can be
*known* to be wrong about, without asking anyone.

**Refused** — `https://` only. Android blocks cleartext at targetSdk 28 and up and iOS ATS does the
same, so an `http://` address is a card whose picture cannot render on either client. Exit 9, with
the scheme named, rather than a stored ref that is broken by construction.

**Warned about, on stderr, never fatal** — Wikimedia serves thumbnails at **120, 250, 330, 500, 960,
1280 and 1920 px** and answers `400, Use thumbnail sizes listed on https://w.wiki/GHai` for every
other width. An agent writes `320px-` or `800px-` as readily as `250px-`; they look equally plausible and
most of them are a blank card on both apps. Drop the `/thumb/` segment and the `NNNpx-` prefix
altogether to get the full-size original, which is always served. A warning and not an error
because the list is Wikimedia's to change, and a stale check must not be able to fail an import.

**Warned about, on stderr, never fatal** — a file type neither client decodes. Android loads with
Coil, which ships no SVG decoder here, and iOS with `UIImage`, which decodes neither vectors nor
`.tif`, `.webm`, `.ogv` or `.stl`. That is not an exotic case: a Wikipedia lead image is frequently
one of them — every flag and colour swatch is an `.svg`, and `Dente`, `Piede`, `Tostapane`,
`Rotonda` and `Tastiera` resolve to `.stl`, `.tiff`, `.webm` and `.ogv`. All are valid
`upload.wikimedia.org` addresses that answer 200, and all are blank cards.

For these the thumbnail rule above is **inverted**, which is why the two are decided together. The
original of a vector is `image/svg+xml`, so "drop the `/thumb/` segment for the original, which is
always served" is precisely the wrong advice — the thumbnail is the only rendered raster there is.
The warning rewrites it for you:

```
https://upload.wikimedia.org/wikipedia/commons/0/03/Flag_of_Italy.svg
  -> https://upload.wikimedia.org/wikipedia/commons/thumb/0/03/Flag_of_Italy.svg/500px-Flag_of_Italy.svg.png
```

Wikimedia renders `.tif` and `.webm` under `/thumb/` too, but with prefixes of their own
(`lossy-page1-`, a frame marker), so those are named rather than rewritten — an address invented
here that 404s would be worse than the sentence. Those renders are **not** findings, though: only
the **final** extension is judged, so `…/Cell.tif/lossy-page1-500px-Cell.tif.jpg` is the ordinary
JPEG it is, exactly as `…/Sign.svg/500px-Sign.svg.png` always was. `…/Cell.tif` and `…/Sign.svg`,
ending there, still are.

If the URLs came from the **`imageinfo` API**, two cleanups first: it appends
`?utm_source=…&utm_campaign=imageinfo` to `url` and `thumburl`, and it answers with
`thumb.wikimedia.org` rather than `upload.wikimedia.org`. Both addresses work, so neither is
warned about — but `upload.` is what the rules here are written for.

Beyond those, prefer a host that serves images to anyone. Some refuse an unfamiliar client
outright — Wikimedia answers `403 Please set a user-agent` to a generic one — and the result is the
same blank card with nothing reporting it.

### `--check-images`, when a string is not enough

Three things no rule above can see produce exactly the same blank card: a Wikipedia lead image that
resolves to `.stl` or `.webm` behind an ordinary-looking address, a file that has been renamed or
deleted, and a host that refuses an unfamiliar client. One `HEAD` catches all three.

```shell
loopky import cards.tsv --title "…" --dry-run --check-images   # worth the most here
loopky card edit <deckId> --from-file edits.jsonl --check-images
```

Available on `deck create`, `card add`, `card edit` and `import`, `--dry-run` included. It reports
the status and content type of everything that is not a 2xx picture, on stderr and in `--json` as
`image_checks` — and reports **nothing** about a URL that is fine, because a finding buried in 900
lines of "this one is fine" is no better than the check you wrote by hand.

**What a string is known to be wrong about travels separately, as `image_advice`.** An
undecodable format or a thumbnail width Wikimedia does not serve is reported on `deck create`,
`card add`, `card edit` and `import` whether or not `--check-images` was passed, so it is a sibling
array rather than a row in `image_checks` — which is documented as what that opt-in flag found. On
stderr it is printed last, after everything the network had to say, and capped at 20 entries like
the buckets below; `--json` carries them all. One row per **distinct** URL, as `image_checks` is,
each listing every card and side it appears on — `{"url": …, "where": [ … ], "advice": …}`. It
matters most on `deck create --from-file --dry-run`, the pre-flight for a file you are about to
publish with.

**"Wrong" and "could not be checked" are different answers.** A `429`, a timeout or a `5xx` says
nothing about the picture, so it is `unverified` in `--json` and counted apart in the summary line:

```
loopky: picture URLs — 249 ok, 0 wrong, 1 could not be checked; writing anyway.
```

Folding the two together is what made this unusable at scale — 432 of 475 Wikimedia URLs came back
"wrong", every one of them a rate limit the check had provoked itself, and between them they
scrolled the run's single real finding off the screen. Neither bucket prints more than 20 lines
now; `--json` carries every row.

An `image/` prefix is not the same as a decodable picture, and that distinction is load-bearing
here: Wikimedia serves an SVG original as `image/svg+xml` with an entirely ordinary 200, so a
prefix check would call a whole deck of flags fine. `image/svg+xml` and `image/tiff` are findings.

Four properties, and each is a decision rather than an omission:

- **Opt-in**, because it is the only flag here that makes requests of its own. An ordinary
  `card add` stays one write and no round trips.
- **A warning, never a refusal.** A host having a bad minute must not be able to fail an import; the
  picture may well be fine. The write goes ahead and the note says so.
- **One request per distinct URL**, not per card, at **three** at a time, with a `429` retried and
  `Retry-After` honoured. A picture on forty cards is one question, and against Wikimedia more in
  flight is *slower* as well as noisier: 100 URLs took 32 s at three and 42 s at six, and 250 came
  back 250/250 clean in 83 s. `--check-images-concurrency N` (up to 16) is for a host that is not
  Wikimedia, not a speed dial.
- **It sends a real user agent.** `403 Please set a user-agent` is Wikimedia's answer to a generic
  client, which is the very failure this exists to catch; a probe that produced it on every
  Wikimedia URL would be worse than no probe. A host that refuses `HEAD` outright is asked again
  with a one-byte ranged `GET`, so a working picture is not condemned by a quirk of the method.

## Tab completion

```shell
eval "$(loopky completion bash)"                                 # in ~/.bashrc
loopky completion zsh > "${fpath[1]}/_loopky"                    # then restart the shell
loopky completion fish > ~/.config/fish/completions/loopky.fish
```

Commands, subcommands, every flag, and the values of the flags that have a closed set — `--env`,
`--separator`, the shell name itself. `import` completes filenames; so do `--from-file` and
`--qr-out`.

The `.deb` and the Homebrew formula install all three for you. The `curl` installer does not: it
says the command exists and stops there, because enabling completions means writing to a shell rc
file or a system directory, and an installer that edits `~/.bashrc` behind you is one you cannot
cleanly undo.

Two things it deliberately does not do.

**A deck id is never completed.** It would be a homeserver round trip on a keypress — a tab that
hangs for a second on a good network, and forever on the hourly-expired session — in the one place
you cannot interrupt without losing the line you were typing. zsh and fish name the word they want
(`deckId`) and offer nothing; bash offers the flags and stays quiet.

**The script describes the binary that printed it.** It is generated from the same command table
the binary dispatches on, so it cannot offer a flag this build refuses — but that also means a
copy written to a file goes stale on upgrade. Regenerate after `loopky update`; `eval` in an rc
file never has the problem.

## Environment

| Variable | What it does |
| --- | --- |
| `LOOPKY_SESSION` | A session secret — a **bearer token**, see the note below. Read **before** the stored session, and the only way in on a sandbox that has no stored one. Mint it with `loopky login --export` on a machine with a human at it, and revoke it with `loopky logout` while it is set. |
| `LOOPKY_ENV` | `staging` or `production`. `--env` wins. Defaults to production. |
| `LOOPKY_CONFIG_HOME` | Where state lives. Defaults to `$XDG_CONFIG_HOME/loopky`, then `~/.config/loopky` (`~/Library/Application Support/loopky` on macOS, `%LOCALAPPDATA%\loopky` on Windows — `Local`, not `Roaming`, so a session secret is not copied to a domain profile server at logoff). Setting it also moves the session out of the macOS Keychain and back into a file — and on Windows, pointing either it or `XDG_CONFIG_HOME` at a roaming location (`%APPDATA%`, `%USERPROFILE%\.config`) puts the session back in the roaming profile. |
| `LOOPKY_NO_UPDATE_CHECK` | Set to anything to never look for a newer release. The check is cached for a day, runs alongside the command, and can never fail it — but a pipeline that wants no surprises can switch it off. `--no-update-check` does the same for one invocation. |
| `RUST_LOG` | The pubky SDK's own tracing, defaulted to `warn` — by the start script in the jar distribution and through libc in the binary, which has no start script. `RUST_LOG=debug` is the first thing to try when a homeserver call fails for no visible reason. |

## Exit codes

| | | | |
| --- | --- | --- | --- |
| 0 | ok | 6 | not found |
| 1 | internal | 7 | storage full (507 — terminal, never retried) |
| 2 | usage | 8 | environment mismatch |
| 3 | not signed in | 9 | bad input |
| 4 | **session expired** | 10 | unsupported host |
| 5 | network | 11 | update found, not applied |
| | | 12 | homeserver 5xx |
| | | 13 | `login --timeout` ran out |

`loopky commands --json` carries this table — name, number and a line of what to do about it —
along with the subset each command can actually produce. That last part is worth reading for its
*absences*: `tag trending` never answers `session_expired`, so there is no point signing in first.

13 is nobody approving a sign-in inside `--timeout`. *That process* is not signed in — deliberately
not "nothing was stored", which it cannot promise: the await runs on a thread that is unobserved
rather than stopped, so an approval landing as the process exits still persists a session. `loopky
whoami` answers it. The code that was on screen is spent either way — the FFI's auth flow is a
single slot the first poll takes — so the recovery is `loopky login` again rather than a retry.

12 is the homeserver answering with a server error of its own. It used to be **1, internal**, which
this table documents as "worth reporting as a bug" — and a 500 is not a bug in the client, not the
caller's input, and unlike every other row here it may well work on the next attempt. A batch told
`internal` sends an agent looking through its own file for the row that broke it; told
`server_error` it retries the rows that did not land. Deliberately not 5, which promises the request
never arrived: it did, and it may have been applied.

11 is `loopky update` refusing honestly: there *is* a newer release and this copy is not ours to
replace — a Homebrew or `.deb` install, a container layer, the jar directory, a file the user
cannot write. Never 0, because an agent that asked for an update and got a zero would carry on
believing it had one. The message names the command that does own it.

10 is the machine, not the command: there is no `libpubkycore` for this OS/architecture pair, and
no retry, no re-login and no second attempt can change that. It has a code of its own because
without the check the answer is not vague but *wrong* — the JNA lookup misses with "…not found in
resource path…", which the shared classifier reads as **6, not found**, and an agent is told the
deck it asked for does not exist.

4 has a code of its own because the homeserver session dies after roughly an hour and nothing
renews it: writes start failing, reads keep working, and from the outside that is
indistinguishable from a network wobble. An agent told the wrong one either retries a dead session
forever or abandons a working network.

`--dry-run` on `import` exits 0 without a session at all: it reads a local file. On `deck create`
and `card add` it needs one, because what those two have to check — is this id free, is this row
already in the deck — is a homeserver read. `deck create --dry-run` answers the first in
`data.created`: `true` means the deck *would* be published, `false` that the id is taken, and
`dry_run` beside it says nothing was written either way.

`whoami --json` reports `session_live`, asked rather than assumed — worth checking before starting
an hour-long import rather than forty cards in. There is no `expires_at` to report: the session
payload does not carry one.

## Things worth knowing before you script it

- **Nothing prompts.** Including the nice ones — there is no "announce this deck?" confirmation,
  because this client never requests the capability a post would need. `login` is the only command
  that blocks on a human, and `--qr-out` / `--url-only` exist for a box with no terminal anyone is
  watching.
- **stdout is the machine channel, and it is held that way at the descriptor.** Results and
  failures both go there as `--json`; the QR code, prompts, progress and every log line go to
  stderr. `--json` silences **progress counters** on stderr, because the result carries the same
  numbers — it does not silence stderr. Warnings still arrive there, so capturing stderr for
  diagnostics is worth doing in either mode.

  That is enforced rather than agreed: `libpubkycore` installs a `tracing` subscriber whose default
  writer is stdout, so a DHT bootstrap error — routine on a box that reaches the homeserver fine —
  used to land ahead of the envelope where `2>/dev/null` could not remove it. fd 1 is now pointed at
  stderr before the FFI loads and Kotlin keeps a duplicate of the real one, so anything writing to
  the raw descriptor goes to stderr no matter which layer it came from. `| jq` needs no `grep '^{'`
  in front of it.
- **`card add` is idempotent** by front/back-plus-image, and reports what it skipped. `import
  --resume` checkpoints against the deck on the homeserver rather than a local cursor, matched on
  `--title` — which is why `--title` is mandatory and never derived from a filename.
- **`card add --from-file` appends in groups of 100, and says so as it goes.** One write per card
  is a chunk write *plus* a whole-manifest read-modify-write each — 170 cards took ten minutes and
  printed nothing until the end, against 7.9 s now. Groups rather than one call for the file,
  because an append is all-or-nothing: a batch that dies partway leaves every completed group on
  the homeserver, and re-running skips them. A failed group is not retried in-process — re-run the
  command, which is a correct recovery precisely because the dedupe reads chunk records.
- **`--dry-run` runs the command's own path.** It is on `import`, `deck create` and `card add`, and
  each stops just before its own write. Pre-flighting a `deck create --from-file` through `import
  --dry-run` reads a *different* parser, which is how the two came to disagree about a well-formed
  four-column TSV.
- **`card edit --from-file` is idempotent too, which is why it has no `--resume`.** A row already
  holding what it asks for is skipped rather than rewritten, so re-running the same file *is* the
  resume: no cursor to keep, nothing to pass, and no `updated_at` churn on rows that did not change.
  Three more properties come with it, and all three come from one 665-row batch that 500'd after 35
  writes and said nothing about the 35:

  - **Everything is validated before anything is written.** Ids, both-sides, image URLs. A bad row
    400 fails the command with the homeserver untouched rather than 399 rows in.
  - **One refused row does not end the batch.** The rows after it are attempted — when a batch fails
    and the same rows apply singly, the row is not the problem. A failure that *will* refuse
    everything (an expired session, a full disk) does stop it, and so does a run of five in a row;
    the result says how many were never reached.
  - **A failed batch still reports what it wrote.** The same result shape travels on the failure
    envelope as `data`, with `written` / `skipped` / `failed` / `not_attempted` and, per failed row,
    its file line, card id, exit code and message. A homeserver 500 is also retried twice before it
    counts as a failure — the shared layer already recovers an expiry, a 429 and an unreachable
    session round trip, and a 500 was the gap.
- **`loopky commands --json` is how a binary describes itself.** Every verb, the operands it takes
  and their arity, its flags and whether each takes a value, whether it needs a session, and the
  exit codes it can produce. `loopky --help --json` prints the same thing. It is generated from the
  same table the completion scripts are, so it cannot describe a surface this binary does not have,
  and it needs no session, no network and no FFI — it works on a broken install. Reading `--help`
  as prose, or the repository's `USAGE` constant, is no longer the way in.
- **`loopky batch` runs a sequence of commands against one session, and the saving is real.** Every
  homeserver command pays process start, the FFI load and a session round trip — all three of which
  a batch pays once: on staging,
  `whoami` is ~2.5s before it does anything. `card add --from-file` and `import` already amortise
  that where they apply; a *sequence of different* commands — create a deck, add cards, read them
  back — had no amortised form and is the shape an agent produces. Measured: six operations, 28.7s
  as separate invocations, 10.6s as one batch; eight read-only ones, 26.9s against 8.7s.

  A line is `{"argv": ["card", "add", "deckid", "--front", "a", "--back", "b"]}`, with an optional
  `"id"` echoed back; the bare array works too. Each line goes through the same parser and the same
  dispatcher a command line does, so a batch can never accept something the CLI does not. Under
  `--json` every operation streams a line of its own carrying that command's whole result, then the
  envelope summarises — and each streamed envelope's own `ok` is that operation's, so branching on
  it is safe, and an operation's rendered result reaches stdout the way a single command's does.
  The whole file is validated before the first operation runs, unknown verbs included:
  a `deck creat` on line 400 fails with the homeserver untouched rather than 399 writes in. A failure does
  not end the run unless `--stop-on-error`, and the exit code is the **first failure's** rather than
  one of the batch's own — `session_expired` and `storage_full` say different things about whether
  re-running the file is worth anything.

  Nothing is transactional and nothing rolls back. Re-running is the recovery, which is what
  `card add`, `card edit --from-file` and `deck create --id --if-not-exists` being idempotent are
  for. One session for the whole run also means one hour: a long batch hits the expiry exactly
  where a long sequence of separate commands would.
- **`deck create --id` makes a killed create addressable, and `--if-not-exists` makes it a
  no-op.** Without an id, an ambiguous failure could only be recovered by listing decks and
  matching on title — neither cheap nor race-free — and a plain re-run published a second deck.
  With both flags the retry hands back the deck that is already there and reports
  `created: false` — unless that deck is **incomplete**, in which case it re-publishes and finishes
  it, which is the case the flag exists for. A deck someone else authored never occupies your id. Without `--if-not-exists` an id that is taken is **refused** rather than
  published over: a publish replaces the manifest and its whole chunk table, so a reused id would
  take the deck's cards with it, and nothing here prompts.
- **`login` can be bounded.** `--timeout <seconds>` exits 13 rather than blocking until somebody
  reaches for their phone. Bound it inside the process rather than wrapping it in `timeout -s KILL`:
  the signal skips the sweep that deletes a `--qr-out` file, which leaves a live auth URL on disk.
- **The session can only write `/pub/loopky/`.** Not `/pub/pubky.app/`. It cannot post, follow, or
  edit a profile under any bug or any prompt injection, because it was never handed the capability.
  Deck tags still work — a deck manifest's tag record lives in the loopky namespace.
- **On Linux a session is a mode-0600 file, not an OS keyring**, and that is deliberate: libsecret
  is usually absent on the headless box this is built for. A session secret on that machine is
  protected by file permissions and nothing else. What is stored is a capability-scoped, expiring
  session — never a secret key, which never leaves Pubky Ring.
- **On macOS it is in the login Keychain instead.** That row is a developer's machine, where the
  Keychain is always there and there is a human at the keyboard, so the Linux reasoning does not
  apply. The item is `loopky.session`, written through `security(1)` — never with the secret in
  `argv`, which any local user can read. Two things worth knowing: the item is trusted to
  `/usr/bin/security` so reads do not raise a dialog, which means anything that can run that tool
  as you can read it; and setting `LOOPKY_CONFIG_HOME` **or `XDG_CONFIG_HOME`** opts back into the
  file, because both mean "keep everything here" — worth knowing if you export the second out of
  habit. `loopky whoami` reports which one holds your session.
- **`LOOPKY_SESSION` is the weaker channel of the two**, and worth knowing before you reach for it
  on a shared host. An environment variable is readable by any same-uid process through
  `/proc/<pid>/environ`, is inherited by every child the CLI spawns, and lands in shell history
  when set inline. `LOOPKY_SESSION=… loopky …` as a one-shot prefix keeps it out of unrelated
  processes' environments but **not** out of `history`. It is still the right tool for an
  ephemeral sandbox, which has no stored session and no human to scan a code — the point is that
  it is a bearer token, so `loopky logout` with it set now *revokes* it rather than refusing.
- **`--qr-out` writes a live credential.** The PNG is the `pubkyauth://` URL, secret included — a
  QR code is an encoding, not a protection. It is created `0600` and deleted once approval lands,
  but for the length of the approval window that file is a session anyone who can read it can
  take.
- **You are told when the client is stale, and never updated behind your back.** A newer release
  arrives as one line on stderr and as `update_available` in the `--json` envelope — null when
  there is nothing to say, otherwise `{version, schema, schema_changed}`. The last of those is
  worth branching on separately: a newer CLI at the *same* schema is a convenience, one at a
  different schema means your parser may be wrong. The check is one cached-for-a-day HTTPS GET
  against the release page the installer already uses, it runs alongside the command, and a check
  that fails is silent rather than fatal. `loopky update` is the one command that acts on it, and
  it refuses — with the right command, and exit 11 — on a Homebrew or `.deb` install, in a
  container, and on the jar.
- **`card list` can page, and a page really is cheaper.** Plain `card list` means the whole deck.
  `--limit` and `--cursor` walk the manifest's chunk table and fetch only the records the page
  needs, so deciding which of 4,000 cards still want a picture no longer costs ~700 KB per pass;
  `--json` carries `next_cursor` while there is more, and the human path says so on stderr rather
  than adding a line to stdout that a `cut` would count as a card. `--missing-image` /
  `--has-image` narrow what comes back and compose with both.

  There is **no server-side filter** to ask for instead, and that is structural: the homeserver
  stores opaque records and Nexus indexes tags, not cards. So a filter without `--limit` saves the
  output and the caller's work, not the fetch. A page comes back in chunk order, which is study
  order wherever the two could differ — chunk `n` owns a private slice of the ord line, so cards
  cannot sort across chunks. A cursor is a place in the deck rather than a snapshot of it: one
  naming a chunk that compaction has since folded away resumes at the next one that exists.
- **A session and an `--env` that disagree is a hard error**, not a warning. Nexus answers a query
  aimed at the wrong network *successfully and empty*, so a mismatch would look like a failed write
  rather than a misconfiguration.

## Packaging

`loopky` is a **GraalVM `native-image` binary**: one file, no runtime, ~5 ms to answer `--version`
against the jar's ~50, and nothing installed on the machine it lands on. That last property is the
one the whole thing is for — the audience is a cloud agent's sandbox, non-root, recreated per task,
configured by a setup script, and "install a JDK" is exactly what a one-line install cannot be.

Three things about it are worth knowing before changing anything here.

**JNA was the entire difficulty, and it is solved by three files.** `libpubkycore` ships *inside*
the jar under JNA's resource layout and `Native.load` extracts it at runtime — reflective resource
loading, which is the one thing a closed-world image must be told about explicitly. The
registrations live in `src/main/resources/META-INF/native-image/`, with a README of their own
explaining where they came from and how to regenerate them.

**The binary has to be one file, and that is checked rather than remembered.** `native-image` does
not fail when it cannot fold a JDK native library into the executable; it emits the library beside
it and reports success. Anything reaching `javax.imageio` pulls in AWT and adds `libawt.so`,
`libawt_headless.so` and `libawt_xawt.so` — an X11 library, in a sandbox with no display — and the
one-line install is quietly dead with a green build behind it. `:cli:checkNativeImageIsOneFile`
fails the build instead. It has caught this twice already: the QR PNG writer (now ~30 lines of
chunk-and-CRC in `TerminalQr`) and the Koin binding for `MediaProcessor` (now
`PassThroughMediaProcessor`, since a card's picture here is a URL and nothing is ever decoded).

**`-march=compatibility`, not the default.** `native-image` targets x86-64-v3 unless told
otherwise, which needs AVX2; this binary is *downloaded*, onto a sandbox whose CPU nobody chose,
and a v3 binary on a host without it dies with SIGILL. Irrelevant to a client that spends its life
waiting on a homeserver.

**Windows builds as one `loopky.exe`, and it needs the Visual C++ redistributable.** CI compiles the
image on `windows-latest` on every PR; publishing it as a release asset is what is still missing
(#301). The redistributable is a **stated gap rather than an oversight**: `VCRUNTIME140.dll` and
`VCRUNTIME140_1.dll` are the only two of the binary's 23 imports that are not in-box on Windows 10+,
the `api-ms-win-crt-*` entries being the Universal CRT, which is. Without those two Windows refuses
to start the process and names the missing DLL, rather than failing somewhere inside loopky. Install
it once from [Microsoft](https://aka.ms/vc14/vc_redist.x64.exe); most machines already have it,
which makes this fail for the unlucky rather than for everyone.

**That link is the unpinned "latest supported v14" one on purpose.** Microsoft's rule is that the
installed redistributable must be the same version as the MSVC build tools that produced the
executable **or later** — and this binary is linked by whatever toolset the `windows-latest` runner
ships, 14.51 at the time of writing. A version-pinned permalink such as `aka.ms/vs/17/release/…`
currently serves 14.44, so a reader who followed it would install a runtime that is *older* than the
one required and be refused anyway, having done what the documentation asked.

It is not fixable here. GraalVM's prebuilt Windows JDK libraries are compiled against the *dynamic*
CRT, `--static` and `-H:+StaticExecutableWithDynamicLibC` are Linux-only, and
`-H:NativeLinkerOption=/MT` is rejected outright (`LNK1146`) because `/MT` is a `cl.exe` switch
rather than a `link.exe` one — forcing the static CRT underneath GraalVM's own objects would link
two CRTs, with two heaps and two `FILE*` tables, into one image. So CI pins the **whole** import
list: a new dependency fails the build in either direction, redistributable or not, instead of
arriving unnoticed in an artifact that a runner with the redistributable installed cannot test.
