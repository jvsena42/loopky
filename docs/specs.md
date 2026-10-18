# Loopky — Deck Import Spec (Paste-to-Import)

> **Scope:** deck import only — the paste flow, the parser, triage and publish. Everything else
> the app does (study/SRS, discovery, tags, profiles, settings, signup) is documented in
> [`docs/Architecture.md`](./Architecture.md), not here.
>
> §6–§9 are prescriptive and are the test matrix the parser suite is written against.

---

## 1. Summary

Paste-to-Import is **the primary way users build decks in Loopky v1**. The user pastes any structured or semi-structured text — a vocab list, a Notion table, a spreadsheet column, a chat message, a glossary — and Loopky turns it into cards. Loopky auto-detects the separator, shows a live preview as real flip-cards (not a table), and hands the parsed cards to a swipe-based triage queue before committing them to a deck.

It is inspired by Anki's "Import File" pattern but rebuilt for touch: no file pickers, no column indexes, no desktop-era settings dialog. The user pastes, glances at the preview, taps import.

**Who it's for:** Every Loopky user. Language learners pasting vocab lists, students pasting glossaries, hobbyists pasting their own notes. No technical literacy required.

**Why this rather than a file picker:** it's instant, offline, free, private, and covers the single most common import shape — two columns of text the user already has somewhere. File import (`.txt`, `.csv`, `.apkg`) has since shipped alongside it and reuses this parser; AI and OCR (§14) would too.

---

## 2. Goals

- **One-tap primary import:** Users should reach the paste screen in ≤2 taps from Decks or the empty state.
- **Zero-config in the common case:** Auto-detect the separator correctly for ≥90% of pastes. The user should rarely need to touch the override.
- **Preview as cards, not a table:** Show the first 3 parsed items as real Loopky flip-cards so the user feels the result before committing.
- **Triage before commit:** Every *pasted* card passes through the swipe queue — the user approves, edits, or discards each one before it enters the deck. Bulk file import goes to a summary screen instead (§5.4).
- **Native feel:** iOS and Android each use their own native components — SF Pro / Roboto, system sheets, platform haptics on successful import.
- **Accessible:** Full VoiceOver / TalkBack support, dynamic type, reduce-motion alternatives.

## 3. Non-goals (v1)

Explicitly deferred:

- AI-generated cards (paste article → cards)
- OCR / photo-of-page import
- Audio recording during import
- Image-folder bulk import
- Drag-and-drop (desktop only — Loopky is mobile in v1)
- Re-import / sync with an external source
- Private decks of any kind (see §11)

**Shipped since, having started here as non-goals.** All four reuse this spec's parser and commit
flow rather than a second pipeline, which is why they were cheap enough to bring forward:

- File import from device storage — `.txt` / `.csv` (§14)
- `.apkg` Anki file import, via the bulk summary screen instead of triage (§5.4, §14)
- Cloze deletion syntax (`{{c1::…}}`), expanded into cards at parse time
- Per-column field selection, on the bulk path only (§5.3)

---

## 4. User stories

1. **Vocab list from a textbook.** A Spanish learner pastes 40 lines of `palabra — word` from their notes. Loopky detects the em-dash separator, previews three cards, they swipe through the triage queue and commit. Total time: under a minute.
2. **Notion table.** A student copies a 2-column Notion table into Loopky. Loopky sees the pipe-delimited markdown table, parses it, previews, done.
3. **Hand-typed quick list.** A user types `capital of France: Paris` and a few similar lines directly into the paste box. Loopky auto-detects `: ` and parses each line into a card.
4. **Spreadsheet with extra columns.** A power user pastes three tab-separated columns: front, back, tags. Loopky uses the first two as the card front and back and drops the third — cards have no tags. They add the deck's tags on the commit screen.
5. **Re-opener.** A user who already imported once taps "+" on Decks. The paste screen is empty and focused on the text field. No history, no drafts — each import is a fresh canvas.

---

## 5. UX flow

### 5.1 Entry points

Three ways to reach the paste screen:

- **Decks tab → floating "+" → "Paste to import"** (primary)
- **Decks empty state** — the CTA under the illustration reads *"Paste a list to get started"*
- **Deck editor → "Add cards in bulk"** — imports into an existing deck instead of creating a new one

All three routes land on the same paste screen.

### 5.2 Paste screen

- **Top:** platform-native nav bar. Title: *"Paste cards"*. Left: cancel. Right: disabled *"Next"* button until text is pasted.
- **Body:**
  - A large rounded text field with the hint *"Paste your list here — one card per line"*. The field auto-focuses and auto-expands as text grows.
  - Below the field, once text exists: a **detected-separator chip** reading e.g. *"Detected: em-dash · tap to change"*. Tapping opens a bottom sheet with the override options (§6).
  - Below the chip: **live preview** — the first three parsed items rendered as real Loopky flip-cards using the existing Card component. The user can tap any preview card to flip it and see the back. The preview updates within ~150 ms of any change to the text or separator.
  - A subtle counter: *"42 cards will be imported"*.
- **Sticky footer:** *"This deck will be public on your Pubky"* notice (see §11) + primary *"Next"* button.

### 5.3 Extra columns

**On the paste path there is no column mapping UI.** The first column is always the card front and
the second is always the back; anything past the second is dropped at parse time. Nothing on the
paste screen mentions columns, so nothing claims a behavior it does not have.

**Bulk file import is the exception.** An Anki export names its fields, so `ImportFieldPicker` lets
the user choose which two become front and back. That only became possible because the file carries
field names; a paste does not.

Per-column *role* assignment (`Front / Back / Tags / Ignore` chips) is still unbuilt on both paths —
it is blocked on cards gaining a `tags` field, which they do not have (see §8, §14).

### 5.4 Triage queue

Tapping *"Next"* hands the parsed cards to the triage queue:

- Full-screen swipe interface. One card at a time, rendered with the full Card component (including flip).
- **Swipe right:** keep. **Swipe left:** discard. **Tap edit icon:** inline edit front / back without leaving the queue.
- Progress indicator at top: *"12 of 42"*.
- Skip-to-end shortcut: *"Approve all remaining"* in the nav bar for users who trust their paste.
- Haptic tick on each swipe (iOS `UIImpactFeedback.light`, Android equivalent).

**Triage is for interactive imports only.** It is card-at-a-time by design, which is right for a
40-line paste and wrong for a 20,000-card Anki export — nobody swipes through one. Bulk file import
(§14) therefore skips the queue entirely and goes to a **summary screen**: *"18,432 cards parsed ·
209 skipped"*, a sample of the first few rendered as cards, the detected separator, and one confirm
button. Same parser, same commit flow, no per-card decision.

The rule of thumb: if the user chose the rows, they triage them; if a file chose them, they get a
summary. This narrows §14's original claim that triage is the spine every import source plugs into.

### 5.5 Commit

- On triage completion, a confirmation screen: *"42 cards ready. 3 discarded."* + deck metadata form (title, description, cover image, tags). This is the only place tags are entered — they describe the deck, not individual cards (§8).
- Primary action: *"Publish deck"* (see §11 on why it's "publish," not "save").
- Success: celebratory micro-animation, haptic success, land on the new deck detail screen.

### 5.6 Undo window

A snackbar appears on the deck detail screen for 10 s after commit: *"Deck published. Undo"*. Tapping undo deletes the deck from the user's homeserver and returns to the paste screen with the original text still in the field.

---

## 6. Separator detection rules

The parser tries rules **in this order** and stops at the first rule that produces a consistent parse (≥80% of non-empty lines parse cleanly):

1. **Markdown table** — lines starting with `|`, optional `---` divider row
2. **Blank-line-separated pairs** — `Q\nA\n\nQ\nA` pattern
3. **Tab** (`\t`)
4. **Semicolon** (`;`)
5. **Pipe** (`|`) outside of markdown tables
6. **Em-dash with spaces** (` — `) or en-dash (` – `)
7. **Colon with space** (`: `)
8. **Comma** (`,`) — last resort; risky for natural-language answers containing commas
9. **Fallback: single column** — treat each non-empty line as front-only; back is empty, user fills it in triage

The user can always override via the detected-separator chip. The override sheet lists: `Auto`, `Tab`, `Semicolon`, `Pipe`, `Em-dash`, `Colon`, `Comma`, `Blank line`, `Custom…` (opens a single-character input).

---

## 7. Parsing rules

- **Quoting:** double-quoted fields support embedded separators and newlines, CSV-style. `""` escapes a literal quote.
- **Trimming:** leading and trailing whitespace stripped from every field.
- **Blank lines:** ignored unless the active rule is blank-line-separated pairs.
- **Max paste size:** 10,000 characters OR 2,000 cards, whichever comes first (`MAX_CHARS` / `MAX_CARDS` in `ImportRepositoryImpl`). Over that, show a soft error: *"That's a lot. Try splitting into smaller decks for now."*
- **Dedupe:** exact-match duplicates within a single paste are collapsed silently. Near-duplicates are not touched in v1.
- **Character encoding:** UTF-8 only. Paste buffer is read as UTF-8; invalid sequences are replaced with `�` and the affected line is flagged in triage.
- **Line endings:** `\r\n`, `\n`, and `\r` all normalized to `\n`.

---

## 8. Column handling

- **Fixed roles:** col 1 → front, col 2 → back. Always. There is no mapping UI and no way to reassign a column (§5.3).
- **Extra columns are dropped** at parse time, silently. A `front⇥back⇥tags` spreadsheet or Anki export imports cleanly as front/back; the tags column is discarded.
- **Tabs split every column.** Tab is a genuine column delimiter — spreadsheet and Anki exports use it and it effectively never appears inside card text — so a tab line splits on every tab and keeps the first two fields. Every other delimiter splits on its **first** occurrence only, so delimiters inside the back are preserved (§9: `date: December 25: Christmas`).
- **Cards carry no tags.** Tags are deck-level: they live on the deck manifest, are entered on the commit screen (§5.5), and are written via Pubky's native tag primitive (§11). The card record has no `tags` field — see [Architecture.md §8.0](./Architecture.md#80-homeserver-layout-canonical).

---

## 9. Edge cases & errors

| Case | Behavior |
|---|---|
| Single-column paste | Treat every line as front-only. Back is empty, user fills in triage. Show a banner: *"No separator found. Each line became a card front — fill in the backs in the next step."* |
| Spaced semicolon or pipe (`cat; gato`, `cat \| gato`) | Detected like the unspaced form — it is how most people type a pair. Prose is still single-column: lines that mostly use the delimiter twice (`ls -la \| grep .txt \| head`) or put three-plus words on both sides of it (`She ran quickly; he walked slowly`). A comma followed by a space stays punctuation. |
| Three or more columns | Only the first two are used, as front and back. Extra columns are dropped silently — nothing in the UI mentions columns, so nothing is claimed about them (§8). |
| Mismatched row lengths | Rows with fewer fields than the majority show as red in preview with a warning icon. User can edit or discard in triage. |
| Paste over max size | Soft error (§7). "Next" button disabled. |
| Pasted nothing but whitespace | "Next" stays disabled. No error. |
| Invalid UTF-8 | Replace with `�`, flag the line, let the user fix or discard in triage. |
| RTL text (Arabic, Hebrew) | Fully supported. Preview cards render RTL per device locale. Separator detection is unaffected. |
| Very long single card (>2000 chars on one side) | Card commits, but triage shows a *"Long card"* flag. Text is not truncated. |
| Duplicate cards within paste | Silently deduped before preview. Counter reflects the deduped total. |
| All lines are identical | One card after dedupe. Warning in triage: *"Just one unique card in that paste."* |

---

## 10. States

Every state below has a screen.

1. **Empty** — no text pasted. Text field auto-focused. Preview area shows a faint illustration and *"Your cards will show up here as you paste."*
2. **Paste detected** — text just landed. Separator chip and preview animate in.
3. **Preview loading** — parser running. Preview cards show skeletons (~150 ms max).
4. **Preview ready** — first three cards rendered. Counter populated. "Next" enabled.
5. **Parse error** — paste is unparseable or over-size. Red banner, "Next" disabled, suggestion offered.
6. **Triage in progress** — full-screen swipe UI.
7. **Importing** — triage done, cards being written to the homeserver. Progress indicator.
8. **Success** — confirmation screen with counts.
9. **Undo window** — snackbar visible on deck detail for 10 s.
10. **Post-undo** — user is back on the paste screen, text preserved.
11. **Network error on commit** — toast: *"Couldn't reach your homeserver. Try again?"* with retry. Triage decisions preserved.

---

## 11. Pubky considerations

- **No private decks in v1.** Every imported deck is published to the user's Pubky homeserver and is discoverable via Discover and the user's profile.
- The paste screen and commit screen must both show a plain-language notice: *"This deck will be public on your Pubky."* — no fine print, no tooltips, no toggle.
- The primary commit button reads **"Publish deck"**, not "Save deck" or "Create deck." The word choice matters: it tells the user something public is about to happen.
- Deck tags entered on the commit screen (§5.5) are written via Pubky's native tag primitive, not a custom Loopky system. Cards carry no tags of their own.
- The author field on the published deck resolves to the user's pubky identity — a public key, shown truncated and copyable, never with the legacy `pk:` prefix.

### 11.1 Published deck shape

A published deck is not one record. It is a manifest, a set of card **chunk** records (~100 cards
each), and media blobs keyed by sha256:

```
/pub/loopky/decks/{deckId}/manifest.json      — metadata + chunk table
/pub/loopky/decks/{deckId}/cards/{n}.json     — a batch of cards
/pub/loopky/decks/{deckId}/media/{sha256}.{ext}
```

The two consequences that reach *this* spec: the layout imposes no ceiling on deck size, so the
paste cap in §7 is a property of the paste box and the swipe queue, not of storage; and publish is
~201 writes for a 20k-card deck rather than 20,001, which is what makes the bulk path in §5.4
viable at all.

Everything else about it — the chunk table, why the manifest carries no card index, `ord` as study
order, sync by `updated_at`, last-write-wins — belongs to storage rather than to import.
See [Architecture.md §8.0](./Architecture.md#80-homeserver-layout-canonical) for the full JSON
schemas and §8.4 for the numbers that forced the layout.

---

## 12. Accessibility

- **VoiceOver / TalkBack:** Every preview card announced as *"Card 1 of 3 preview, front: …, double-tap to flip."* Separator chip announced as *"Separator: em-dash. Double-tap to change."* Triage swipes exposed as explicit *"Keep"* and *"Discard"* buttons for switch-control and non-gesture users.
- **Dynamic type:** Paste box font and preview card text both scale with OS setting. At the largest setting, the preview switches from 3 cards to 1 card.
- **Reduce Motion:** Triage swipe becomes tap-to-decide with Keep / Discard buttons. Flip animation in preview becomes a crossfade.
- **Colorblind:** Any status color in the preview (red for mismatched rows, green for the dedupe badge) is paired with an icon and text label.
- **Tap targets:** The separator chip is ≥44×44 pt iOS / 48×48 dp Android.
- **Contrast:** Paste box text, separator chip, and banners meet WCAG AA on both themes.

---

## 13. Open questions

Four of the original six were settled by shipping, and are recorded as decisions rather than
questions: deck metadata is collected after triage; "Approve all remaining" shipped without a
swipe-through gate; the deck size cap moved to the paste box (§7) once the storage layout stopped
capping decks; and private decks are not a v1 feature (§11) — the code has no visibility toggle and
no local-only write path.

Genuinely open:

1. **Image and audio in paste** — out of scope, but does the parser need to gracefully ignore
   pasted image data so the user isn't blocked? Likely yes.
2. **Undo after leaving the screen** — if the user leaves deck detail before the 10 s timer expires,
   the undo vanishes. Probably acceptable; matches standard snackbar behavior.

---

## 14. Future extensions

These reuse this spec's *parse → preview → commit* spine — not necessarily its swipe queue (see the note at the end):

- **AI "Paste anything"** — user pastes an article or transcript, Claude/Gemini extracts Q/A pairs, the result feeds straight into the same triage queue. No new commit UI needed.
- **OCR photo import** — textbook page → Vision / ML Kit text → same triage queue.
- **URL import** — paste a YouTube or article URL, fetch + AI-extract, same triage queue.
- ~~**Anki `.txt` import**~~ — **shipped.** Anki's "Notes in Plain Text" export is tab-separated, which the existing parser already handles (§6 rule 3), so it needed no new dependencies. Routes through the bulk summary screen, not triage.
- ~~**`.apkg` import (after that)**~~ — **shipped, and cheaper than estimated.** The estimate assumed three new dependencies (a KMP zip reader, a SQLite driver, zstd). Android has `java.util.zip` and `android.database.sqlite` in the platform, so it needed none of them. The collection's `notes` table is unpacked to tab-separated text and fed through the same parser and summary screen as the `.txt` path — no second pipeline. Not handled, and reported rather than failed on: `collection.anki21b` (zstd, Anki 2.1.50+) and iOS, which has no platform zip or SQLite exposed to Kotlin/Native. Both point at the plain-text export.
- **Per-column field mapping + per-card tags** — the `Front / Back / Tags / Ignore` chip row from the original spec, so a third column can become card tags instead of being dropped (§8). Blocked on a schema change: the card record has no `tags` field, and adding one touches `Card`, `CardDto`, and the homeserver layout. Tracked in issue #45.
- **Private decks + public/private toggle** — would need a local-only write path on `DeckRepository` as well as the toggle; §11 is the reason there is neither today.
- **Drafts** — save an unfinished paste if the user cancels, restore on next entry.

The **commit flow** is the reusable spine; every future import source plugs into it.

> **Not the swipe queue.** Card-at-a-time triage does not scale to a 20k-card Anki export, and no one is going to swipe through one. What every source genuinely shares is the parse → preview → commit spine and `DeckRepository.publish`. Interactive sources (paste, and probably OCR) keep triage; bulk sources get the summary screen in §5.4.

---

*End of spec.*
