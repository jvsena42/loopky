# Journey results

Android runs first, then an iOS section, then the CLI at the foot of the file.

## Android

Run against the Loopky debug APK on the `emulator-5558` Pixel emulator with Pubky Ring
installed (staging identity), 2026-06-13.

## 01 — Onboarding / Pubky Ring auth — ✅ PASS

| Step | Result |
| --- | --- |
| Launch Loopky, onboarding shown with "Sign in with Pubky Ring" | PASSED |
| Tap sign in → Pubky Ring opens with the authorization prompt | PASSED — staging identity, relay `httprelay.pubky.app/inbox`, capabilities `/pub/loopky/:rw` + `/pub/pubky.app/:rw` |
| Approve in Ring | PASSED — "Authorization Successful" |
| Loopky completes sign-in | PASSED — token decrypted → session exchange → session saved → Home shown greeting the real pubky (`pk:x1kwaq`). Session persists across restarts. |

**Earlier blocker (now fixed):** sign-in failed instantly with "auth request expired".
Root cause was a panic in the pubky SDK's HTTPS client — its `icann_http` client used
reqwest's default rustls config (rustls-platform-verifier), which on Android panics
("Expect rustls-platform-verifier to be initialized") because the native verifier component
isn't present. The panic killed the auth-relay poller, surfacing as `RequestExpired`.
Fixed by making the SDK pin bundled webpki roots for ICANN TLS (pubky/pubky-core#430),
consumed via the FFI fork's `[patch]`. Surfaced by adding logcat tracing + a panic hook to
the FFI (`init_logging`).

**Auto-return caveat:** Loopky appends Ring's `x-success`/`x-cancel`/`x-error`/`x-source`
callbacks (→ `loopky://login-callback`, `MainActivity` = singleTask) so Ring re-opens Loopky
after approval. On the installed Ring build the success screen shows an "OK" button and does
not fire `openXSuccess`, so the user taps back to Loopky manually — a Ring-side issue, not Loopky.

## 02 — Paste-to-Import → triage → publish — ✅ PASS

Re-verified on `emulator-5554` 2026-06-17 after adding the triage step + card options.

| Step | Result |
| --- | --- |
| Decks tab → "Paste to import" | PASSED |
| Paste comma lines | PASSED — parser auto-detected "comma", live preview |
| Next → **Review cards** triage | PASSED — `triage_card`, `triage_progress` "1 of 3", keep advances "2 of 3 · 1 kept", keeping the last card advances to Publish |
| Publish screen → **Card Options** | PASSED — Listen (`publish_listen_toggle`) + Speak (`publish_speak_toggle`) both ON by default |
| Publish deck | PASSED — "Deck published! … Undo (7s)" + Done; `listen_enabled`/`speak_enabled` serialized into the manifest |
| Done → deck detail | PASSED — deck "Sky", Total 2 / Due 2, In Your Library |

Re-run on `emulator-5554` 2026-09-08 for the library loading-state fix, end to end: paste three
comma lines → "Detected: comma" and a 3-card preview → triage keeps → publish "Animals PT" →
"Deck published! … Undo (5s)" → Done → share prompt dismissed → deck detail (Total 3 / New 3) →
back to the grid, which listed "Animals PT" without a restart.

**One step of the script is stale, and it is not this change.** It asserts Listen and Speak are
both ON by default on the publish screen; both are OFF, which is what the language-pair rule
requires — turning one on obliges the author to declare `frontLang`/`backLang`, so the opt-ins
cannot default on. The XML still says otherwise.

### The library no longer hides its import controls while it loads

`DecksScreen` used to swap its whole body for the full-screen loader, so a first launch — the only
time there is no cached library to paint — put the paste CTA and the file import behind a spinner.
Caught by wiping `shared_prefs/loopky.preferences.xml` (`run-as … rm`, which drops `deck_cache`)
and bursting `screencap` over the cold load; the window is ~1.5 s, too short for `android layout`
to poll.

| Width | Result |
| --- | --- |
| Portrait (compact) | ✅ header, "Paste to import" and "Import from Anki or a file" all rendered, dots + "Shuffling your decks…" where the grid goes |
| Landscape (expanded, nav rail) | ✅ same, inside the `PaneWidth.Wide` cap |
| Tapping the paste CTA mid-load | ✅ opened "Paste cards" — visible *and* usable, not just painted |

The `Pixel_Tablet` AVD could not cover this: it has no session and signing one in needs Pubky
Ring, so the expanded-width check was made by rotating `Medium_Phone` (914dp landscape).

## 03–06 — runnable

The study loop, discover, deck manage/delete, and profile/settings/sign-out journeys remain
runnable on the emulator.

## 07 — Triage edit — ✅ PASS on iOS (2026-09-01); still not re-run on Android

`journeys/07-triage-edit.xml`: edit a draft card's front/back in triage, keep, publish, and
confirm the edit persisted. **Scripted 2026-06 and never actually run until now**, which is how
two bugs sat in it — see the #193 follow-ups section at the end of this file. Driven on the
iPhone 17 Pro simulator after those fixes; the Android script is still owed a run.

## 08 — Image select — ✅ PARTIAL PASS

`journeys/08-image-select.xml`. On `emulator-5554` 2026-06-17: the cover sheet opens from
`publish_cover_change` with `image_search_input` + `image_pick_gallery`; since
`UNSPLASH_ACCESS_KEY` is blank in `local.properties`, the web grid shows the gallery-only hint
("Search the web or pick from your gallery") as designed. The gallery path uses the system photo
picker and is a manual check. Sheet test-tags surface correctly (the sheet content sets
`testTagsAsResourceId` since `ModalBottomSheet` renders in a separate window).

Re-run on `emulator-5554` 2026-08-28 for the pasted-link/pasted-image work, with a key
configured this time:

| Step | Result |
| --- | --- |
| Sheet shows the field, "From gallery" **and** "Paste" (`image_paste`) | PASSED |
| Typed address → grid replaced by `image_link_preview`, credit line gone, Done enabled once drawn | PASSED — `images.unsplash.com/photo-…` rendered in the pane |
| Typed address that is not an image (`example.com/not-an-image`) | PASSED — `image_link_error` shown, `image_sheet_done` stayed disabled |
| `image_link_clear` → field empties, grid and credit line come back | PASSED |
| Paste with an address on the clipboard | PASSED — address landed in the field and previewed; Done committed it as the card's front image |
| Paste with plain text on the clipboard | PASSED — stayed a search term, grid kept, no preview |
| Front pick must not carry into the back sheet | PASSED after the fix — back sheet opens empty, grid back, Done disabled. It failed before it: the sheet's ViewModel is the screen's, and the front's address was still there, committable |

**Pasting an image as bytes is out of scope for now — issue #168.** Reading a Chrome "Copy
image" clip froze the app: `ClipData.Item.coerceToText` on a `content:` uri opens and reads the
stream rather than formatting the uri, and it was being called on the main thread. Such a clip
is now identified from local clip metadata alone and refused with a message, with no provider
call at all. The bytes path itself could not be exercised here in any case — Chrome's "Copy
image" wedged this emulator four times, including once with Loopky not in the foreground and
Paste never pressed, so the AVD and not the app is what fails there. The empty-clipboard notice
is also unverified: `cmd clipboard clear` does not exist on this image.

Re-run on `emulator-5554` 2026-08-14 **with a real `UNSPLASH_ACCESS_KEY`** — the first time the
web grid path has actually been exercised. The grid populates, every cell shows its photographer
over a gradient scrim, and `image_credit` reads "Photos from Unsplash" before a selection and
"Photo by NIR HIMI on Unsplash" after one. Both links open Chrome at
`unsplash.com/@nirhimi?utm_source=loopky&utm_medium=referral` and
`unsplash.com/?utm_source=loopky&utm_medium=referral` respectively, and Done still applies the cover.
The `download_location` ping is covered by `UnsplashClientTest` (exact URL + auth header) rather
than observed on the wire.

## 09 — Speak study — ✅ PARTIAL PASS

`journeys/09-speak-study.xml`. On `emulator-5554` 2026-06-17: studying the speak-enabled "Sky"
deck shows the back-card `study_speak` button; tapping it raises the Android RECORD_AUDIO
permission dialog at the right time. This emulator image has no on-device speech recognition
service, so `SpeechRecognizer` reports unavailable and the flow returns to the card without
crashing — the Listening/Correct/Wrong outcome is a manual check on a device with Google speech.

**Re-run 2026-09-04, `emulator-5554`, after the silent-close fix.** Reported symptom: the Speak
sheet sometimes closed without showing a success or an error. Both halves reproduced here on the
"Spanish basics" deck (es-ES → en-US, Speak on):

| Step | Result |
| --- | --- |
| Tap **Speak** → rationale → RECORD_AUDIO grant | PASSED — unchanged |
| Listening sheet | PASSED — `speak_mic` + "Buenos dias" |
| Say nothing, wait | PASSED — sheet now shows **"Didn't catch that"** (`speak_failed_title`) with the reason and **Try again**. It used to sit on "Say the word" for good: this emulator's recognition service reports `onReadyForSpeech`/`onBeginningOfSpeech` and then never returns a result *or* an error, so nothing ended the listen |
| **Try again** from the failure sheet | PASSED — straight back to Listening, no `ERROR_RECOGNIZER_BUSY`; a second failure reports the same way |
| Dismiss | PASSED — back to the card |

Still a manual check on a device with Google speech: the Correct/Wrong outcome, and which language
each engine actually uses. The `LanguageUnavailable` sheet (Close instead of Try again) was not
reachable here — this image raises no language error.

## 10 — Listen/Speak fixes + design polish — ✅ PASS (re-run 2026-06-17, emulator-5554)

Verified after the Listen/Speak + fidelity fixes (RECORD_AUDIO revoked first to test the fresh
grant path):

| Step | Result |
| --- | --- |
| Study front card | PASSED — word + "Tap card to reveal answer" only; **no Listen/Speak on the front** (superseded — see journey 12) |
| Reveal back | PASSED — **"Listen"** pill (peach) + **"Speak"** pill (purple); names + colors now match the design (previously both read "Speak") |
| Tap **Speak** → mic permission | PASSED — "Allow Loopky to record audio?" dialog appears (the user-reported "permission not requested" bug is fixed) |
| Grant → recognition unavailable | PASSED — Toast "Speech recognition is unavailable on this device" shows instead of silently doing nothing |
| Paste preview (`MJ1SR`) | PASSED — bottom orange **Next** button shown once parsed |
| Publish | PASSED — peach "N cards ready" badge with solid orange check; white card fields; **Listen/Speak option rows have leading icons** (peach headphones / purple mic) |
| Cover image sheet | PASSED — **Done** is now a pill (disabled-grey until a selection) |

Speech recognition itself still needs a device/emulator with Google speech for the
Correct/Wrong outcome.

## 11 — Listen/Speak use the deck's language — ✅ PASS (2026-08-22, emulator-5554)

The point of the change is which *language* the engines get, and the emulator's device locale is
`en-US`, so a Spanish deck is exactly the case that used to be wrong.

| Step | Result |
| --- | --- |
| Study a deck published before the language pair existed | PASSED — card back shows **neither** Listen nor Speak, though its manifest carries `listen_enabled: true`. No fallback to the phone's locale |
| Open that deck in the editor | PASSED — the **Card Options** block is present (it did not exist on this screen before); both toggles read **off**, matching what the deck actually does |
| Toggle Listen on | PASSED — the two language rows appear with the hint "Listen and Speak need to know what language each side is in, or the phone reads the card in your own accent." |
| Tap **Save** with no languages picked | PASSED — save refused, stays on the editor, "Pick a front and back language to use Listen or Speak." shown under the pickers |
| Open the Front language picker | PASSED — populated from the **engine's installed voices** (`tts.availableLanguages`), not the fallback list: Arabic → Bodo → … → Spanish (Spain) |
| Pick front `en-US`, back `es-ES`, Save | PASSED — returns to deck detail; reopening the editor shows both languages persisted, so they round-tripped through the manifest |
| Study the deck again, reveal the back | PASSED — **Listen** appears. Speak stayed absent, correctly: the editor had loaded `speak_enabled` folded through `speechReady`, so only Listen was turned on |
| Tap **Listen** | **PASSED — the acceptance test.** logcat: `GoogleTTSServiceImpl: Synthesis request for locale spa-ESP and name es-ES-language`, on a device whose locale is `en-US`. Before this change `setLanguage(Locale.getDefault())` made that `eng-USA` |
| Enable Speak too, save, restudy | PASSED — card back shows both **Listen** and **Speak** |

Two things this run did **not** prove:

- **The recognizer's language.** This emulator image still has no speech recognition service
  (journey 09), so `EXTRA_LANGUAGE` reaching the recognizer is covered by unit tests and the
  ViewModel effect assertions, not observed on the wire.
- **The `loopky-lang-*` tag records.** The saves logged no `syncTags` failures, which is the only
  positive signal the client emits, but the records were not read back off the homeserver. The
  write/diff behaviour is covered by `DeckRepositoryTagSyncTest`.

Unrelated pre-existing noise seen during the run: `listFollowed: 3uyducpmnylw unreadable — 404`.

## 12 — Listen/Speak on both card sides — ✅ PASS (2026-08-22, emulator-5554)

Restores the rule that both card faces carry a Speak button, which an earlier mockup had
overridden — so the journey 10 row above no longer holds.

| Step | Result |
| --- | --- |
| Study front card | PASSED — **Listen** (peach) and **Speak** (purple) both present, with "Tap card to reveal answer" still below the card |
| Tap **Listen** on the front | PASSED — logcat `Synthesis request for locale eng-USA`, the deck's **front** language |
| Reveal the back | PASSED — both buttons still present |
| Tap **Listen** on the back | PASSED — logcat `Synthesis request for locale spa-ESP`, the deck's **back** language |

So each side is read in its own language rather than one tag being fixed per deck.

Pronunciation practice on the front targets the front text (unit-tested: grading it against the
back would mark every front attempt wrong), but as with journeys 09/11 the recognizer itself is
unexercised on this emulator image.

---

## UI/UX gap pass — 2026-08-13, `emulator-5554`, signed in as `pk:rc3omr…b4re3o`

Drove every flow end to end (onboarding → paste → triage → triage-edit → image picker →
publish → deck detail → editor → card editor → study → discover → friend profile → profile
→ settings → delete → offline) and fixed what it turned up. Correction to the notes above:
03, 05 and 06 all execute today — they were listed as merely "runnable".

### Fixed and re-verified on device

| Symptom seen while driving | Fix |
| --- | --- |
| Publish a deck → Decks tab says "No decks yet" until the process restarts | `DeckRepository.changes` invalidation signal; the deck now appears immediately |
| Delete a deck → grid still lists it; tapping it lands on "Deck not found" with **no back button**, only a Retry that can never work | list refresh, a back control on the error state, and Retry hidden when it cannot succeed |
| wifi/data off → Home shows "Nothing to study yet — create or import a deck", Decks shows "No decks yet" | `listByAuthor` no longer swallows transport failures into an empty list; both now show an error with retry |
| Home showed the zero-decks empty state after finishing a session | new "all caught up" state carrying the next due time |
| Onboarding printed `HTTP transport error: error sending request for url (https://httprelay.pubky.app/inbox/…)` verbatim | `ErrorReason` + per-platform copy; raw text stays in logcat |
| Sign-in failed twice then succeeded on an identical third attempt | bounded retry on the auth-relay poll, transport failures only — **wrong fix, reverted in #59; see the 2026-08-17 pass below** |
| Paste "Next" with an empty box and Publish with an empty title: nothing happened, no message | real disabled styling, CTAs enabled so validation can speak, Publish CTA pinned above the fold |
| Both Share buttons did nothing | one `Context.shareText` helper |
| Editor drag handle did nothing when dragged | move up/down buttons + real reorder (needed the card-order fix first); the handle now drags too |
| Decks search icon and "Recent" label were inert | wired to a real filter and sort |
| Separator chip looked tappable and was not | override sheet (spec §5.2) |
| Discarding a card in triage was irreversible | undo, including for the last card |
| Speak fired the mic prompt cold; permanent denial left it inert forever | rationale dialog + "Open settings" path |
| "1 cards" everywhere | `plurals.xml` — the app had **zero** plural resources |
| "Detected: em-dash" shown for a plain hyphen | label reads "dash" (`Separator.EmDash` buckets em-dash, en-dash and `" - "`) |
| Settings showed "Homeserver: Unknown" | resolved from the pkarr record; Ring's session payload has no `homeserver` field |
| Own pubky in add-friend → a live Follow button | self-detection |

### Found by code review, not by driving

**Saving the deck editor destroyed every card image, every audio clip and the deck cover.**
It rebuilt each card as `CardSide(text = …)` with `coverImageRef = null` and republished over
the manifest. Only reproducible on a deck that already has media, which is why no journey hit
it — hence the new `11-deck-editor-media.xml`. Also fixed: card order was `sortedBy { it.id }`
over random ids, so deck detail and the editor listed cards arbitrarily and reorder could not
have persisted.

### Journeys added

- `10-offline-errors.xml` — failures must not render as an empty library.
- `11-deck-editor-media.xml` — regression guard for the media-destroying save.
- `12-dead-controls.xml` — sweep of the controls that used to do nothing.

`02`, `04`, `05` and `09` gained assertions for the list-refresh, self-follow, delete-copy and
permission-rationale fixes. `05` could not have passed before this pass regardless of app
behaviour: `deck_delete_confirm` **was** set in code, but `AlertDialog` renders in its own
window so the nav-host root's `testTagsAsResourceId` never reached it. Same fix applied to the
add-friend sheet and both sign-out dialogs.

### Still manual / environment-limited

- Speech recognition outcomes: this emulator image has no on-device recogniser, so
  `SpeechRecognizer` reports unavailable and only the permission path is testable here.
- The system photo picker in `08` remains a manual check.
- The Pubky stack intermittently fails on this emulator with
  `Expect rustls-platform-verifier to be initialized` (the upstream issue noted above). It
  clears after toggling the radios. It classifies as a generic error rather than "offline" on
  purpose — it is a TLS-stack fault, and telling the user to check their connection would be
  misleading since retrying does not help.

---

## 01 re-run — auth-relay failure — 2026-08-17, `emulator-5554` (#59)

Re-ran journey 01 to verify #59. **The relay-failure branch passed; the happy path could not be
re-verified from a script** — see the flakiness note below.

### Verified

| Step | Result |
| --- | --- |
| Sign out → onboarding, radios off, tap sign in | PASSED — `beginSignIn` succeeds (the auth URL is generated locally), Ring opens |
| Loopky surfaces the real cause | PASSED — `complete: FAILED — PubkyError: Auth approval failed: … HTTP transport error … httprelay.pubky.app/inbox/y1N6EOpH…`. **No** `awaitApproval: transport failure … retrying`, **no** `No auth flow in progress` |
| On-screen copy | PASSED — "Loopky signs you in through Pubky's authorisation relay, and it isn't responding. Try again in a moment." |
| One-tap restart from the error state | PASSED — a single tap logs `state=Starting, calling beginSignIn` and mints a new secret; Ring reopens |

### Why the retry could never have worked

`await_auth_approval` in the FFI does `AUTH_FLOW.lock().take()` and
`PubkyAuthFlow::await_approval(self)` consumes the flow, so the first poll — success or failure
— tears it down. The 2026-08-13 row above credits the retry with a recovery that actually came
from the user re-tapping (a fresh `beginSignIn`). Independently, the SDK **already** retries the
poll: logcat under tag `pubkycore` shows
`Http relay inbox channel polling attempt 1/2/3 failed`. The app-level retry was redundant as
well as broken.

### The emulator flakiness, and what it is not

Six scripted sign-ins between 07:23 and 07:35 failed identically, every one ~5.9s after
`beginSignIn`. Ruled out: DNS resolves (`34.65.156.171`, **no AAAA record**, so not an IPv6
fallback stall), ICMP reaches the host, Chrome on the same emulator loads the relay and reports
"Connection is secure", no rustls panic in logcat, and a full `adb reboot` did not clear it.
From the host the inbox long-poll holds open ≥25s.

Then a **manual** sign-in at 07:39 succeeded on the same network — matching the report that
failures are rarer when a human drives it. So it is not the network configuration and not the
app build; the difference is something about scripted repetition (six fresh inbox channels in
twelve minutes, each failure opening another). Unexplained, and worth suspecting relay-side or
emulator-NAT limits before suspecting Loopky.

> **Superseded 2026-08-20 — it is the pacing, not the repetition.** See the note below.

**Consequence for anyone running 01: signing out is a one-way door on the emulator whenever the
relay is in this state.** Journey 01 now carries that warning and covers the failure branch.

---

## 01 — the "scripted sign-in fails" flakiness, explained — 2026-08-20, `emulator-5554`

The 2026-08-17 pass above left this "unexplained" and pointed at scripted *repetition*. On a
fresh session today, driving sign-in two ways back to back on the same network gives a cleaner
discriminator: it is the **delay between tapping sign in and approving in Ring**.

| Run | Cadence from `beginSignIn` to Authorize | Result |
| --- | --- | --- |
| 1 — verification between every step (layout dump, then tap) | ~15s | FAILED — "the authorisation relay isn't responding" |
| 2 — three taps chained, `sleep 2` then `sleep 1`, nothing in between | ~3s | **PASSED first try** |

That also explains the manual/scripted split the 08-17 note recorded without accounting for it:
a human taps through in a couple of seconds, and a journey runner that verifies each step does
not. It is consistent with the prior session's ~20 slow failures followed by a fast loop
succeeding immediately, and it means **journey 01's happy path cannot be driven by a runner that
dumps the layout between actions** — 01 now says so, with the chain and the coordinates.

Not proven to be a relay-side timeout — two runs is a discriminator, not a mechanism, and the
FFI's own poll (`Http relay inbox channel polling attempt 1/2/3 failed`) sits between the tap
and the error. But the actionable half holds either way: chain the taps, verify afterwards, and
on failure retry the whole chain rather than waiting inside it.

---

## 14 — File import — 2026-08-17, `emulator-5554`, signed in as `pk:bzbjrj…yhjzpo` (#55)

New journey `14-file-import.xml`, driving the presentation-layer work in #55. Fixtures already on
the device: `/sdcard/Download/japanese_core.apkg` (800 notes) and `anki_export.txt` (1,200 cards).

### Verified

| Step | Result |
| --- | --- |
| Decks CTA | PASSED — centred, tucked under the paste card, reads "Import from Anki or a file". It was left-aligned and stranded by the 20 dp section gap, saying only "Or import a file" |
| `.apkg` → summary | PASSED — "800 cards parsed", sample rows, `bulk_repick` present |
| Detected separator | PASSED — `bulk_separator_chip` reads "Separator: tab". The string `bulk_detected_separator` had existed unused since #49 |
| Separator override | PASSED — the chip opens **paste's** sheet (`separator_option` ×9); choosing "comma" re-parses to "0 cards parsed · 800 skipped" and disables Import |
| Skipped rows genuinely dropped | PASSED — the comma case is the regression in miniature: it reports 0 importable and blocks, where before the count stayed at 800 and `publish` then failed on the first empty side |
| Title prefill from `.apkg` | PASSED — `publish_title` opened as **"Japanese Core"**, the collection's own deck name. The file-name fallback would have given lowercase "japanese core", so this is the `decks` table being read, not the filename |
| Title prefill from `.txt` | PASSED — "anki_export.txt" → "anki export" (extension stripped, underscore to space) |
| Determinate publish progress | PASSED — `publish_progress` bar plus `publish_progress_label` observed advancing "Publishing 0 of 800 cards…" → "Publishing 800 of 800 cards…", with `publish_cancel` beside it. Previously an indeterminate spinner for the whole upload |
| 800-card publish | PASSED — deck detail showed "Japanese Core", Cards 800 |
| 1,200-card publish | PASSED |
| Wrong file type | PASSED — a `.png` gives "That's not a text file" + "Pick an Anki .apkg, or a deck exported as plain text", with a retry that reopens the picker. It used to decode to U+FFFD soup and parse into plausible junk cards |

### Not exercisable from a script: cancel during publish

`publish_cancel` renders and is reachable, but **the cancel itself could not be driven here**.
Against this homeserver an 800-card publish completes in roughly a second and 1,200 in not much
more, so the tap lands after the success screen has already replaced the button — `android layout`
costs about as long as the whole upload. It is not a defect in the control; there is simply no
window on a deck this size.

Covered by unit test instead (`PublishDeckViewModelTest.cancellingAPublishSweepsThePartialDeck`,
using a new `FakeDeckRepository.publishGate` to hold a publish open): cancel sweeps the partial
deck, leaves `isPublishing`/`isCancelling` false, and sets **no** error — the last of those being
the real hazard, since `DeckRepositoryImpl.publish` is a `runCatching` and hands a cancellation
back as an ordinary failure. Re-check by hand on a 20k-card import, where the window is tens of
seconds.

### Notes

- The app shows onboarding for a beat on cold start before the persisted session resolves. Not a
  regression — worth knowing, because a layout dump taken too early reads as "signed out".
- The 429-flakiness and rustls faults recorded above did not recur in this session.

## 15 — Pubky links — ✅ PASS

Run against the debug APK on `emulator-5554`, signed in as Cosmic-Crystal-Panda
(`rc3omr…b4re3o`), following Silver-Otter-Sparrow (`bzbjrj…yhjzpo`), 2026-08-18.

| Step | Result |
| --- | --- |
| `VIEW pubky://<their-pubky>` | PASSED — Silver-Otter-Sparrow's profile, with Follow + Copy + Share and a People card reading Following 1 / Followers 1 |
| Their Following list | PASSED — opens from the stat column and lists Cosmic-Crystal-Panda; tapping the row opens that profile |
| `VIEW pubky://<own-pubky>` | PASSED — "You" badge, no Follow button, Copy and Share centered on their own row |
| `VIEW …/decks/<deckId>/manifest.json` | PASSED — deck detail for "Japanese Core", 800 cards |
| Cold start via link | PASSED — force-stopped, then the link restored the session and still landed on the profile. Back returns to Home, so the link opens on top of the tabs rather than instead of them |
| Share own profile | PASSED — share sheet carries `Cosmic-Crystal-Panda on Loopky` above `pubky://rc3omr…`, not a bare key |
| Share sheet → Loopky | PASSED — Loopky is a target for `text/plain` and opens the profile the message points at, which is the path that matters since chat clients leave `pubky://` unlinkified |
| Shared text with no address | PASSED — toast "No Loopky link in that text…" rather than opening silently on whatever screen was already there |
| Deck link pasted into add-friend | PASSED — opens the **deck**. Previously the sheet sliced the URI by hand and could only ever reach its author |
| Free text pasted into add-friend | PASSED — inline `add_friend_error` instead of a tap that does nothing |

### Not exercised here

Deep link **while signed out**: the pending link is held until the nav host leaves onboarding, so
it should open right after sign-in. Not driven on device — this account stays signed in and
re-auth through Ring is flaky on the emulator (see journey 01).

### Pre-existing, unrelated to this change

`decksFromFollowing` logs `listByAuthor failed for pubkybzbjrj9a…hjzpo — unexpected 'pubky' prefix
in user id`. One follow record on this account's homeserver is keyed with a stray `pubky` prefix in
front of the z32 id, so it can never resolve; `following()` passes it through verbatim. Harmless to
the strips (the other followee still loads) but it inflates the follow count and spams the log.
Worth a separate fix — either dropping ids that are not shaped like a pubky when parsing the
follows listing, or finding whatever wrote that record.

---

## #147 — Local keys, recovery restore, and backup (2026-08-26)

Driven on the Pixel_9 emulator against **staging**. Sign-out between runs via
`adb shell pm clear com.github.jvsena42.loopky`.

| Check | Result |
|---|---|
| Third door present on onboarding | PASSED — `onboarding_restore` renders below the two existing entry points |
| Restore with a valid phrase | PASSED — signs in and lands on home |
| One DHT lookup per attempt | PASSED — was two (~3s each); the pre-flight answer is now passed into sign-in |
| Bitcoin-seed warning | PASSED — permanent inline block above the field, not dismissible |
| Valid phrase with no account | PASSED — "This key has no account yet", derived pubky shown, "Check my recovery phrase again" ranked above "Register this key" |
| Ring-missing → local route | PASSED — `signup_create_locally_option` unlocks every method with Ring **disabled**, and prices are quoted |
| Fiat quote | PASSED — `Pay ₿ 10 (≈ US$0.01) once` on staging |
| Price test tags | FIXED — `signup_method_*_price` never existed; the tag was written as a literal `$testTag_price` |
| Landscape onboarding | FIXED — the third button pushed the policy consent (which gates the primary button) off the bottom of the bounded two-pane panel, clipping in silence. The panel now scrolls |
| Medium width (700dp) | PASSED — stacked layout, every control reachable |

### Found only on device

**The grant auth flow does not work against Synonym's staging homeserver.** A first test key
happened to succeed, so it was briefly believed verified; a second produced
`403 Forbidden - Writing to directories other than '/pub/' is forbidden` from the grant flow's
`export_grant_session_secret`. `signIn`/`signUp` now bind to the **cookie** variants, which sign in
cleanly and answer an unregistered pubky with an honest 404. Revisit with #130.

**A pkarr homeserver record can outlive the account it points at.** `getHomeserver` answered
*Registered* for a key the homeserver then 404'd at signin — so the pre-flight cannot rule this out,
and the 404 has to be classified as "no account" on the restore path. It was rendering
`ErrorReason.NotFound`, whose copy is *"This deck no longer exists"*: deck copy, on a
recovery-phrase screen.

### Not exercised

**A real local signup end-to-end.** It needs a signup token, and the three ways to get one cost an
SMS attempt, sats, or an invite code — none of which were available in this session. Everything up
to the token is verified (the gate flip, prices, method selection); the redemption step itself,
the backup screens, and the sign-out guard are covered by unit tests only. Journey 22 is written to
be run with a real invite code.

**The tablet AVD.** Landscape and Medium width were exercised by rotating and resizing the phone,
which covers the Expanded and Medium code paths. `Pixel_Tablet` itself was not booted.

### Sign-in options restyled (2026-08-26)

Driven on the Pixel_9 (phone) and **Pixel_Tablet** emulators.

| Check | Result |
|---|---|
| Phone portrait | PASSED — filled "Continue with Pubky Ring" above outlined "Use a recovery phrase or file", marks aligned, both labels complete |
| Tablet landscape (1280×800dp, Expanded) | PASSED — two-pane; buttons, notice, create link and consent all visible with room to spare |
| Tablet portrait (800×1280dp, Medium) | PASSED — stacked; everything visible |
| Landscape **phone** (Expanded width, ~445dp height) | Content exceeds the bounded panel and is reached by scrolling. The consent checkbox gates the primary button, so this is the one width where the screen can look finished while the orange button is disabled |

**Side-by-side buttons were tried and reverted.** At any panel width this layout can spare from the
hero, "Continue with Pubky Ring" and "Use a recovery phrase or file" truncate to "Continue with
Pubky" and "Use a recovery". A clipped label reads as the whole label, which is worse than a
scroll.

`emu rotate` is what actually rotates the tablet — `settings put system user_rotation` did not take
on it, and `wm size` overrides did not reach the app either.

## 25 — A session Loopky cannot reach (#165) — ✅ PASS (2026-09-01, emulator-5554 + Pixel_Tablet)

Driven with the fault injection journey 25 describes, on the phone emulator and then on
`Pixel_Tablet` in both orientations. The account was restored from a recovery phrase (journey 20),
then a two-card deck published so the later failures could not be a broken build.

| Step | Result |
| --- | --- |
| Publish a deck with the first writes allowed through | PASSED — `save: SUCCESS deckId=q3vz90rsspty`, so the wedge that follows is the injection and nothing else |
| Editor Save once the injection bites | PASSED — logcat shows `revalidating session` → `session revalidated successfully` **between** the two write attempts, so the write is retried through a fresh import rather than reported on the first failure |
| Editor error copy | PASSED — "Couldn't save this deck. Loopky couldn't re-establish your session. It's not your connection — try again." No URL, no "HTTP transport error", nothing from the FFI. This is the row that used to render the raw Rust error in place of the card list |
| Editor "Sign in again" | PASSED — `deck_editor_sign_in_again` present under the message; tapping it logs `onSignInAgainClick: signing out to re-authenticate` and lands on onboarding |
| Publish error copy | PASSED — "Couldn't publish this deck." + the same session copy, replacing "Check your connection and try again. Your decks are safe on Pubky." `publish_sign_in_again` offered beside it and signs out correctly |
| Tablet landscape (1280×800dp, Expanded) | PASSED — the error row and the button stay inside the editor's `contentPane(Reading)` cap, aligned with the card rows rather than stretching the pane |
| Tablet portrait (800×1280dp, Medium) | PASSED — same |

**`settings put system user_rotation` did take on `Pixel_Tablet` this time**, contradicting the
2026-08-26 note above — but only on the second attempt, with `accelerometer_rotation` set to 0
first. The first `put` reported success and left `dumpsys` at `rotation=0`. Confirm with
`dumpsys window displays | grep -oE 'w[0-9]+dp h[0-9]+dp'` before trusting a screenshot; that reads
the width class the app actually got, which the rotation value alone does not.

**The long form of the copy was cut on this run.** It first read "…so nothing was saved. Your decks
are safe on Pubky. Try again — if it keeps failing, sign in again.", which took five lines of the
publish form. Every screen composes this after a consequence ("Couldn't save this deck. …"), so
what was lost is already said, and "sign in again" is the button directly underneath.

---

# iOS

First iOS runs ever recorded. Driven on the **iPhone 17 simulator (iOS 26.5)** via
`xcodebuildmcp`, signed in against the **real staging homeserver**, 2026-08-29.

## How sign-in works on a simulator

Pubky Ring cannot be installed on a simulator, so `pubkyauth://` has nowhere to go and the
deeplink path is a dead end there. Sign in by **scanning the QR from a real phone**: tap "Sign in
with Pubky Ring" and the QR sheet is raised automatically, because `ringInstalledHere` is false.
The relay poll underneath is the same one the deeplink uses.

Without this, nothing needing a session could be verified on iOS at all — which is why the QR
landed in the same PR rather than waiting for the iPad work (#173).

## Before this run, iOS did not build

`main` did not compile: `onDispose()` had been removed from the shared ViewModels, `onSignInClick`
had gained a mandatory parameter, and `ErrorMessages.swift` was written against camelCased Kotlin
enum spellings that never existed. Once compiling, it **segfaulted on every launch** from a Koin
cycle (`BackgroundTasks → DeckRepository → BackgroundTasks`) that only iOS entered. Treat every
result below as new ground.

## What passed

| Flow | Result |
| --- | --- |
| Sign in — QR scanned from a phone | ✅ PASS — relay poll → session → Home greeting the real pubky. Survives reinstall (Keychain). |
| Paste-to-Import — parse | ✅ PASS — 2 colon lines → "Detected: colon", live preview, Next enabled. Comma input the shared rules decline reports "single column" with both warnings and Next stays disabled. |
| Publish | ✅ PASS — title → Publish → "Undo (1s)" → Done → deck detail: "Spanish basics", 2 cards, 2 new, both rows, You badge. Written to the homeserver. |
| Edit a card | ✅ PASS — change the back → Save → deck detail reads "Hola / hello there" back from the homeserver. |
| Deck editor — study options | ✅ PASS — Listen/Speak/Type/Both directions all present; enabling Type saves to the manifest. |
| Study loop | ✅ PASS — "1 of 2" → reveal → Again <10m / Hard 1d / Good 3d / Easy 7d → grade both → Done. Deck detail then reads 0 due, 0 new, **43% mastered**, read back from the homeserver. |
| Type the answer | ✅ PASS — wrong answer reports "Not quite — try again", keeps the card shut, leaves the typed text in the field, offers no grade; correct answer opens the card with "Correct!" and all four grades, none pre-selected. Give up opens the card and reports nothing. |
| Listen | ✅ PASS (audible check only) — plays through `SpeechSpeaker` in the deck's declared language. Works without a Kotlin `IosSpeaker`. |
| Settings | ✅ PASS — real pubky and homeserver; raising Good 3d → 5d writes the synced record and the study screen's button then reads "Good · 5d". Restored to 3d afterwards. |
| Profile | ✅ PASS — kfezy1, 1 deck / 2 cards / 0 due, people chips, edit sheet opens. |
| Someone else's deck from Discover | ✅ PASS — this is the `authorPubky` fix. Opens with cover, description, tags, 24 cards and the card list; it resolved to `NotFound` before. |
| Friend profile | ✅ PASS — Discover → person → 6 decks, 17 cards, 1 following, 1 follower, Follow button, deck grid. |
| Follow list | ✅ PASS — Profile → Following → self-addressed empty state. |

## Not exercised, and why

- **Sign out** and **delete account** — both were built and left untapped on purpose: the session
  was needed for the rest of the run, and re-authenticating needs a phone to scan with.
- **Follow / unfollow** — writes to a real social graph; not exercised on someone's live account.
- **Search** — the sheet opens and accepts a query, but a people search against the staging Nexus
  indexer sat spinning and never returned. Not investigated; `SearchScreen` predates this work.
- **Speak** — no `SpeechRecognizer` binding on iOS, and `Info.plist` has no microphone or
  speech-recognition usage strings. The button is hidden rather than shown-and-inert.
- **Background tasks** — `BGProcessingTaskRequest.submit` throws on a simulator.
- **iPad / any width but a phone** — tracked in #173.

## Known noise

`fetchProfile: FAILED — 404` is logged at error level with a stack trace on every sign-in for an
account with no `pubky.app` profile. Harmless and handled, but it was the only `E/` line in an
otherwise clean session and it buries anything that matters. Filed as #174.

## Anki `.apkg` import — ✅ PASS (2026-08-29)

Read end to end on the iPhone 17 simulator against the real homeserver, from a fixture carrying
four notes, a two-template (reversed) note type, a `0x1F`-nested deck name, a protobuf deck
description, and Anki's legacy `collection.anki2` stub alongside the real `collection.anki21`.

| Step | Result |
| --- | --- |
| Open the file | PASSED — zip parsed by `ZipReader` over the system zlib, collection read through the SQLite cinterop |
| Prefer the real collection | PASSED — `collection.anki21` chosen over the legacy stub |
| Parse | PASSED — "4 cards parsed · Separator: tab" |
| Field names | PASSED — "Fields: Spanish → English", read from the `fields` table |
| Deck name | PASSED — "Spanish Nouns", the **root** of `Spanish Nouns␟Beginner`, not the leaf |
| Deck description | PASSED — "Basic Spanish nouns.", decoded from the protobuf `decks.kind` blob |
| Reversible detection | PASSED — two templates, so "Both directions" arrived **on** as a suggestion |
| Suggested tags | PASSED — `#spanish` `#animals`, from the note tags |
| Triage → publish | PASSED — Approve all → Publish → Undo window → deck detail: 4 cards, 4 new, all four rows |

**Two crashes were found and fixed on this path**, both on the first real file:

1. `IosAnkiDb.open` kept the connection handle as the `CPointerVar` it was written into, which
   `memScoped` frees on exit — so every later call read freed memory and `close` segfaulted.
   Covered now by `IosAnkiDbTest`, which fails with signal 11 if the bug is reintroduced.
2. `String(format:)` with Android's `%1$s`. Java's `%s` is a **C string** on iOS, so a Swift
   `String` passed to it faults in `strlen`. Nine ported strings carried it. Every `%d` was also
   widened to `%lld`, since a Swift `Int` is 64-bit.

The second is a whole class of bug, and the catalog is now audited for it rather than trusted —
see the driving notes.

**Not reached by the picker.** The Files picker runs out of process, so the file-choosing tap
cannot be automated. The run above went through "Open with" instead (`simctl openurl` with a
`file://` URL), which the app now handles — Android has the same entry point via `ACTION_VIEW` /
`ACTION_SEND`. The picker itself was exercised by hand.

## Driving notes

- `ui-automation type-text` into a SwiftUI `TextEditor` drops characters. Some of that was ours —
  a field bound through the ViewModel round-trips every keystroke and races the next, now fixed —
  but the tool is still lossy. Prefer `--replace-existing`, re-read the element ref between steps
  (it changes), and use `key-press --key-code 40` for newlines; multi-line `--text` is rejected.
- **Format strings ported from Android are a crash, not a typo.** Java's `%1$s` is a C string on
  iOS and faults in `strlen`; a Swift `Int` handed to `%d` reads the wrong width. Neither the
  compiler nor SwiftLint sees either. Check every `%s` becomes `%@` and every `%d` becomes `%lld`
  when porting a string, and check the *argument order* — `edit_card_context` puts its counts
  before its title, and getting that wrong is also a segfault.
- A bare `.onTapGesture` is invisible to both VoiceOver and the automation snapshot. The study
  card and Discover's person tile both needed an explicit accessibility action before they could
  be driven — worth checking on anything new that is tappable but not a `Button`.


## #149 — Signup, backup and restore on iOS (2026-08-29)

The identity cluster had **no iOS counterpart at all** before this run: fourteen screens, none of
them ported. Driven on the **iPhone 17 simulator (iOS 26.5)** against the real staging homeserver.

| Screen | Result |
| --- | --- |
| Onboarding → Create account | ✅ PASS — after the nav fix below; the method picker shows live Homegate pricing ("Pay ₿ 10 (≈ US$0.01) once") |
| SMS — number entry | ✅ PASS — "Send code" appears only once the number validates. **No code was sent**: a bogus number would burn a real SMS attempt against the homeserver |
| Lightning | ✅ PASS — live invoice created; QR on a white plate, BOLT11 shown, "Open in wallet" / "Copy invoice", "Waiting for payment…" |
| Invite code | ✅ PASS — renders, uppercase field, submit gated. **Not redeemed** — no code to hand |
| Local signup (terminal step) | ⚪ NOT REACHED — needs a spent token, so it needs a real SMS/sats/invite |
| Restore with a phrase | ✅ PASS — derived the key, signed in, landed on Home. See the flake note below |
| Restore with a file | 🟡 PARTIAL — the screen renders and "Choose file" opens the picker, which runs out of process and cannot be automated (same limit as `.apkg`). The decrypt path is unexercised on iOS |
| Backup menu | ✅ PASS — phrase card ticked ✓ Done for a restored key, Ring card says "Not installed — we'll take you to it", "I'll do this later" present |
| Recovery phrase | ✅ PASS — twelve words blurred, reveal pill over the grid, Continue disabled until revealed |
| Confirm quiz | ✅ PASS — Word 4 / 7 / 10; a wrong answer says so and **clears every selection**; a right one returns to the menu |
| Encrypted file | ✅ PASS to the picker — passphrase masked, strength reads "Strong", exporter opens with the blob and a `.pkarr` name. The confirmed-write branch needs the out-of-process picker |
| Export to Pubky Ring | ✅ PASS — not-installed path, "Install Pubky Ring", and the subtitle that says Loopky keeps its own copy |
| Backup from Settings | ✅ PASS — the permanent "Back up your account" row appears for a Loopky-held key and is **absent** for the Ring-held account, which is what `holdsOwnKey` is for |
| Profile backup nag | ✅ PASS by absence — no nag for a Ring-held key, and none for a restored one (already backed up) |
| Sign out | ✅ PASS — confirm dialog, then back to onboarding |

### Three bugs found by driving it, all invisible to the build

1. **Every push in the signed-out flow was dropped.** `identityPath` was appended to inside a
   `NavigationStack` with no path binding, so "Create account" and "Use a recovery phrase or file"
   did nothing at all. Split into two stacks — the signed-in side needs
   `navigationDestination(item:)`, the signed-out side needs a path.
2. **Disabled buttons looked enabled.** A `ButtonStyle` does not react to `.disabled()` on its
   own, so every disabled primary in the app rendered at full strength and invited a tap it would
   swallow.
3. **A passphrase field could not be typed into.** The accessibility identifier sat on the row
   around the field rather than the field, so automation resolved it and typed into nothing. Worth
   remembering: an identifier on a container is not a text target.

### One unexplained failure, recorded rather than closed

The **first** restore-with-phrase attempt on a fresh install returned "That's not a valid recovery
phrase". Two later attempts with the identical phrase, on the same build, signed in. Diagnostic
logging showed `validate_mnemonic_phrase` answering `true`, so the phrase was never the problem
and the failure is downstream of it. Not reproduced since; do not assume it is fixed.

### Not exercised, and why

- Anything needing a **spent signup token** — local signup, and therefore the "a key nobody has a
  copy of" state that the Profile nag and the unbacked sign-out warning are written for.
- The **confirmed-write** halves of file backup and file restore, and the **Ring-installed** half
  of Ring export: all three end in an out-of-process system UI the automation cannot reach.

## #113 — closing the iOS parity audit (2026-08-29)

The last of what #113 listed as Android-only. Driven on the **iPhone 17 simulator (iOS 26.5)**
against the real staging homeserver, signed in by restoring a recovery phrase — no Pubky Ring scan
needed any more, which is what made a sign-out cheap enough to test guest mode at all.

| Flow | Result |
| --- | --- |
| Cold start with no session | ✅ PASS — lands in the browsing shell: Discover, no tab bar, trending tags and decks loading |
| Guest → deck detail | ✅ PASS — Follow, Clone and "Try these cards" all present without an account |
| Guest → Follow | ✅ PASS — "Keep this deck" prompt, naming what signing in unlocks, with a real CTA |
| Guest → study preview | ✅ PASS — 10 sampled cards, images, "Next card" in place of the grade row |
| Explicit sign out | ✅ PASS — lands on onboarding, **not** the browsing shell |
| Tag chip → tag browse | ✅ PASS — `#brasil` lists six decks with covers, counts and authors |
| Follow a deck (signed in) | ✅ PASS — pill flips, announce prompt offers Share / Don't ask again / Not now, post goes out |
| Speak | ✅ PASS to the microphone — button appears only on a deck with a declared pair, permission prompts read correctly, the sheet listens. A simulator has no useful audio in, so no transcription was graded |
| Deck library search + sort | ✅ PASS — filtering hides non-matches and says so; the header echoes the chosen sort |
| Today | ✅ PASS — headlines the study target, shows the goal tally, and the caught-up card replaces the hero when nothing is due or unseen |
| Avatars | ✅ PASS — real profile pictures on Discover, initials where none is set |
| QR scan in search | ⚪ NOT REACHED — a simulator has no camera |
| Drag-to-reorder | ⚪ NOT REACHED — the automation cannot express a long-press drag; the VoiceOver move actions beside it are the reachable half |

### Six bugs found by driving it, none visible to a build

1. **Every push on the signed-in side was dropped.** `navigationDestination(item:)` drives one
   destination at a time, so assigning a new route while one was on screen left it in place —
   deck detail → study, → preview and → clone all did nothing, silently. It is a path now.
2. **Tag chips were not controls.** An `onTapGesture` is invisible to VoiceOver *and* to the
   automation snapshot, so the chips were reachable by a finger and by nothing else.
3. **Avatars never resolved.** A pubky.app profile stores its picture as a `pubky://` file URI
   that `AsyncImage` cannot fetch and fails silently on. `avatarDisplayUrl` has been sitting in
   `commonMain` for exactly this, with a KDoc naming `AsyncImage`, and iOS never called it.
4. **Today told you to stop.** The hero showed the bare due count, so a library with nothing
   overdue and unseen cards left read "0 cards to review" above a Start studying button.
5. **The announce prompt had no "Not now"** — a `confirmationDialog`'s cancel button is detached
   from the action list and did not render.
6. **Following a deck left `canPreview` stale**, in shared code, so a deck you had just kept still
   offered "Try these cards". Android had this too.

Plus three strings handed to `Text(_:)` as bare keys while their catalog values take arguments, so
the screen showed the specifier itself. Worth re-running that check whenever strings are ported:
walk the catalog for values containing `%`, then grep for bare `Text("key")` uses of them.

### Search does not hang

The previous audit recorded search "sat spinning and never returned" against the staging indexer.
It was not reproduced: "spanish" returns deck results in a couple of seconds. Treat the earlier
note as fixed by something in between rather than as an open blocker.

## iPad — adaptive layouts (#173) — 2026-08-29

Three width classes driven on three simulators, all iOS 26.5, via `xcodebuildmcp`. Signed in
against the real staging homeserver — the iPad by QR scanned from a phone, the iPhone from the
session it already held.

| Simulator | Window | Class | What it exercises |
| --- | --- | --- | --- |
| iPad Pro 13-inch (M5), landscape | 1366pt | expanded | every two-pane layout, the sidebar, the grade column |
| iPad mini (A17 Pro), portrait | 744pt | medium | 3-column grids, no two-pane, the QR **sheet** |
| iPhone 17 | 393pt | compact | the regression pass — nothing may have changed |

### Expanded — iPad Pro 13" landscape

| Screen | Result |
| --- | --- |
| Navigation | ✅ PASS — `.sidebarAdaptable` renders the four destinations as a Liquid Glass sidebar, and the toggle collapses it to the floating top tab bar. Order and identifiers unchanged. |
| Home | ✅ PASS — the due-today hero in a 340pt column beside "Today's decks" two across, greeting spanning both. |
| Deck detail | ✅ PASS — cover/title/author/tags/stats in a 360pt column, the card list beside it, the header bar drawn once across the top, Study capped at 520pt and centred. |
| Profile | ✅ PASS — avatar/name/Edit on the left; stats, people chips, pubky.app CTA and Sign out on the right. |
| Decks | ✅ PASS — 4-column grid, content capped at 1160pt and centred. |
| Discover | ✅ PASS — 4-column grid, topic strip, guest banner. |
| Study session | ✅ PASS — card held at 640pt with the four grades standing in a 200pt column beside it, each still thumb-sized and each keeping its `study_*` identifier. |
| Onboarding | ✅ PASS — hero left, sign-in right, both vertically centred. |
| Sign-in handoff | ✅ PASS — picked `AnotherDevice` from the window and rendered the QR **inline in the sign-in column**, not as a sheet. Scanned from a phone; the relay poll completed and the session landed on Home. |

### Medium — iPad mini portrait

| Screen | Result |
| --- | --- |
| Discover | ✅ PASS — 3 columns, not 2 and not 4. |
| Onboarding | ✅ PASS — stacked, and the QR arrives as a **sheet**: the handoff is still `AnotherDevice` (an iPad's key lives on its owner's phone at any width) but there is no second column to render it in. |

### Compact — iPhone 17, the regression pass

Home, Decks, Profile, deck detail and the study loop are pixel-unchanged: stacked layouts,
2-column grid, grades in a row under the card, tab bar at the bottom. Every ceiling added by this
work is above the phone's window, so none of them binds there.

### Two things worth knowing

**In sidebar mode the tab rows are invisible to `ui-automation snapshot-ui`.** The system sidebar
exposes only its `ToggleSidebar` button to the runtime snapshot; collapse it to the tab bar and all
four destinations appear as `tab` targets. Nothing in Loopky controls this — it is how SwiftUI
renders the sidebar — but a script driving the tabs on an iPad has to collapse the sidebar first.
VoiceOver reads the sidebar rows normally.

**The QR gate had a latent hole this exposed.** It asked only `!ringInstalledHere`, which was right
while every sign-in was `ThisDevice`. An iPad now hands off to another device, so the ViewModel
deliberately fires no deeplink — and on an iPad *with* Ring installed the old condition would have
left the screen waiting forever with nothing on it. The question is whether the deeplink was fired,
which is both halves of the ViewModel's own rule.

### Not exercised

- **Slide Over and Split View** — the simulator cannot be driven into either from the automation,
  and the rotation keystroke does not reach it either. The code path is the same one the iPhone
  proves: a narrow pane reports a `.compact` size class, which pins the width class to compact
  ahead of any width reading.
- **The stale iOS test-account phrase.** The recovery phrase recorded for the disposable simulator
  account is rejected by the local BIP39 checksum, so restore-from-phrase could not be used to sign
  the iPad in. The QR scan was used instead.

## Signup → backup → sign out → restore, end to end on iOS (2026-08-30)

Driven on the **iPhone 17 Pro simulator (iOS 26.5)** against the real staging homeserver, on a
brand-new account created for the run: Lightning signup (₿10, staging price), so the "a key nobody
has a copy of" state that `17`/`19` describe was reachable for the first time. The iPhone 17
simulator was deliberately left alone so its existing session survived.

| Step | Result |
| --- | --- |
| Guest shell → "Get started" → onboarding | ✅ PASS |
| Create account → method screen | ✅ PASS — Bitcoin card quotes "Pay ₿ 10 (≈ US$0.01) once", the staging price, with the fiat figure beside it. No Ring gate: a simulator cannot hold Ring, so the local route is the one offered |
| Lightning invoice → paid | ✅ PASS — invoice rendered with QR + Copy, and the screen advanced on its own within ~80s of payment |
| Key minted, account registered | ✅ PASS — landed on the backup menu |
| Reveal the twelve words | ✅ PASS — blurred until asked for, and revealing does **not** mark the method done |
| **"I'll do this later"** | ✅ PASS — lands on Home with the account live and no method recorded |
| The un-backed-up nag | ✅ PASS — Profile carries "Back up your account / Your key lives only on this device…" with `profile_back_up_now`. Home carries none, which is right: the nag has one home |
| The un-backed-up sign-out warning | ✅ PASS — an `.alert` reading "Signing out will erase your key — Loopky holds the only copy of the key for ma8tms, and you haven't backed it up. Signing out deletes it, and this account can never be recovered", with Back up / Sign out and erase / Cancel all visible |
| Nag → phrase → quiz | ✅ PASS — quiz asked words 4, 7 and 10; passing it flipped the card to "✓ Done" and the nag disappeared |
| Sign out (backed up) | ✅ PASS — lands on onboarding, not the guest shell |
| Restore with the recovery phrase | ❌ **FAILED on the first submit**, ✅ passes after the fix below |

### The phrase the app had just shown was rejected as invalid

Submitting the twelve words produced **"That's not a valid recovery phrase"** — the local BIP-39
verdict, over a phrase the app itself had minted minutes earlier. It is not a mis-transcription:
the words were read out of the runtime accessibility tree rather than off a screenshot, and they
check out against the BIP-39 English wordlist independently of the app (checksum `1111`).

Nor was it the FFI. The runtime log shows `validate_mnemonic_phrase` returning at mint time with
`mnemonic_phrase_to_keypair` following it, and at restore time the same call with **no** derivation
after it — so validation answered "false" for a string that had answered "true" fifteen minutes
before. Appending one character to the field and deleting it again, leaving the visible text
identical, made the very next submit sign in.

**The field and the ViewModel disagreed.** `RestorePhraseScreen`'s `TextField` owns its own
`@State` and the ViewModel learns of edits only through `.onChange`; when the text arrives in one
shot rather than keystroke by keystroke, the ViewModel can still hold what it had before while the
field shows the phrase. `canSubmit` is only `phrase.isNotBlank()`, so the button is happily enabled
over a stale value, and what gets checked is not what the user is looking at. Fixed by handing the
ViewModel the visible text at submit time. The exact sequence that failed now signs in on the first
tap.

Worth knowing: **this is the "stale recovery phrase" from the #113 and iPad notes.** The phrase
recorded then was almost certainly fine, and the entry path was the bug. Three other screens take
a pasted value through the same `.onChange`-only route — `InviteCodeScreen`, `RestoreFileScreen`
and `BackupFileScreen` — and were not exercised here; they are the same shape and worth the same
one-line guard.

### Two more found by driving it

- **The sign-out dialog had no Cancel.** `confirmationDialog`'s `.cancel` button is detached from
  its action list and rendered nothing at all, leaving the destructive "Sign out" as the only
  button on screen and a tap outside as the sole, undiscoverable way back. Now an `.alert`, which
  draws both — the same trap, and the same fix, as the announce prompt in #113.
- **The sign-out copy claimed you need Pubky Ring to get back in** ("You'll need Pubky Ring to sign
  back in"), which is untrue for exactly the account this run created: a recovery phrase, a
  recovery file or Ring all work. Android's string was already right; iOS now matches it.

### Not exercised

- **A real clipboard paste.** The automation could not raise the paste menu or send ⌘V, so the
  bulk-entry path was driven by `type-text`. The fix removes the whole class either way, but the
  claim "a paste desyncs" is inferred from the mechanism rather than watched.
- **Recovery file and Ring export** — still end in out-of-process system UI the automation cannot
  reach.

### Sign in by recovery file — ✅ PASS (2026-08-30, same run)

Previously recorded as unreachable because both halves end in an out-of-process document picker.
They are reachable with a person to tap the picker, and were driven that way here.

| Step | Result |
| --- | --- |
| Settings → Back up your account → Encrypted file | ✅ PASS — Create disabled until a passphrase is entered |
| Create and save file | ✅ PASS — `fileExporter` offered "Save as recovery" (`.pkarr`) into the Loopky folder; a confirmed save wrote 91 bytes |
| Both methods marked done | ✅ PASS — the backup menu shows "Recovery phrase ✓ Done" **and** "Encrypted file ✓ Done". This is the confirmed-write half that had never been exercised |
| Sign out → Restore → Encrypted recovery file | ✅ PASS — picker returned `recovery.pkarr`, the screen named the chosen file, Restore stayed disabled until a passphrase was typed |
| Restore | ✅ PASS — decrypted, signed in, landed on Home as the same pubky (`ma8tms`) with **no** backup nag, which is right: a file-restored key is demonstrably backed up |

**The passphrase was entered in one shot on the backup side, on purpose, and did not desync.** That
matters more than it sounds. `BackupFileScreen` carries the identical `.onChange`-only passphrase
as the restore-phrase field that *did* desync an hour earlier, so the defect is **intermittent
rather than deterministic** — one clean run is not evidence a screen is safe. On the restore side
the sync was forced (type, append a character, delete it) so that a failure could only be blamed on
the backup side; it passed, so the file written with bulk-entered text was encrypted with the whole
passphrase.

The consequence of it going the other way is why the same guard has now been applied to the file
screens and the invite code: a phrase that desyncs shows a wrong error message and the user tries
again, but a *passphrase* that desyncs encrypts the file with something the user never typed and
nothing detects it — not at write time, not at "✓ Done", not until the day they need the file and
it will not open.

### iPad — the same three fixes at regular size class (2026-08-30)

Driven on a clean **iPad Air 13-inch (M4)**, expanded width. The signed-in iPad Pro was left alone:
its session came from a QR scan and there is no phrase for that account, so signing out of it would
have cost a scan to undo.

| Step | Result |
| --- | --- |
| Guest shell → onboarding | ✅ PASS — all three routes in the expanded-width layout |
| **Restore by phrase, first submit** | ✅ PASS — bulk-entered, no forced sync, signed straight in to the sidebar layout. This is the case that failed on the phone before the fix |
| Sign-out confirm | ✅ PASS — **Cancel renders beside Sign out** here too; the `.alert` conversion is not phone-only |
| Restore by file → picker | ✅ PASS — picker opened over the bounded restore column, file visible in Recents |
| **Restore by file, first submit** | ✅ PASS — passphrase bulk-entered with no forced sync, decrypted, signed in as `ma8tms` |

Two things this run establishes that the phone run could not. The file that was restored here is
the one written by the **guarded** `BackupFileScreen`, so the guard is confirmed end to end: a
passphrase typed in one shot encrypts a file that opens with the passphrase the user actually
typed. And restore-by-phrase now works on an iPad, which is what makes an iPad cheap to sign in:
before this it needed a QR scanned from a phone, because the recorded phrase was believed dead.

**A saved recovery file overwrites silently.** Saving a second file to the same folder replaced the
first with no prompt, and the earlier one — a different passphrase — went to the folder's `.Trash`.
That is the system exporter's behaviour rather than Loopky's, but anyone keeping a fixture file
should know the passphrase they wrote down belongs to whichever save was last.

## Deck detail — the whole header was untappable on a phone (2026-08-30)

Found while driving the **Release** build (production Nexus + Homegate) on an iPhone 17 Pro Max to
capture App Store assets, and confirmed by hand — not by automation alone, which is what first made
it look like a driving artifact rather than a bug.

**Every control in the deck-detail header did nothing on a phone.** Back, Edit, Delete and Share
all rendered, all reported themselves as buttons to VoiceOver and to `snapshot-ui`, and all
swallowed their taps. Nothing below them was affected — the tag chips opened tag browse and
"Start studying" started a session — so the screen looked entirely alive right up until you tried
to leave it. Interactive pop is off (the header replaces a hidden navigation bar), so the only way
off the screen was to force-quit.

| Step | Before | After |
| --- | --- | --- |
| Deck detail → Back | ❌ dead, 5 taps in a row did nothing | ✅ pops to the tabs |
| Deck detail → Edit | ❌ dead | ✅ opens Edit Deck |
| Deck detail → Share | ❌ dead, no share sheet | ✅ (same hit-testing path) |
| Deck detail → tag chip | ✅ worked throughout | ✅ |
| Deck detail → Start studying | ✅ worked throughout | ✅ |
| **iPad**, same build, same deck | ✅ **worked throughout** | ✅ |

**Cause: the header was inside the `refreshable` scroll view.** A `ScrollView` with
`.refreshable` swallows taps on its topmost content, and `compactBody` had the header as the first
child of that scroll view. `wideBody` had already hoisted its header out — for an unrelated
reason, so that the back button would not scroll away — which is the only reason the iPad was
unaffected and why a tablet pass could never have caught this. `compactBody` now matches it.

Three things this is worth remembering for. The build is green and SwiftLint is clean either way.
The accessibility tree reports a dead button and a live one identically, so `snapshot-ui` cannot
tell them apart — only tapping and asserting on what changed can. And the two layouts diverged
silently: the phone path regressed while the iPad path stayed correct, which is the failure mode
the width-adaptive rule in CLAUDE.md warns about, running in the direction nobody checks.

## Backup hardening, quiz chip, and the phone field (2026-08-31)

Driven on a **Xiaomi 22031116BG (MIUI, Android 14)** over `android-cli`, against staging except
where noted. Five fixes plus one follow-up, all verified by driving rather than by a green build.
Journeys touched: **06** (profile/settings), **20** (restore by phrase); the backup-menu path has
no journey of its own yet and is worth one.

| Check | Before | After |
| --- | --- | --- |
| `android screen capture` on the phrase screen, **production** debug build | ❌ full-resolution, readable phrase | ✅ pure black |
| Restore-phrase field, rotate portrait → landscape | ❌ wiped to empty | ✅ all twelve words survive |
| Recovery-phrase screen, rotate | ❌ blank grid, Continue permanently disabled | ✅ words kept, Continue live |
| Quiz option, selected | ❌ thin warm outline — read as an error | ✅ solid fill + check mark |
| Phrase screen → Save to password manager | — | ✅ GPM sheet → "Saved and checked" |
| Confirm step, after a password-manager save | ❌ recall quiz for words nobody wrote | ✅ "Check your saved phrase" read-back |
| Backup menu, after saving | ❌ method done, no row to say so | ✅ **Password manager ✓ Done** |

**`android layout` does not work on this device at all.** `uiautomator dump` crashes inside MIUI's
`ThemeCompatibilityLoader` (`/data/system/theme_config/theme_compatibility.xml` missing) before it
reaches the app, so the flat-JSON assertions the journeys rely on are unavailable here — every
check above was made from screenshots and coordinate taps. Use an emulator or a non-MIUI device
when a journey needs `android layout`.

**Two device-driving notes.** `adb shell input text` with `%s` separators drops most characters
into a Compose field; typing word by word with ~350 ms between them is reliable. And a fresh
install after `adb uninstall` trips MIUI's "Instalação via USB bloqueada" and needs the phone
unlocked — `adb shell pm clear` resets state without that prompt.

**A black screenshot is not always `FLAG_SECURE`.** One capture came back black because the phone
had gone to sleep, which looks identical to the capture block. Check `dumpsys window | grep mAwake`
before concluding anything from a black frame.

**The password-manager save writes to a real Google account.** Verifying it created a `Loopky`
entry in the signed-in user's Google Password Manager holding the staging fixture phrase. Delete it
from `passwords.google.com` after a verification run, or the fixture phrase outlives the test.

## iOS chips, and a crash behind them (2026-08-31)

Driven on the **iPhone 17 simulator** via `xcodebuildmcp`, staging, while checking whether the
Android quiz-chip fix needed an iOS counterpart. Journeys touched: **04** (discover/social),
**13** (tag browse).

| Check | Before | After |
| --- | --- | --- |
| Discover → tap a topic chip | ❌ **app crashes to the home screen** | ✅ loads "Decks tagged …" |
| Selected topic chip | ❌ looks unpicked; the *others* dim to 50% | ✅ filled, matching Android |
| Selection in the a11y tree | ❌ nothing — `.isSelected` used nowhere on iOS | ✅ reported on topic and quiz chips |

**The crash was a value-class bridge trap, not a UI bug.** `SIGSEGV` at `0x0` inside
`sanitizeLabel`'s `trim()` — a null dereference in a language with no nulls. `Tag` is
`@JvmInline value class`, boxed inside `List<Tag>` but erased to `String` at a parameter position,
so the boxed object Swift found in state reached a bridge expecting an `NSString`. Now recorded as
the fifth bridge trap in CLAUDE.md; the fix is `onTagLabelSelected`, taking the label.

Two things worth repeating. The crash was **only** reachable by tapping — the build is green,
SwiftLint is clean, and `snapshot-ui` renders the chips identically either way, so nothing short of
driving the app finds it. And the iOS quiz chip was already *visually* correct (it filled, which is
what Android was changed to match), which made it easy to assume iOS needed nothing; the defect it
did have was in the accessibility tree, where looking at a screenshot cannot reach.

## Deck editor — the cover it edits (#166) — ✅ PASS (2026-09-01, `emulator-5554`)

Journey **11** re-run on a Medium_Phone emulator, signed in as `kfezy1`, against a real
homeserver. The editor's cover box showed the title's first letter for every deck that had a
cover — the same placeholder a deck with none gets — while the deck grid and deck detail both drew
the real picture. It is the only screen from which a published deck's cover can be *replaced*, so
it was the only screen that would not show what was being replaced.

| Step | Before | After |
| --- | --- | --- |
| Editor on a deck with an **Unsplash** cover | ❌ `S` on the accent-soft square | ✅ the photo |
| Editor on a deck with a **gallery** cover (homeserver blob) | ❌ `S` | ✅ the photo |
| Pick a gallery image, before saving | ❌ `S` — the picked bytes were never drawn | ✅ the photo |
| Save with the cover untouched, reopen | ✅ cover kept (already guarded by this journey) | ✅ kept, ref unchanged |
| Cover tile vs. the title input beside it | ❌ level with the 10sp label, floating above the field | ✅ centred on the field |
| Same, at 1280dp (`wm size 2560x1600`, density 320) | ❌ same misalignment | ✅ centred |

**Two covers, two paths, and only one of them was in the issue.** A remote cover is a URL the
manifest already carries; a gallery cover is a blob with no URL at all, so it has to be fetched and
handed over as Base64 exactly the way `DeckDetailViewModel` does. Mapping the URL alone — the fix
the issue proposes — leaves every gallery cover still showing its initial, and nothing reports it.
The picked-but-unsaved case was broken too: `coverPendingBytes` was in the state and never drawn,
so choosing a photo from the gallery looked like it had done nothing until you saved.

**The alignment was a second bug, found only by looking at the screen.** The cover sat in a Row
with `Alignment.Top` against a column of *label / field / counter*, so it hung level with the 10sp
"DECK TITLE" label rather than the input. Invisible while the box was an empty letter tile;
obvious the moment it held a picture. Bottom-aligning is no better — it parks the tile against the
character counter — so the row is centred.

**Saving no longer rebuilds a cover it did not change.** With the stored URL now in state, the
save path would have reconstructed a ref from the URL alone on every metadata save, quietly
dropping the stored mime and dimensions. `resolveCoverImage` only builds a new ref when the URL
differs from the one the deck was loaded with.

**iOS, 2026-09-01 (iPhone 17 simulator, `xcodebuildmcp` now installed).** Re-run against the
staging account that owns the test decks, opening the editor on an **existing** deck with a cover
(Periodic Table, 118 cards) rather than one created for the test.

| Step | Result |
| --- | --- |
| Editor on a deck with a cover | ✅ the photo — `coverImageBase64` reaches `CardMediaImage` |
| Cover tile clipped to its 64pt box | ❌ then ✅ — see below |
| Cover tile vs. the label + title beside it | ✅ centred on both |

**Showing the cover exposed a second iOS bug, and only driving the app found it.** The tile is a
`ZStack` under `.frame(width: 64, height: 64)`, with the clip applied to the `CardMediaImage`
*inside* it. A `.fill` image reports a size larger than the box in one dimension, so the ZStack grew
with it and the frame merely re-centred the overflow instead of cutting it off — the cover spilled
out over "DECK TITLE" and the title text beside it. Invisible on `main`, because on `main` the
editor never drew a cover at all. Fixed by clipping *after* the frame
(`.frame(...).clipShape(RoundedRectangle(cornerRadius: 14))`), which is what Android's
`Modifier.size(64.dp).clip(...)` on the Box already did. SwiftLint is clean either way, and the
build was green with the overlap on screen.

**Android re-run 2026-09-01 (`emulator-5554`, Pixel_9), on a cloned deck.** The staging phrase
account owns nothing, so the editor was opened on a deck **cloned** from Discover — whose cover is a
pinned blob ref, the Base64 path, and which is genuinely pre-existing rather than created by the
test.

| Step | Result |
| --- | --- |
| Editor on the clone, cover is a pinned blob | ✅ the photo |
| Rename and save, reopen the editor | ✅ cover kept, counter reads the saved title |

**The title row now matches iOS.** The counter and error sat *inside* the column beside the cover,
making it label / field / counter; centring a tile against all three parks it level with the field
while the label floats above it. Moving them below the whole row leaves label + field beside the
cover — iOS's arrangement — so the tile spans both. Both tiles are 64dp/64pt; only the surrounding
column differed.

**Found while testing, not caused by this branch: [#193](https://github.com/jvsena42/loopky/issues/193).**
Tapping Clone deck on iOS terminated the app — `LoopkyErrorReason.init` maps 11 of the shared
enum's 12 entries and lets `Unknown` fall into an `assertionFailure`, but `Unknown` is
`PubkyErrors.kt`'s own `else ->` catch-all. Any unclassified error therefore crashes the iOS debug
app when its copy is rendered. Present on `main`; `ErrorMessages.swift` is untouched by this branch.

## #193 — the unclassified-error crash, and the audit it prompted (2026-09-01, iPhone 17 / 17 Pro)

**The crash.** `LoopkyErrorReason.init` mapped 11 of `ErrorReason`'s 12 entries and let the
twelfth trip an `assertionFailure`. The twelfth was `Unknown` — the classifier's own `else ->`,
produced by seven call sites — so every unclassified error killed the iOS debug app the moment its
copy was rendered. Verified both ways on the iPhone 17 simulator by rendering all twelve entries at
launch: on `main`, `Fatal error: Unmapped ErrorReason: Unknown` after the eleventh; with the fix,
twelve lines and no trap, `Unknown -> Something went wrong`.

**The mapping now runs the other way.** A bridged Kotlin enum crosses as a *class*, so switching
over it can never be exhaustive and a Swift case nothing matched stays unreachable in silence —
which is the actual defect, not the missing line. `LoopkyErrorReason.bridged` switches over `self`
with no `default`, so every case must name its entry or the build fails, and `init` is a lookup
over `allCases`. The assertion survives for its stated purpose: a reason added on the Kotlin side.

**The audit.** Every Swift `switch` over a bridged enum or sealed interface was checked against its
Kotlin source, and every `Effect` member against the screens that consume it. Most `default`s are
sound. Three findings, one of them worse than the crash.

| Finding | Verdict |
| --- | --- |
| `ErrorReason.Unknown` unmapped | ✅ fixed |
| Deck editor never rendered the announce prompt | ✅ fixed — see below |
| Search dropped `ShowFollowError` | ✅ fixed |
| `FormError`, `SignupError`, `BulkImportError`, `RestoreOutcome`, `PublishError`, `UnsplashError`, `PassphraseStrength`, `SignInReason`, `DeckSort`, `TypePhase` | ✅ all entries mapped; the `default`s are genuinely unreachable or land on the one remaining case |
| `BackupPhraseEffect.SaveToPasswordManager`, `BackupEffect.ReadBackFromPasswordManager` | ✅ unreachable — `IosPasswordManagerPresence.canSave()` is `false` by design |

**Creating a deck on iOS did not work, and a green build said nothing.** `DeckEditorScreen` never
read `uiState.sharePrompt`. `settle()` parks a *create* on that prompt and **withholds
`SaveSuccess` until it is answered**, so with `shareOnPubky` defaulting on the deck was written to
the homeserver and the editor simply stayed put — Save looked inert. Worse, `actualDeckId =
deckId ?: generateId()` means a second tap on Save mints a second deck.

Driven on the iPhone 17 Pro simulator (account `ma8tms…`, which owns one deck), reaching the
editor's create mode through a temporary button — the real entry point exists **only** in the empty
library state, on both platforms, which is why nobody had hit this:

| Step | `main` | Fixed |
| --- | --- | --- |
| Save a new deck | ❌ editor unchanged, no prompt | ✅ "Share this on Pubky?" with Share / Don't ask again / Not now |
| After answering "Not now" | ❌ still on the editor; deck present in the library after Close | ✅ editor closes onto the new deck's detail screen |

Both probe decks were deleted afterwards; the account is back to its single deck.

**Search now says why a follow failed.** `SearchEffect.ShowFollowError` fell into a `default: break`
whose comment argued the reverting pill was enough. It is not — Android's search toasts the reason
and iOS's own Discover already flashes it, so search was the only one of the three that reverted in
silence. Same capsule toast as Discover. The failure path was not forced on a device (a simulator
has no radio to switch off); it is line-for-line the Discover path that already works.

**Two announce outcomes are deliberately still not shown**, now as explicit cases rather than a
`default`. `PublishDeckEffect.Shared`/`ShareFailed` and the `DeckEditorEffect` pair arrive
immediately before the `Published`/`SaveSuccess` that pops the screen, so a toast raised there is
torn down before anyone reads it. Android's window-level toast survives that navigation; SwiftUI's
overlay cannot.

**Left for a follow-up, found by the same audit:**

- `PublishDeckView`'s share prompt is still a `confirmationDialog`, the construct whose `.cancel`
  button "may not render at all" — the reason `SignInPromptView.sharePrompt` was moved to `.alert`.
  "Not now" is the safe choice there and may be invisible.
- `HomeEffect.NavigateAllDecks` has no iOS handler and `onSeeAllDecksClick` no iOS caller: Home's
  "See all" over today's decks does not exist on iOS.
- `TriageEffect.NavigateEditCard` still has no iOS destination (already documented in
  `TriageScreen`).
- **New: `journeys/24-deck-create-editor.xml`.** No journey covered creating a deck from the
  editor, which is exactly how the stuck-save survived. It needs an account with an empty library,
  because that is the only place the create entry appears.

## #193 follow-ups — the three the audit deferred (2026-09-01, iPhone 17 Pro)

All three fixed. Driven on the simulator, signed in as `ma8tms…`; every probe deck deleted
afterwards. SwiftLint clean, `detektAll` clean, `:shared:allTests` green (1258 tests), and
`:composeApp:assembleDebug` still builds against the shared change.

**Home's "See all" was a label, not a control.** `TodaysDecksSection` rendered `home_see_all` as a
styled `Text`: it looked exactly like Android's button, announced nothing to VoiceOver, and did
nothing when tapped — `HomeEffect.NavigateAllDecks` had no handler and `onSeeAllDecksClick` no
caller. It is a `Button` now, carrying Android's `home_see_all_decks` tag, wired through
`HomeScreen` to `MainView`, which selects the Decks tab. **Both** Home layouts pass it, compact and
the wide two-pane one. Verified: tapping it moves the selection to Decks.

**The publish share prompt is an `.alert`.** It was still the one `confirmationDialog` left in the
share family — the construct whose `.cancel` button is detached from the action list and, per the
note on `SignInPromptView.sharePrompt`, may not render at all, which would leave "Not now" (the
safe, ordinary answer) reachable only by guessing that a tap outside dismisses. It keeps the
announcement preview as its message, because this is the one place the post is shown before it is
sent. Verified on device: Share / Don't ask again / Not now all visible, preview intact. This one is
applied on the documented rule rather than on an observed failure — the old dialog did render all
three buttons on this OS version.

**Triage card editing exists on iOS, and it turned out the whole feature was broken on both
platforms.** `TriageEffect.NavigateEditCard` had no iOS destination *and* no iOS control emitting
it. Added: an Edit action on `TriageView` (tag `triage_edit`, between the two verdicts as on
Android), a `TriageEditCardScreen` — deliberately not VM-driven, mirroring Android's
`TriageEditCardRoute`, since the draft is in memory and an edit is a field write — and the
`importTriageEditCard` route. Reading and writing the row goes through three narrow
`IosDependencies` functions rather than exposing `ImportRepository` to Swift.

Driving it immediately showed the edit not taking:

| Step | Before | After |
| --- | --- | --- |
| Edit "Gato" → "Gatinho", Save | ❌ triage still reads "Gato" | ✅ reads "Gatinho" |
| Reopen the editor | ❌ opens on "Gato" | ✅ opens on "Gatinho" |
| Publish | ✅ deck carries "Gatinho" | ✅ unchanged |

**That is a `commonMain` bug, and Android has it too.** `ImportRepository.updateRow` stores the
edit in a `rowEdits` map beside the parse — deliberately, so a re-parse can drop them wholesale —
but `currentDraft()` handed back the *raw* rows and only `keptRows()` applied the edits. So every
reader of the draft disagreed with the deck that would be published: triage kept showing the old
text, the editor reopened on it, and a row edited into having a back still counted as missing one.
The edits only became visible at publish, which is the one moment nobody can see. `currentDraft()`
now applies them, with two tests over the real repository. `FakeImportRepository` was wrong the same
way — it ignored edits in both `currentDraft()` and `keptRows()` — so no ViewModel test could have
caught it; it now mirrors the real `applyEdit`.

**Journey 07 already described this exactly, and had never been run.** Its step "the first card
front now reads 'buenos dias'" is the assertion that fails on `main`. The journey now spells out
why that step matters, and adds reopening the editor and leaving it without saving. Android is
still owed a run of it — no emulator was up, and the fix there is the shared code these tests and
the iOS pass cover.

## #174 — an absent profile is no longer reported as an error (2026-09-01, `emulator-5554`)

Shared code, so this is one fix for both platforms; driven on Android because that is where a
log is readable without a simulator attached.

Signed in as `ma8tms…`, Discover → browse `loopky-deck`, which resolves one author profile per
tile and is the densest `fetchProfile` path in the app:

| Step | Result |
| --- | --- |
| Launch, session restored, Home loads | PASSED |
| Discover → 11 decks, 5 author profiles resolved | PASSED — `loadAuthorProfiles: resolved=4/5` |
| The author with no `pubky.app` profile | PASSED — `D … fetchProfile: none published by 1xzbn89y…`, one line, no throwable |
| Error-level lines in the whole session | **0** (`grep -c " E Loopky"`) |

Before the fix that fifth author produced `E/Loopky/IdentityRepo: fetchProfile: FAILED —
PubkyError: … 404 Not Found` with a full stack trace, and sign-in produced two more on every
launch. Nothing about the behaviour changed: the caller still `getOrNull()`s and falls back to
the pubky.

**The classifier was the actual work.** A 404 had to be told from a read that failed, and
`PubkyError` carried the status only inside its message prose. It now parses one out
(`PubkyError.status`), anchored to `"responded with an error: NNN"` so it cannot read a status
out of the `pubky://` URL in the same message, and `isNotFound()` prefers it — which also closes
the hazard `STATUS_507` was written for: a deck id containing "404" used to classify a live
record as missing.

## Every authenticated write paid a `/session` round trip (#105) — ✅ PASS (2026-09-01, `emulator-5554`)

Journeys **02** (paste-import → publish) and **05** (deck manage/delete) re-run on a 16KB-page
`sdk_gphone16k_arm64` emulator, signed in as `ma8tms…dyomwy`, against a real homeserver, after the
FFI fork learned to reuse an imported session (`src/session_cache.rs`).

Counted from the SDK's own tracing rather than a stopwatch. `pubky` logs `Importing session
secret` once per `import_secret`, and `import_secret` is what ends in the `/session` round trip —
so the line count *is* the overhead, and the old code emitted one per authenticated call by
construction.

| Operation | Authenticated writes | `/session` imports (before: one per write) |
| --- | --- | --- |
| Fresh process, cold start | — | **1** |
| Publish a 6-card deck | 4 × `put_with_session` | **0** |
| Delete a 24-card deck with 6 tags | 16 × `delete_with_session` | **0** |
| Delete the 6-card deck | 4 × `delete_with_session` | **0** |

`429 Too Many Requests`: **0** across all of it, with both sweeps back at the ordinary
`MAX_IN_FLIGHT` width — the narrowing to 2 existed only because the doubled request count
saturated the limiter, and it went with the cause. Publish landed in **2.5s**; the two deletes in
**14.5s** and **10.2s** end to end, most of which is the deck-list refresh behind them.

**The cold-start `1` is the control.** It proves the log line still fires and the counting is
honest: the session is imported exactly once per process, and every write after it reuses that
import. Without that row a table of zeroes would equally well mean the tracing was off.

**Two things this run says nothing about.** The homeserver here answered every request first time,
so the expiry path — 401 → drop the cached session → re-import → retry once — was never taken;
it is covered by unit tests in the fork, not by this. And the deck sizes are small: 16 deletes is
not the 9,263-card deck that opened the issue, so the *ratio* is measured here and the wall-clock
at scale is not.

**One emulator artifact, and one sighting that would not reproduce.** `adb shell input text` in a
tight loop corrupts occasional lines (a newline landing one character late), which produces cards
with an empty side and a publish that fails validation before any network call — pace the typing.

Twice during this run the app was left on a **blank white screen with an empty `android layout`
dump**, process alive and `MainActivity` still resumed, after pressing BACK on a failed publish.
It did not survive scrutiny as a bug: single BACK from paste, paced BACKs out of publish, five
rapid BACKs from the Decks tab, and BACK-then-immediate-tap after a failed publish were each
driven deliberately afterwards and all behaved correctly. Both sightings followed a 150–240 line
typing loop, so the likeliest explanation is hundreds of queued key events still draining into
whatever screen the app had navigated to — an artifact of the driving method rather than anything
a finger can reach. Filed as jvsena42/loopky#198 so a second sighting has somewhere to land, not
as a known defect.

---

## iOS import flow brought up to Android's — 02 and 07 re-run (2026-09-01)

`journeys/02-paste-import-publish.xml` and `journeys/07-triage-edit.xml`, driven on the **iPhone 17
simulator** and the **iPad Pro 13-inch (M5) simulator** (both iOS 26.5) via `xcodebuildmcp`, signed
in against the real staging homeserver. Paste cards and Review cards were the two screens furthest
from their Android counterparts; both were rebuilt against the Compose originals.

**02 — paste and triage steps: ✅ PASS on iOS.** The two regressions this journey exists to catch
were *both* live on iOS until this run, because the assertions were written from Android and the
iOS screen had neither:

| Step | Before | Now |
| --- | --- | --- |
| Single word "dog" → chip reads "No pattern detected" | ❌ no chip — a separate warning row below the field | ✅ the chip carries it, as on Android |
| Count reads "1 card", not "1 cards" | ❌ `paste_card_count` was a flat `"%lld cards"` | ✅ a real plural in the catalog |
| 3 comma lines → "Detected: comma" + live preview | ✅ | ✅ now a horizontal strip of flashcards, not a two-column table |
| Next → Review cards with `triage_card`, `triage_progress` "1 of 3", Discard/Edit/Keep | 🟡 the screen existed; **none of the ids did** | ✅ every id the journey names now resolves |
| Keep ×3 → Publish | ✅ | ✅ 1 of 3 → 2 of 3 → 3 of 3 → New deck |

Stopped there rather than publishing a junk deck to staging: the publish screen is unchanged by
this work.

**07 — the edit entry point: ✅ PASS.** `triage_edit` is now a round pencil button between the two
verdicts; tapping it opens the editor on the card's current text (`triage_edit_front` = "hola",
`triage_edit_back` = "hello").

**Swipe, which iOS did not have at all.** Review cards is now a card stack: drag right kept
(1 of 3 → 2 of 3, "1 kept"), drag left discarded ("1 kept · 1 discarded"), the peek card behind
grows in as the top one leaves, and the keep/discard badge scales in over the card and rides with
it. The three round buttons run the same fly-off, so tapping and swiping do not look like two
different screens. Verified mid-gesture on the phone and end-to-end on both devices. Reduce Motion
skips the flight and keeps the decision.

**Tablet.** Both screens match Android's tablet behaviour: a full-width bar over content capped at
the reading ceiling and centred. One thing was wrong on the first iPad pass and is fixed — the card
*region* was capped at 540 rather than the card, which left the card and its buttons bunched under
the title with ~300pt of empty cream below. The region is greedy now; the card is what gets capped.

**The background never reached the edges, on any screen.** Sampling the framebuffer on Review cards
showed pure white from y=0 to y≈90 and again below y≈750 — `.background(color.ignoresSafeArea())`
does not work in a `NavigationStack` destination, because the background sizes to a frame that is
already inset and the colour has no inset left to escape. It was invisible on the cream screens,
where the window's white passes for `surfacePrimary`, and obvious the moment Review cards painted
`surfaceSecondary`. All 16 screens now go through `loopkyScreenBackground`, which puts the colour
under a `ZStack`; re-sampled edge to edge afterwards.

**One stale assertion, left as found.** Journey 02 still asserts "the Listen and Speak toggles are
both ON by default". They are off — all four study opt-ins default off, deliberately, and the
publish screen showed all four off on this run. Not touched here because nothing in this change
caused it; either the journey or the default is wrong and that is worth deciding on purpose.

**A simulator artifact worth knowing.** The simulator keyboard's autocorrect rewrites pasted-in
test text as it is typed ("hola" → "Hora", "dog,cachorro" → "Dog,cachorro", "adios" → "Áudios"),
and `type-text` refuses a string containing a newline or an em-dash — type each line and press
key code 40 between them. None of it is the app; it does make a typed em-dash list untestable this
way.

## Plain-language copy pass — jargon out, Android/iOS matched (2026-09-02, `emulator-5554`)

A copy-only change: no logic touched. Three goals — drop the words a normal user has no way to
know, shorten the instruction blocks, and make the two platforms say the same thing.

**"homeserver" is gone from every user-facing string on both platforms** (was 9 on Android and
mirrored in `ErrorMessages.swift`). Nobody picks a homeserver, sees one, or can act on the word.
It became "your account" where it meant storage. The Settings row that displays the
homeserver's z32 was briefly renamed **Storage server**, and that was reverted the same day (see
the iOS pass below): a coined term names nothing, so a user comparing Loopky's row against any
other Pubky client's has no way to tell they are the same thing. Everywhere else, `homeserver`
survives only in Kotlin/Swift identifiers and code comments.

**"Pubky" now appears only where it names something the user can act on** — the Pubky Ring app,
pubky.app, the invite code from the Pubky team, and the pubky shown on a profile. Dropped from
error copy ("Sign-in couldn't reach Pubky" → "Sign-in couldn't connect"; "Your Pubky storage is
full" → "Your storage is full"), from the sharing prompts ("Share this deck on Pubky?" → "Post
about this deck?", settings row "Ask to share on Pubky" → "Ask before posting"), and from
`error_offline_message`, which no longer explains where decks live in order to reassure you.
"Pubky's authorisation relay", "the Pubky network", "peer-to-peer traffic" and "signup token" are
all gone — the DHT-lookup failure now reads "Some networks block the connection Loopky needs",
which is the part a user can do something about.

**The long blocks were cut, hardest first.** `unregistered_ring_body` 277 → 190 chars,
`restore_error_no_account_message` 223 → 154, `error_lookup_failed_message` 193 → 101. ~30 strings
shortened in all.

**Three rewrites drifted factually and were corrected before commit** — worth recording because
each read fine in isolation. A draft of `unregistered_ring_body` said you could "create an account
here and add it to Ring"; Loopky cannot register a key it does not hold, which is the entire reason
that screen exists. A draft of `backup_phrase_warning` called the phrase "for your Loopky account
only" — it is a Pubky identity that works in Ring and elsewhere. And a draft of
`unregistered_register_confirm_body` narrowed "a key you created for Pubky" to "for Loopky".
Shortening safety copy is where the meaning goes missing quietest.

**Android/iOS divergence: 21 → 7, and the 7 are deliberate.** One is correctly platform-specific
(Drive vs iCloud Drive); five are the trailing `%1$s` reason sentence Android appends to deck-editor
and publish errors and iOS has no reason plumbed for; one is `%@` vs `%1$s` for a single argument.
Also fixed along the way: iOS's `noHomeserverAccount` message still told users to finish setup in
Pubky Ring, which predates Loopky having its own signup.

**One `%`-specifier trap introduced and caught.** Giving iOS the Android delete-confirm copy put a
`%1$@` into a value that `DeckDetailScreen` passed to `Text(_:)` as a bare key — the documented
failure that renders the specifier. Fixed with a `deleteMessage` computed property mirroring the
existing `cloneMessage`; an audit over every `Text`/`Button`/`Label`/`alert` key position against
every `%`-bearing catalog value now reports zero.

| Verified on the phone | Result |
| --- | --- |
| 06 — Settings: identity rows, sharing row, all four study rows | ✅ "Storage server" (since reverted to "Homeserver"), "Ask before posting", no wrapping or truncation |
| 06 — Settings after the second copy pass (below) | ✅ Hard interval's note is one line, Easy interval's two, sharing two; screen no longer scrolls to reach Image search |
| 06 — Sign out dialog | ✅ "Your decks stay in your account. Signing back in restores everything." |
| 05 — Deck detail → Delete deck | ✅ "Delete deck?" / "“Spanish Nouns” and everything you've learned from it will be deleted. This can't be undone." — the title interpolates |

`assembleDebug` and `detektAll` green. Placeholder arity was diffed against `HEAD` across both
catalogs: one intentional change (the delete message gaining the deck title), nothing else.

**Not verified, and why.** The error strings (journey 10) were not re-triggered — they are
`ErrorReason` copy reached only by inducing the failure, and this change alters the words, not the
mapping. **Nothing was checked on iOS at all: this machine is Linux, with no Xcode, no simulator
and no SwiftLint.** The Swift edits are string literals in `ErrorMessages.swift` plus the one
`deleteMessage` property; they need a build and a pass over Settings, sign-out, delete-deck and the
share prompt on a simulator before this is trusted on iOS.

**Second pass on the Studying section, on review.** The three descriptions were still doing more
explaining than a settings row needs. `settings_interval_description` lost the fixed-interval
clause ("the same every time you tap that grade, however often you've seen it") and is now just
"How long until the card comes back." — the mechanic is still true and still worth knowing, but a
row description answers "what is this number?", and the study screen is where the behaviour is
felt. The mastery note dropped "so raising this raises the bar" (the reader can see that from the
number), and the new-cards note dropped the sentence about being told when you reach the goal.
The sharing description lost "so your followers see it"; **its second sentence stays** — without
"Your decks are public either way", "Ask before posting" reads as a visibility switch, which is a
privacy control Loopky does not have.

**Em dashes removed from the copy (same day).** The em-dash-as-connector runs through the catalogs
as a tell, and it was rewritten out of 27 strings on both platforms plus two in `ErrorMessages.swift`
("It's not your connection — try again." → "…, so try again."). Five uses are **kept on purpose**
and anything sweeping this again should leave them alone: `paste_blank_placeholder`,
`profile_stat_pending` and `settings_app_version_unknown` are a bare `—` used as an empty-value
glyph, and `decks_sort_alphabetical` ("A–Z") and `deck_editor_move_to_label` ("Position (1–%1$d)")
are en-dash numeric ranges, which is what an en dash is for. Re-checked on the phone: Settings
reads "A goal, not a limit. You can always keep going."


## iOS pass on the copy branch — study captions, Homeserver, language hint (2026-09-02, `iPhone 17` sim + `emulator-5554`)

The plain-language pass above was written on a machine with no Xcode, and flagged itself as
**unverified on iOS**. This is that verification, plus the three changes it turned up.

**The Studying section was wrong on iOS, and only on iOS.** Android has always hung each caption
under the row it explains; SwiftUI's `Section(footer:)` stacked all three below all four steppers,
so "A goal, not a limit", "How long until the card comes back" and the mastery note read as one
block of prose with no way to tell which belonged to which. The mastery note is the one that
actually breaks: it is only meaningful beside the Easy interval it measures against. Fixed by
giving the stepper label a `VStack` of label + caption, matching `StudySettingsSection`; the footer
now carries only the "can't edit yet" warning, which is genuinely section-wide. VoiceOver gains the
captions as a side effect — they were unreachable footer text and now read out with their stepper
(`New cards per day, A goal, not a limit. You can always keep going., 20, Increment`).

**"Storage server" reverted to "Homeserver"** on both platforms — see the correction above.

**The deck-editor language hint was cut to one line.** Two sentences of justification became "Sets
the voice used to read each side aloud." The requirement is unchanged and still stated where it
bites, on `deck_languages_required`. Three drafts were rejected on the way and the reasons are
worth keeping: "not your accent" makes the reader work out whose accent and why it would happen,
and "This also tags the deck" describes a side effect nobody is deciding at that moment.

| Verified | Result |
| --- | --- |
| 06 — Settings, Studying section (iPhone 17 sim) | ✅ each caption under its own row; Good has none, as on Android |
| 06 — Settings, identity rows (iPhone 17 sim) | ✅ "Homeserver" |
| 06 — Settings, identity + Studying (`emulator-5554`) | ✅ "Homeserver"; Android captions unchanged |
| 05 — Deck editor, Listen on → language pickers (iPhone 17 sim) | ✅ hint is two lines above the pickers, no truncation |
| 05 — Deck editor, Listen on → language pickers (`emulator-5554`) | ✅ hint is one line; backed out without saving |

`assembleDebug`, `detektAll` and `lintSwift` green; iOS builds and runs on the simulator.

**Not verified, and why.** The **iPad** was not checked. The regular-size-class pass this repo asks
for needs a signed-in Settings screen, and the iPad simulator is in guest state — a simulator signs
in by QR from a real phone, which a session cannot do. The change is a `LabeledContent` label
swapped for a `VStack`, which SwiftUI lays out the same at any width, but nobody has looked at it
above compact. The error strings (journey 10) are still un-retriggered, unchanged from the pass
above.

## 04 — Discover, after folding the indexer into `PubkyEnvironment` (#205) — ✅ PASS (2026-09-02, `emulator-5554` + `iPhone 17` sim)

The Nexus indexer stopped travelling on a `BuildConfig.NEXUS_BASE_URL` wire of its own and became
`PubkyEnvironment.nexusBaseUrl`, alongside the gate, the homeserver and the web client. Journey 04
is the whole test surface: everything below the Discover header is an indexer read, and a
mismatched indexer never errors — Nexus answers for the other network, so the strips just come back
empty.

Confirmed the staging indexer had content first, per the journey's own instruction:
`curl 'https://nexus.staging.pubky.app/v0/stream/resources?app=loopky&tags=loopky-deck&limit=5'`
returned five deck manifests.

| Verified | Result |
| --- | --- |
| Android — Discover, trending chips | ✅ `#language #stem #gcse #bosnian #english` |
| Android — People on Loopky | ✅ three suggestions with Follow pills |
| Android — "Discover decks" global browse | ✅ six `discover_deck_tile`s, incl. other authors' decks |
| Android — avatars | ✅ a real photo renders for `juan`, i.e. `avatarDisplayUrl` resolved against the injected host |
| iOS — Discover (`iPhone 17` sim) | ✅ `discover_person` rows, `tag_chip_*` chips, browse tiles from four authors |

**The escape hatch was tested in both directions**, because "the indexer follows the environment"
is only worth as much as the one override that may leave it. With
`LOCAL_NEXUS_BASE_URL=http://127.0.0.1:8080` in `local.properties` and nothing listening there,
"Discover decks" fell to its honest empty state — "Nothing published here yet" with the
`discover_browse_empty_search` CTA — while "From people you follow" kept rendering, since that
comes off the homeserver. That is exactly the silent half-empty symptom #205 describes, reproduced
on purpose. Removing the key and rebuilding brought the browse tiles straight back.

`assembleDebug`, `assembleRelease`, `:shared:allTests`, `detektAll` and `lintSwift` all green. The
release `BuildConfig` was read out of the build directory to confirm the pin: `PUBKY_ENV
= "Production"` with `LOCAL_NEXUS_BASE_URL = ""`, so a shipped build resolves production for all
four endpoints and cannot be talked into staging (#42).

**Not verified:** the iPad. The change is DI wiring with no layout in it, and Discover was read on
a compact size class only.

---

# CLI (`loopky`)

The headless client has no `journeys/*.xml` and cannot have one: those scripts drive a screen with
`android-cli`, and this has none. Its equivalent is two CI jobs in `.github/workflows/ci.yml`, on
a headless Linux x86_64 runner, which is the environment #54 exists for: `cli-linux` runs the
shared suite on the `jvm()` target and asserts the exit-code and `--json` contract against the jar
distribution, and `cli-binary` builds the shipped `native-image` binary through `cli/Dockerfile`
and asserts the same contract plus the two things only the binary can get wrong — that it is
**one file**, and that the FFI loads out of its own resources. What follows is what was checked by
hand beyond that.

## #54 — the Linux target and the CLI — 2026-09-02, macOS arm64 (dev machine)

Run against the **live production and staging networks** with
`cli/build/install/loopky/bin/loopky`, `LOOPKY_CONFIG_HOME` pointed at a scratch directory.

| Verified | Result |
| --- | --- |
| `libpubkycore` loads from the jar through JNA | ✅ `UniffiPubkyClientJvmTest` — mnemonic generated, keypair derived twice to the same pubky, an invalid phrase answered `false` |
| Whole shared suite on the desktop target | ✅ 1,271 tests, 0 failures (`:shared:jvmTest`) |
| `login` mints a real auth URL | ✅ `pubkyauth://signin?caps=%2Fpub%2Floopky%2F%3Arw&relay=https%3A%2F%2Fhttprelay.pubky.app%2Finbox&secret=…` |
| …with **no Ring return-callbacks** | ✅ no `x-success`/`x-cancel`/`x-error`/`x-source` in the URL — there is no app to return to |
| …and the capability is loopky-only | ✅ `caps=/pub/loopky/:rw`, not `DEFAULT_CAPABILITIES` |
| Relay poll starts and blocks | ✅ `Starting auth flow polling for relay channel https://httprelay.pubky.app/inbox/…` |
| Terminal QR renders | ⚠️ SUPERSEDED — see "CLI QR unscannable on Terminal.app" below. The claim was made from a terminal that fills block glyphs to the cell; Terminal.app does not. |
| `--qr-out` | ✅ 512×512 PNG written |
| `--url-only` | ✅ prints the bare URL and no picture |
| Nexus read, production | ✅ `tag trending --limit 8 --json` → `stem, gcse, language, portuguese, 🇧🇷, english, biology, geografia` |
| Nexus read, staging | ✅ `--env staging` → a *different* list (`language, stem, gcse, bosnian, english`), i.e. the environment really does route the indexer |
| Exit code: no session | ✅ 3, `"code":"not_signed_in"` |
| Exit code: unknown command | ✅ 2, one-line message (not the whole usage block) |
| Exit code: dead `LOOPKY_SESSION` | ✅ 4, with the right advice — "mint a new one with `loopky login --export`", not "run `loopky login`" |
| `--json` failures land on **stdout** | ✅ a caller parsing one stream sees the errors too |
| stdout stays clean | ✅ QR, prompts, progress and every log line on stderr; `RUST_LOG` defaulted to `warn` by the start script, so the SDK's four INFO lines no longer precede a login |
| `--version` | ✅ `loopky 0.1.0 (schema 1)` |
| Unit tests | ✅ 41 (`:cli:test`) — arg parsing, the card-file formats incl. accented round-trip, the JSON envelope, the exit-code mapping, the environment-mismatch guard |
| `detektAll` | ✅ green |
| Android and iOS unaffected by the source-set move | ✅ `:composeApp:assembleDebug` and `:shared:compileKotlinIosArm64` both build |

**Two bugs the hand-run caught that a green build did not.** `--version` printed the usage block,
because the "you gave me nothing" branch fires on a command line with no positional words. And
`import cards.tsv` dispatched under the verb `"import cards.tsv"` — the verb was "the first two
words", which is right for `deck create` and wrong the moment a command takes an operand; it
matched nothing and reported an unknown command. Both fixed, both now covered by `ArgsTest`.

## The Linux row itself — 2026-09-02, `ubuntu-latest` (CI run 33689406560)

The `.so` was cross-built from macOS in a container, so until this ran nothing had loaded it on a
real glibc host. The `cli-linux` job did, and passed in 2m30s.

| Verified on Linux x86_64 | Result |
| --- | --- |
| `linux-x86-64/libpubkycore.so` loads through JNA from the jar | ✅ — `:shared:jvmTest` passed, and `UniffiPubkyClientJvmTest` is in it, so the alternative was a failing task |
| Whole shared suite on the desktop target, on Linux | ✅ `> Task :shared:jvmTest` |
| `loopky --version` from the built binary | ✅ `loopky 0.1.0 (schema 1)` |
| `whoami --json` with no session | ✅ exit 3, `{"schema":1,"ok":false,…"code":"not_signed_in","exit":3,…}` |
| The envelope names the network | ✅ `"environment":"production","indexer":"https://nexus.pubky.app"` |
| Unknown command → 2, missing input → distinguishable | ✅ |
| A headless box with no desktop session and no libsecret | ✅ — which is what a GitHub runner is, and the reason the file store is the default rather than the fallback |

**Not verified, and it needs a Linux box with a phone next to it.** Everything above the sign-in
line: no Pubky Ring approval was completed, so `deck create`, `import`, `card add/edit/rm`,
`deck sync/compact` and `whoami` against a real session are **untested end to end**. The
acceptance criteria that turn on that — a deck the Android app opens without a repair step, an
`import` killed mid-run and resumed without duplicating, a full run leaving no record under
`/pub/pubky.app/` — are unmet until someone scans the QR.

## Review round — 2026-09-03, staging, by hand on Linux

A review of the branch drove the binary end to end against a real staging homeserver with a Pubky
Ring session, importing 48 cards. **The run worked** — 48 cards in one shot, correct ords, tags
applied, read back identical — and turned up eleven findings, four of them reproduced on the
device rather than read off the diff. All eleven are fixed on this branch.

| Finding | Where it bit |
| --- | --- |
| `login` stored `homeserver: ""` | The FFI payload has no such field, and the Ring path was the one that never backfilled it — so the environment-mismatch guard, its tests and its README paragraph all applied to a session shape `loopky login` never produces. It failed **open** in the common case. |
| The desktop JDBC URL was never interpolated | `${'$'}` is a Kotlin template producing a literal `$`, so every desktop `.apkg` read failed and was reported as "that .apkg has no readable collection" — a wrong diagnosis pointing at the user's file. |
| `card edit --back=` → exit 1 "internal" | The CLI's own documented way to clear a side answered with a Kotlin assertion string. |
| `card add --json` echoed an `ord` the homeserver did not store | 1000 reported, 0 stored, on a fresh deck. Intent, not result, on the channel built for diffing the two. |
| `whoami`/`login` emitted camelCase | Both docs told an agent to read `session_live`, which did not exist. |
| A third TSV column stored as an image URL | A 3-column Anki export published every card with an image ref pointing at a sentence. |
| `catch (Exception)` missed `Error` | `Native.load` on an unsupported host produced exit 1 with **nothing on stdout** — the one case the docs name. |
| A malformed `LOOPKY_SESSION` → `session_expired` | A typo taught an agent "the hour is up". |
| `--limit twenty` silently became 20 | For the one command whose whole output is a ranked list. |
| A card fronted `#1 ranked` vanished | `startsWith("#")` swallowed it. |
| Two doc comments contradicting the code | `--export` described as print-only; a command name that does not exist. |

**Two of these had passing tests over them**, which is the part worth remembering:
`EnvironmentMismatchTest` handed the guard a homeserver by hand, and the shared suite drives the
`.apkg` reader's *callers* rather than its opener. Both now have tests that go through the real
shape — a raw session payload, and a real zip around a real SQLite file — and both were confirmed
to fail on the old code before the fix landed.

## Review rounds — 2026-09-03, eight rounds against live staging

The branch was reviewed and **re-driven on a real staging homeserver** eight times, not signed off
from the diff. 2 HIGH, 15 MEDIUM and 10 LOW found and closed. The full round-by-round record is on
[PR #208](https://github.com/jvsena42/loopky/pull/208); what belongs here is what a later reader
needs.

**Two defects had passing tests sitting over them, and it was the same mistake twice.** A test
built on a *fixture* rather than on the shape the real flow produces:

- `EnvironmentMismatchTest` handed the guard a homeserver by hand, so the CLI's headline safety
  check was green while having **never once fired** — the Ring sign-in path stored a blank
  homeserver, and the guard compares against exactly that field.
- The `.apkg` suite drove `JvmApkgReader`'s *callers* rather than its opener, so a JDBC URL that
  was never interpolated went unnoticed and every desktop `.apkg` read failed.

Both now have tests that go through the real shape — a raw session payload, and a real zip around
a real SQLite file — and both were confirmed to fail on the old code before the fix landed. **If
you add a safety check here, test it through the path that mints the value, not through a
fixture.**

**Five of the last six fixes found a further bug while being written**: the batched append
scrambling study order across a chunk seam, `logout` claiming `revoked: true` on a machine that had
never signed in, absent-vs-false collapsing the study opt-ins on a bare `--resume`. Worth expecting
if you touch these paths.

### Two corners are deliberately untested

Both need a condition that cannot be created cheaply. Do not read the rest of this file as meaning
the whole surface has been driven:

| Path | What it needs |
| --- | --- |
| `signOut` reporting a **failed** remote revoke (S4) | the homeserver to refuse a revoke. The user is told "Signed out" while the token is still live — the branch's one remaining path that could mislead about a credential. |
| `logout`'s "same session" branch (R5-1) | reaches its message only *after* revoking, so exercising it costs a fresh Ring sign-in. |

### The deliverable

`avskolydfy2q` — **"Computer Networks — Ch. 1: Introduction", 48 cards**, tagged `networking` /
`tanenbaum` / `computer-science`, ords ascending, read back identical to what went in. Extracted
from chapter 1 of Tanenbaum, Feamster & Wetherall 6e and published end to end through `loopky`: no
phone, no screen, one command. That is the branch's claim, demonstrated rather than argued.

---

## #210 — a binary, not a jar — 2026-09-03, macOS arm64 dev machine + linux/amd64 containers

The one thing #54 called "the primary constraint" and did not deliver: an install that needs no
JRE on the target machine. What ships now is a GraalVM `native-image` binary. Built with **GraalVM
CE 25.0.2**; the Linux row built and run inside containers on an Apple Silicon host, so the x86_64
runs below were **emulated** — see "Not verified" at the foot.

| Verified | Result |
| --- | --- |
| macOS aarch64 binary | ✅ `:cli:nativeCompile`, 59.9 MB, **one file** |
| Linux x86_64 binary | ✅ `docker build -f cli/Dockerfile --target export`, **64,817,416 bytes, one file** |
| …and its glibc floor | ✅ `GLIBC_2.34` — the same floor `libpubkycore.so` already had, i.e. building in `ubuntu:22.04` costs nothing and reaches Debian 12 / RHEL 9 |
| The FFI actually loads out of the binary | ✅ `login --url-only` printed a real `pubkyauth://signin?caps=%2Fpub%2Floopky%2F%3Arw&relay=…&secret=…` on both rows — that call reaches `_UniFFILib.INSTANCE`, so JNA extracted `libpubkycore`, checked the API checksums and registered the event-listener callback |
| …with **no network at all** | ✅ same URL under `docker run --network none`; the failure that follows is an honest transport error on the relay poll. So the CI assertion is offline-safe |
| Nexus read over TLS | ✅ `tag trending --limit 3 --json` → `stem, gcse, language`, both rows |
| …on a base with **no trust store** | ✅ `debian:bookworm-slim` has no `/etc/ssl/certs` at all, and both Nexus and the auth relay were reached unchanged. Both halves carry their own roots — so `ca-certificates` in the image is belt-and-braces, not a requirement, and the Dockerfile now says which |
| Exit codes on the binary | ✅ 3 `not_signed_in`, 2 unknown command, `"schema":1` and `"environment":"production"` on both |
| `--qr-out` after dropping `ImageIO` | ✅ 512×512 1-bit PNG from `TerminalQr.toPng`, decodes under `ImageIO` in the test, dark-on-light the right way round, still 0600, still deleted on exit |
| `RUST_LOG` default without a start script | ✅ unset → silent; `RUST_LOG=debug` → the SDK's tracing back. `RustLog.kt` sets it through libc |
| Container image | ✅ `--target runtime`, 217 MB, runs as uid 1000, `--version` / `whoami` / `tag trending` all correct |
| `.deb` | ✅ `cli/packaging/deb.sh` → 16 MB, `dpkg -i` into a clean `bookworm-slim`, `loopky --version` from `/usr/bin` |
| `install.sh` host matrix | ✅ Intel Mac, Linux arm64 and an unknown host each refused by name with the reason; Linux x86_64 proceeds to the download (404 today — no release yet) |
| Per-row jar tarballs | ✅ `loopky-linux-x86-64.tar` holds only `linux-x86-64/libpubkycore.so`, `loopky-darwin-aarch64.tar` only the dylib; the macOS one runs |
| Shared suite on the JVM target | ✅ 1,305 tests, 0 failures |
| `:cli:test`, `detektAll` | ✅ green |
| The Linux binary **runs on real x86_64 hardware** | ✅ CI run 33783491387, `cli-binary` on `ubuntu-latest` (`gcc (linux, x86_64, 11.4.0)`, not emulated): one file, 64,817,416 bytes — the same byte count the emulated build produced — `--version`, `whoami` → 3, and `login --url-only` printing a real `pubkyauth://…&secret=…`, i.e. the FFI loaded and its checksums matched on native hardware |
| …and it was built with the compatibility target | ✅ `Graal compiler: optimization level: 2, target machine: compatibility` in the same log, so the flag took. That is *not* the same as exercising it — see "Not verified" |

**Start-up, measured rather than quoted.** 20 runs each, warm:

| | native | jar + start script |
| --- | --- | --- |
| `--version` | **5 ms** | 49 ms |
| `whoami` (no session, starts Koin) | 133 ms | 139 ms |

The second row is the honest one and it is not a disappointment about `native-image`: ~125 ms of
it is JNA unpacking `libjnidispatch` so `RustLog.kt` can call `setenv`. Any command that reaches
the homeserver pays that anyway. With `RUST_LOG` already set — the call returns immediately — the
same command is **8 ms**.

**What the one-file check caught, twice, in one sitting.** `native-image` does not fail when it
cannot fold a JDK native library into the executable; it emits the library beside it and reports
success. Both of these built green and produced eight files on Linux:

| Reached AWT via | Fix |
| --- | --- |
| `TerminalQr.writePng` → `ImageIO` | a hand-rolled PNG encoder, ~30 lines of chunk-and-CRC. A QR code is two colours and no palette |
| the Koin binding for `MediaProcessor` → `JvmMediaProcessor` → `ImageIO` | `PassThroughMediaProcessor`, and `initKoinJvm` now takes the processor as a **required** argument so a default cannot make the old one reachable again |

A third source was subtler and is worth recording: enabling the **community reachability-metadata
repository** — the obvious first move for JNA — pulled `java.awt` in on its own. Its JNA rows turned
out to be a subset of what the tracing agent had already recorded against a real homeserver, so it
is off, and `com.sun.jna.NativeLong` was the single row worth merging in by hand.

### Not verified

| Path | Why |
| --- | --- |
| **A host without AVX2** | the reason `-march=compatibility` is set, and the one thing the CI run above does *not* show: a `ubuntu-latest` runner has AVX2, so a binary built at `native-image`'s default x86-64-v3 would have run there too. The log proves the flag was applied (`target machine: compatibility`), not that it was needed. Demonstrating the SIGILL it prevents needs a pre-Haswell host, or QEMU with the feature masked off |
| `.github/workflows/release.yml` end to end | it fires on a `v*` tag and nothing has been tagged. The Linux job runs the same `docker build` verified here; the macOS job runs the same `:cli:nativeCompile`; the upload steps are unexercised |
| The **Homebrew** tap | `cli/packaging/loopky.rb` is a template. A tap is its own repository and this branch cannot create one — the file says what to do with it |
| `install.sh` against a real release | the host matrix and the failure paths were driven; the download, checksum and install path stop at a 404 until something is published |
| A write against a homeserver **from the binary** | the FFI is proven loaded and the auth URL is real, but approving in Ring needs a phone. `#54`'s hand-run above covers the write path on the jar, and the code is identical |

## #210 review round 1 — the fixes, verified — 2026-09-03

Eleven findings, none a false positive. Two were things that would have shipped wrong to a user,
and both were demonstrated rather than argued, so both were reproduced here before being fixed.

| Finding | Reproduced | After |
| --- | --- | --- |
| **H1** the `.deb` declared no `Depends`, so an old release installed and *then* crashed | ✅ `dpkg -i` on `debian:bullseye-slim` (glibc 2.31) exited **0**, then `loopky --version` died with `/lib/x86_64-linux-gnu/libc.so.6: version 'GLIBC_2.34' not found` — a loader error naming no package | ✅ `Depends: libc6 (>= 2.34), zlib1g`. `apt install ./loopky_0.1.0_amd64.deb` now exits 100 with `loopky : Depends: libc6 (>= 2.34) but 2.31-13+deb11u14 is to be installed` and installs **nothing**; `dpkg -i` exits 1 with the same reason and leaves the package unconfigured. Still installs and runs on bookworm. (True of the *shipped* artifact only because round 2 pinned the compression — see below) |
| **H2** `--version` was a literal, so a tag would ship four disagreeing numbers | ✅ `-PloopkyCliVersion=9.9.9` proves the old string was fixed at 0.1.0 whatever the build was called | ✅ `loopkyCliVersion` in `gradle.properties` is the single source; `:cli:generateCliVersion` compiles it in; `-PloopkyCliVersion=9.9.9` → `loopky 9.9.9 (schema 1)`. A new `check-version` job fails the release when the tag disagrees, and both binary jobs assert `--version` matches the tag *after* building |
| **M3** the CI one-file assertion counted a stage that copies one named file | ✅ `export` was `COPY … /loopky`, so `ls dist \| wc -l` was 1 unconditionally | ✅ `export` copies the whole `nativeCompile/` directory; the count is now an assertion. Rebuilt: still exactly one file |
| **M4** an unshipped host built a binary with no FFI and no HTTPS, and passed the one-file check | — | ✅ `checkNativeImageHostIsSupported` runs before `nativeCompile` and names the shipped rows. A task, not a configuration-time `error`, so `:cli:test` and `installDist` still work on such a host |
| **M5** the image job pushed before the smoke tests, and moved `:latest` on any `v*` | — | ✅ `needs: [check-version, linux, macos]`, and `:latest` only for a tag with no pre-release suffix |
| **M6** the `-march` claim did not follow from the log | ✅ `ubuntu-latest` has AVX2, so a default x86-64-v3 build would have run there too | ✅ narrowed to "runs on real x86_64" plus "built with `target machine: compatibility`", and a host without AVX2 is back under **Not verified** |
| **L7** `jvmPlatformModule` kept the default the KDoc argued against | — | ✅ neither entry point has one |
| **L8** a stale artifact made the one-file check blame fixed code | ✅ planted `libawt_xawt.so` in the output directory | ✅ `nativeCompile` clears its output directory first; the planted file was gone and the check passed |
| **L9** Linux arm64 fell off the end of the Homebrew formula | — | ✅ `on_arm` `odie`s like the Intel-Mac branch |
| **L10** `install.sh` pointed at `HostSupport.kt`, renamed to `SupportedHost.kt` | — | ✅ full path, so the two host matrices can be kept in step |
| **L11** the one string telling a user to install a JDK led with it | — | ✅ leads with the binary; the jar is the afterthought |

Re-verified after all of it: macOS binary still one file at 59.9 MB, Linux binary still one file at
64,817,416 bytes, the runtime image still builds and answers `--version`, 1,305 shared tests and
`:cli:test` green, `detektAll` green.

One nuance worth stating rather than glossing, since the round caught exactly this kind of thing:
`dpkg -i` unpacks before it checks dependencies, so `/usr/bin/loopky` exists on disk in the
refused case even though the package is left unconfigured. `apt install ./file.deb` — the path a
person actually uses — refuses without unpacking anything.

## #210 review round 2 — the `.deb` the release would actually publish — 2026-09-03

One new medium and one carried-forward low. The medium is the more interesting entry in this file,
because it is a case of the *verification* being wrong rather than the fix.

**The round-1 H1 check tested a package the release would never produce.** `dpkg-deb --build`
takes its compression from the builder, and the two builders disagree:

| Builder | `dpkg` | `ar t loopky_0.1.0_amd64.deb` |
| --- | --- | --- |
| `debian:bookworm-slim` — where round 1 built it | 1.21.23 | `control.tar.xz`, `data.tar.xz` |
| `ubuntu:24.04` — what `release.yml` runs on | 1.22.6 | `control.tar.zst`, `data.tar.zst` |

Debian 11's dpkg cannot read zstd at all, so the package the release would publish answers, on the
very hosts the `Depends:` line was added for:

```
dpkg-deb: error: archive '…' uses unknown compression for member 'control.tar.zst', giving up
```

— no package named, no fix suggested, nothing unpacked. That is the failure mode H1 removed,
reintroduced one layer down by a default nobody chose. `apt install ./…deb` was unaffected (it
reads the control data itself), so the regression is specific to `dpkg -i` and invisible to the
path most people would test first.

Fixed by pinning `-Zxz` in `deb.sh`. Re-verified by building the package **on `ubuntu:24.04`**,
which is the point:

| Check | Result |
| --- | --- |
| Members | ✅ `control.tar.xz`, `data.tar.xz`, 15,889,232 bytes |
| `dpkg -i` on `debian:bullseye-slim` | ✅ `loopky depends on libc6 (>= 2.34); however: Version of libc6:amd64 on system is 2.31-13+deb11u14` — exit 1, left unconfigured |
| `apt install ./…deb` on bullseye | ✅ exit 100, `Depends: libc6 (>= 2.34) but 2.31-13+deb11u14 is to be installed`, nothing installed |
| `dpkg -i` on `debian:bookworm-slim` | ✅ installs, `loopky --version` → `loopky 0.1.0 (schema 1)` |

**And the one-file check counted files, so a stray directory passed it.** `reports/` — which a
diagnostic flag produces — would have slipped through `checkNativeImageIsOneFile` and surfaced
instead as CI's bare `ls | wc -l`, an exit code with none of the explanation the task exists to
give. It counts entries now, and marks a directory with a trailing `/` because that usually means
a diagnostic flag is still set.

The lesson worth keeping: a packaging check has to be run against the artifact the *release*
builds, not one built the same way somewhere else. Round 1's `.deb` was correct and its test was
not.

## #211 — `.apkg` import, wired up — 2026-09-03, macOS arm64 (dev machine)

The reader landed in #208 with no way to call it: `ApkgReaderJvmTest` was the only thing in the
repo that ever ran it. This is the entry point, checked against real archives — a real zip around
a real SQLite collection, built by `cli/tools/make-sample-apkg.py` and by `ApkgFixture` in the
tests — through `cli/build/install/loopky/bin/loopky`.

A `.apkg` shaped like the failure the field picker exists for: fields `SentenceId` / `Spanish` /
`English` / `Picture`, five notes of which one is empty and one has only a front, one picture of
12,000 bytes, and a two-template (reversed) note type.

| Verified | Result |
| --- | --- |
| `import deck.apkg --dry-run` with no session | ✅ exit 0, nothing written — the dry run is deliberately outside `authed` |
| Field list with a sample of each | ✅ `1 "SentenceId" 2528426`, `2 "Spanish" Hola`, `3 "English" Hello`, `4 "Picture" ""` |
| The heuristic skips the id column | ✅ `front_index: 2` — the #96 failure, visible before the write instead of after |
| `--back-field Picture` re-reads with the new mapping | ✅ front 2 "Spanish", back 4 "Picture", 1 card, `images: {imported: 1, bytes: 12000}` |
| Drop accounting in the envelope | ✅ `{empty: 1, half_empty: 1, missing_media: 0, total: 2}` against `note_count: 5` |
| Reversed note type | ✅ `reversible: true`, so `--reverse` defaults on |
| Anki note tags | ✅ reported as `suggested_tags`, **not** applied to the deck |
| `--front-field Nonsense` | ✅ exit 2, and the message lists all four fields with their numbers |
| A `.apkg` that is not a zip | ✅ exit 9, "could not be opened as an .apkg: zip END header not found" |
| A zip with no collection | ✅ exit 9, and the message names `collection.anki21b` and Notes in Plain Text |
| `--separator` on an `.apkg` / `--front-field` on a `.tsv` | ✅ both exit 2 rather than being ignored |
| `import x.apkg --title X` with no session | ✅ exit 3 — a real import still needs one |
| A text import is unchanged | ✅ `format: "text"`, `separator: "tab"`, `apkg: null` |

`:cli:test` is 40 tests up (97 total, 0 failures), `:shared:jvmTest` unchanged; `detektAll` clean.

**Not verified: a real publish.** Everything above writes nothing. The upload half — blobs to the
homeserver, the per-run memo, the sweep on an aborted publish and the deliberate *absence* of one
on an aborted `--resume` append — is covered by `ApkgUploadTest` against fakes, and has **not** been
run against a live homeserver, because `loopky login` needs a phone scanning a QR code and this
machine has no session. That is the gap in this round: a first real Anki deck published from a
terminal is still the next thing to do.

**The native binary was the other gap, and CI closed it** (run 33804571528, all four checks green).
`.apkg` import is the first thing in the CLI to touch `org.sqlite`, whose JDBC driver extracts a
native library from the image's own resources — reflective loading, exactly the shape that made JNA
the hard part of #210. A `native-image` build needs a GraalVM this machine does not have, so
`cli-binary` generates the sample archive and runs `--dry-run --json` against the real binary. It
read a real SQLite collection out of a real zip and answered:

```json
{"format":"apkg","cards":3,"apkg":{"deck_name":"Spanish Sentences","note_count":3,
 "fields":[{"index":1,"name":"SentenceId","sample":"2528426"},{"index":2,"name":"Spanish","sample":"Hola"},
 {"index":3,"name":"English","sample":"Hello"}],"mapping":{"front_index":2,"front_name":"Spanish"}}}
```

`front_index: 2` in the shipped binary is the whole assertion in one number: the driver loaded, the
collection opened, and the field heuristic skipped the id column. Nothing else the CLI does would
have exercised any of it, and `checkNativeImageIsOneFile` passed in the same build — so reaching
SQLite did not drag a JDK native library out beside the executable.

## Remote card images do not render — 2026-09-04, staging, `emulator-5554` + Linux

Reported as "an agent created a deck with images through the CLI in production and the images are
not rendering". Reproduced on staging with a deliberately mixed deck — `loopky deck create
--from-file` with seven cards spanning four hosts — and the answer is **two independent failures**,
neither of which the CLI can see, because it stores a URL and never fetches it. `--json` reported
success on every card.

`loopky card list --json` read every URL back byte-identical, so nothing is lost or mangled on the
write side. The refs are correct; they are unfetchable.

### 1. Coil's user-agent is refused — Android only

`CardMediaImage` hands a remote ref straight to `AsyncImage`, and nothing in `composeApp` installed
an `ImageLoader`, so requests went out as `okhttp/4.12.0`. Wikimedia refuses that outright:

```
HTTP/2 403   server: HAProxy   content-type: text/plain
Please set a user-agent and respect our robot policy https://w.wiki/4wJS.
See also https://phabricator.wikimedia.org/T400119.
```

It is not the URL and not the image — the *same* URL returns `200 image/jpeg` (718902 b) to a
descriptive agent. Probed on one URL, varying only the header:

| User-Agent | Result |
| --- | --- |
| `okhttp/4.12.0` | **403**, every URL tried |
| *(empty)* | **403** |
| `Loopky/0.7.1 (+https://github.com/jvsena42/loopky)` | **200 image/jpeg** |
| `iosApp/1 CFNetwork/1568.100.1 Darwin/24.0.0` | **200 image/jpeg** |

Fixed by `loopkyImageLoader` — a singleton Coil `ImageLoader` whose only job is a descriptive
`User-Agent`, installed with `SingletonImageLoader.setSafe` in `LoopkyApp`. Verified on device:
card 09 (a 250px Wikimedia thumb) rendered the gull where it had been a blank half-card.

**iOS is not affected by this one.** `AsyncImage(url:)` goes through `URLSession`, whose default
agent carries the bundle name, and Wikimedia answers that with 200 (row 4 above). Not verified on
a simulator — this machine is Linux and has no Xcode — so it is a code-and-protocol reading, not a
device run. What iOS *does* share is the silent failure: `AsyncImage`'s placeholder is
indistinguishable from a slow load, and it offers no way to set a header if a host ever demands one.

### 2. Wikimedia rejects arbitrary thumbnail widths — both platforms

Independent of the agent, and unfixable from the client: `upload.wikimedia.org` now serves only a
fixed set of thumbnail widths and answers everything else with `400, Use thumbnail sizes listed on
https://w.wiki/GHai`. Measured with a known-good user-agent:

| Width | 120 | 180 | 200 | 220 | 250 | 280 | 300 | 320 | 330 | 400 | 440 | 500 | 640 | 800 | 1024 | 1280 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ |

So **120 / 250 / 330 / 500 / 1280**, and the original (un-thumbed) file always works. This matters
more than it looks: an agent asked for pictures writes `320px-…` or `800px-…` as readily as `250px-`,
and four of those five are a blank card on both platforms with the UA fix in place. Card 10 in the
matrix is the control and stayed blank after the fix, exactly as intended.

### The matrix, as it rendered on device

| Card | Host / shape | Before | After |
| --- | --- | --- | --- |
| 01 | `upload.wikimedia.org`, 320px thumb | ❌ blank | ❌ blank (width, not UA) |
| 02 | `upload.wikimedia.org`, 280px thumb | ❌ blank | ❌ blank (width, not UA) |
| 03 | `dummyimage.com` png | ✅ | ✅ |
| 04 | `picsum.photos`, 302 redirect | ✅ | ✅ |
| 05 | `fastly.picsum.photos`, direct | ✅ | ✅ |
| 06 | text **and** picture on the front | ✅ | ✅ |
| 07 | both sides pictured | ✅ | ✅ |
| 08 | `upload.wikimedia.org`, original file | ❌ blank | ✅ |
| 09 | `upload.wikimedia.org`, 250px thumb | ❌ blank | ✅ |
| 10 | `upload.wikimedia.org`, 320px thumb | ❌ blank | ❌ blank (control) |

Redirects, query strings and the text-plus-picture shape were never the problem — 03–07 passed
throughout, which is what made the host the only variable left.

### Still open

The CLI accepts `http://` as readily as `https://` (`looksLikeImageUrl`), and Android at
targetSdk 36 blocks cleartext while iOS ATS does the same — so an `http://` ref is unloadable on
both clients by construction, written without complaint. Not exercised in this run; found by
reading `CardFile.kt`. And more broadly, nothing on the write path tells an agent that the picture
it just attached will not render — `--json` says success either way, which is the gap that let a
whole production deck get built on 403s.

---

## #222 — `deck edit` — 2026-09-04, Linux x86_64, staging

Driven against a **real homeserver** with the live session
(`kfezy17…`, `/pub/loopky/:rw`, `--env staging`), from `cli/build/install/loopky/bin/loopky`, on a
throwaway deck (`wtgnffybom5q`) created and deleted in the same run. Two cards were added partway
through, so the "cards are untouched" rows are about a deck that actually had some.

| Step | Result |
| --- | --- |
| No field flag at all | ✅ exit 2, `usage`, and the message names every flag that would count |
| `--title` + `--description` + `--cover-url` | ✅ `changed: true`, `fields: [title, description, cover_image]` |
| …and the fields nobody named | ✅ `cover_emoji` 🧪 and `tags: [alpha]` survived the write |
| The same edit again | ✅ `changed: false`, `fields: []`, `updated_at` **unmoved** — no manifest write |
| `--tag beta --tag gamma` | ✅ replaced, not appended: `[beta, gamma]` |
| `--front-lang en-US --back-lang es-ES` | ✅ `[beta, gamma, language, english, spanish]` |
| `--back-lang fr-FR` | ✅ `spanish` dropped, `french` added, `beta`/`gamma` kept |
| `--description= --clear-cover` | ✅ description, `cover_emoji` and `cover_image` all null |
| `--clear-tags` on a language deck | ✅ `[]` — the derived labels are **not** put back |
| Edit a deck with 2 cards in it | ✅ same deck id, `card_count: 2`, chunk table `[{n:0,count:2}]` unchanged |
| `deck show` in a fresh process | ✅ every change is on the homeserver, not just in the cache |
| `card list` after the edit | ✅ both cards still there, untouched |
| `--cover-url http://…` | ✅ exit 9 `bad_input`, refused before the write |
| `--cover-url` at a 800px Wikimedia thumb | ✅ warned on stderr, stored anyway (advisory, never fatal) |
| `--clear-tags --tag x` | ✅ exit 2 — refused rather than guessed at |
| `--title=` | ✅ exit 2, "a deck cannot have no title" |
| `--clear-cover --cover-emoji 🧪` | ✅ exit 2 |
| A deck id that does not exist | ✅ exit 6 `not_found` |

### Worth knowing

**`--tag` replacing is visible in row 9 and is the intended trade.** Setting the tags to exactly
`[français]` on a deck with a declared pair drops `language`/`english`/`french` too, because the
pair did not move on that call — the labels are ordinary author-removable tags (Architecture §7.7),
so "the tags are exactly these" has to be believed. Re-declaring the pair puts them back.

**The issue asked for `--cover-url` and `--cover-emoji` to be mutually exclusive "as in
`deck create`", and they are not exclusive there either.** `MediaRef.Image` and `coverEmoji` are
separate manifest fields and `DeckTile` layers them — the image is drawn *over* the emoji, which
is the fallback when it fails to load. So both are settable in one call here, and `--clear-cover`
removes both halves; refusing the pair would have broken the fallback the tile is built on.

---

## #225 — language labels on every CLI write path — 2026-09-04, Linux x86_64, staging

Driven against a **real homeserver** with the live session (`kfezy17…`, `/pub/loopky/:rw`,
`--env staging`), from `cli/build/install/loopky/bin/loopky`, on three throwaway decks created and
deleted in the same run. The first row is the issue's own probe, re-run.

| Step | Result |
| --- | --- |
| `deck create --front-lang en-US --back-lang es-ES --listen --speak` | ✅ `[language, english, spanish]` — was `[]` |
| `deck show` in a fresh process | ✅ the labels are on the **homeserver**, not just in the cache |
| `deck create --tag geografia`, no pair | ✅ `[geografia]` — no `language` umbrella on a deck that is not a language deck |
| `deck edit --clear-tags` on that language deck | ✅ `[]` — naming no pair leaves the labels alone, so the gesture still empties the set |
| `deck edit --front-lang en-US --back-lang es-ES` (the pair it already had) | ✅ `[language, english, spanish]` — restating is the repair |
| `import x.tsv --tag core --front-lang ja-JP --back-lang en-US` | ✅ `[core, language, japanese, english]` |
| `import --resume --front-lang ja-JP --back-lang pt-BR` | ✅ `english` dropped, `portuguese` added, `core` kept |
| `import --resume` with no flags at all | ✅ unchanged, and no metadata write |
| `tag trending --env staging` | ✅ `language`, `english`, `portuguese`, `spanish` rank on Nexus — ordinary tags, indexed like any other |

### Worth knowing

**The workaround the issue described is gone rather than documented.** Retyping a deck to a
different *region* of the same language to make the labels materialise was a trick, not a command;
`deck edit` now reconciles whenever the invocation **names** a pair, so restating the one the deck
already has does it. That is also the fix for the `--tag` interaction noted at the foot of the #222
section above — where "re-declaring the pair puts them back" was true only if the pair actually
moved.

**The `--clear-tags` row is the one that had to keep failing to change.** These are ordinary
author-removable tags (Architecture §7.7 point 4), so an edit that names no pair must not put
`language` back on a set the caller just emptied.

## Tab completion for `loopky` — 2026-09-04, Linux x86_64 (dev machine) + alpine containers

No homeserver and no session: `completion` runs before Koin and generates a static string, so what
had to be verified is that the three shells actually **do** what the script says. Driven from
`cli/build/install/loopky/bin/loopky` — bash locally, zsh and fish in `alpine:3.20` containers,
since neither is installed on this box.

| Step | Result |
| --- | --- |
| `loopky <TAB>` | ✅ all three shells — `login logout whoami deck card import tag update completion` |
| `loopky deck <TAB>` | ✅ `list show create edit delete sync compact`, with summaries in zsh and fish |
| `loopky deck cr<TAB>` | ✅ completes to `create` |
| `loopky deck create --ti<TAB>` | ✅ `--title` |
| `loopky --env <TAB>` | ✅ `staging production` |
| `loopky --env staging deck <TAB>` | ✅ the subcommands — the walk steps over the option **and its value** |
| `loopky card add abc --front x --ba<TAB>` | ✅ `--back --back-image`, so a card front that looks like a flag does not confuse it |
| `loopky deck edit abc --clear<TAB>` | ✅ `--clear-cover --clear-tags` |
| `loopky import --separator <TAB>` | ✅ all nine separators |
| `loopky import <TAB>` | ✅ filenames — the one command with a file operand |
| `loopky completion <TAB>` | ✅ `bash zsh fish` |
| `loopky deck show <TAB>` | ✅ zsh names the word (`deckId`) and offers nothing; bash offers the flags only |
| `zsh -n` / `fish -n` / `bash -n` on each script | ✅ all parse |
| `source` the bash script, `complete -p loopky` | ✅ registered |
| `loopky completion bash --json` | ✅ envelope carries `shell` and the whole `script` |
| `loopky completion` / `loopky completion powershell` | ✅ exit 2, both messages name the three shells |

### Worth knowing

**Driving zsh found a bug a build could not.** The script ends with `_loopky "$@"`, which is
correct when zsh *autoloads* it out of `$fpath` — and wrong for the `eval "$(loopky completion
zsh)"` install the same file documents, where it runs `_loopky` outside any completion context and
prints "command not found" for `_describe` on every new shell. It now branches on `funcstack[1]`
and registers with `compdef` on the sourced path. Both install lines in `--help` work as written.

**The interactive zsh check needed a pty.** `zpty`'s `-t` is *test*, not timeout, so the obvious
read loop blocks forever; the menu above was captured with a bounded polling loop, and the
per-arm dispatch was additionally driven directly by stubbing `_describe`/`_files`/`_message`/
`_values` and calling `_loopky` with `words`/`CURRENT` set by hand.

**Nothing was checked against a network on purpose.** A test asserts no generated script contains
an address — completing a deck id would be a homeserver round trip on a keypress.

## Haptics in the study loop — 2026-09-04, `emulator-5554`

Journeys 03 and 09, re-run for the haptics. An emulator has no actuator, so "did it buzz" was read
back from `adb shell dumpsys vibrator_manager` — `Recent vibrations` names the calling package, the
`performHapticFeedback` constant and the pattern the framework actually played, which is the only
evidence available short of holding a phone.

| Step | Result |
| --- | --- |
| Flip a card (`study_card`) | ✅ `constant=6` (ContextClick) → `Prebaked=TICK`, attributed to `com.github.jvsena42.loopky` |
| Tap the already-flipped card again | ✅ **no** second entry — the ViewModel drops the reveal, so nothing buzzes |
| Grade (`study_good`) | ✅ one ContextClick per grade |
| Grade the 8 presentations of "Spanish Nouns" through to the end | ✅ 15 ticks and then `constant=16` (Confirm) → `Prebaked=CLICK`. Fifteen, not sixteen: the last grade's own tick is suppressed so the session ends on one buzz rather than a tick and a Confirm a few ms apart |
| Enable **Type the answer** on a deck, flip, type a wrong answer, Check | ✅ `constant=17` (Reject) → `Prebaked=DOUBLE_CLICK` |
| Toggle typing back off | ✅ deck restored |

### Worth knowing

**The buzz is decided in `StudySessionViewModel`, not on tap in the screens.** Whether a tap did
anything is known there and nowhere else — a grade arriving while the previous one is still writing,
a Check on an untypable card and a second reveal are all ignored — and buzzing for one of those
tells the reader something happened when nothing did. The "tap the flipped card again" row above is
the one that catches a regression here.

**iOS is written but unrun.** `Haptics.swift` maps the same `StudyHaptic` onto
`UIImpactFeedbackGenerator`/`UINotificationFeedbackGenerator`, and `StudySessionScreen` plays it off
the same effect. There is no macOS on this machine — no `xcodebuildmcp`, no simulator — so the Swift
half has not been compiled, let alone felt. It owes a run.

## The keyboard on the front of a card — 2026-09-04, `emulator-5554`

Journey 03, re-run with **Type the answer** on (48-card "Computer Networks" deck, portrait and
landscape). An emulator shows the keyboard the same way a phone does but says so out loud, so every
row below was read back from `adb shell dumpsys input_method | grep mInputShown` — polled ~12 times
over the second after each grade, because what this bug does is *flash*.

| Step | Before | After |
| --- | --- | --- |
| Flip a typing card | ✅ input on the back, `mInputShown=true` | ✅ unchanged |
| Wrong answer → Check | ✅ miss line, text kept, no grades | ✅ unchanged, and the line now grows in |
| Correct → Check, or Give up | ✅ card opens, `mInputShown=false` | ✅ unchanged |
| Grade → next card's front | ❌ `mInputShown=true` on **8 of 8** advances — one sample most times, four in a row (several hundred ms) on two of them | ✅ `.` on all 14 advances (8 portrait, 6 more after the refactor) |
| Same at expanded width (landscape, `w914dp`, grade column) | — | ✅ 3 of 3 advances clean, grade column intact |

### Worth knowing

**The keyboard was the *next* card's, drawn by the *previous* one.** `AnimatedContent` keeps the
outgoing card composed for its 100 ms fade, and it re-runs the enclosing content lambda — which read
`state.typePhase`, by then already `Answering` for the card that had just arrived. So the card on
its way out drew the incoming card's input, its `FocusRequester` fired, and the keyboard came up over
a front with nothing to type into. It then usually went away when that card was disposed a frame or
two later, which is what made it look intermittent rather than constant. The fix is that
`CardSnapshot` now carries the card's own `typePhase` and typed text, so an outgoing card can only
ever draw its own.

**A phone in landscape cannot type on a card, and could not before this either.** At `h411dp` the
keyboard takes two thirds of the window and the card is left a ~40 px strip with the input inside it
unreachable. Not touched here — it is the same layout on `main`, and it is a height problem, not the
width-class kind (#173). A tablet in landscape has the room and is fine.

## The flip, and what was actually stuttering — 2026-09-04, `emulator-5554`

Journey 03 with **Type the answer** on, re-run after the keyboard-on-the-front fix, because the
turn itself was visibly juddering and the card jumped when the grades arrived. Frame data from
`dumpsys gfxinfo <pkg>` sampled over the 750 ms of one flip, geometry from `uiautomator dump`.

| | Before | After |
| --- | --- | --- |
| Frames rendered per flip | 21 | **44** — it had been dropping every other one |
| Janky frames | 13.6% | **7.1%** |
| p90 frame | 32 ms | **16 ms** |
| Worst frame in the turn | ~435 ms ×3 | none; the IME's ~445 ms now lands after the card has settled |
| Card y across front / answering / graded | 405 → 579 → 405 | **405 in all three** |
| Same at `w914dp` (landscape) | card squeezed to a ~40 px strip, controls unreachable | 332..869 throughout, and typing is usable there for the first time |
| Reveal transition | 3.2% janky | 3.2% janky — the "flash" was never dropped frames |

### Worth knowing

**It was the keyboard, and `atrace` is what said so.** Seven guesses were measured and all seven
were wrong: a height animation on the rows under the card, composing the back face at the 90°
crossing, `TextAutoSize`, the rounded clip sitting outside the 3D layer, `rotationY` itself, the
reveal haptic, and the card's ripple. Each was built and benched; p99 stayed at 400–450 ms through
every one of them. One `atrace --async_start view gfx` over a single flip named it in three lines:

    460 ms  putmethod.latin   Choreographer#doFrame
    446 ms  putmethod.latin   draw-VRI[InputMethod]
    426 ms  RenderThread      dequeueBuffer / allocateHelper
    433 ms  surfaceflinger    present
    425 ms  jvsena42.loopky   eglSwapBuffersWithDamageKHR

The answer field took focus the moment the back face composed — the frame the rotation crosses 90° —
and showing the IME makes SurfaceFlinger allocate its window surface, which every frame the card was
drawing then blocked behind. The card was never slow; the keyboard was landing on top of it.

**A control experiment is what turned guessing into measuring.** Tab switches on the same build
measured 1–4% janky with a p99 of 16–65 ms while the flip sat at 13% and 400 ms, which is what
established the cost was the flip's own rather than the emulator waking up. Worth doing early: two
A/B runs of the *same* build had disagreed by 10 points before that, and one of them nearly sent a
"fixed" claim out on noise.

**`imePadding()` pads by the keyboard's whole height, not the overlapping part.** Moving it from the
screen onto the input block inside the card looks like the surgical version of this fix and is not:
the block's viewport shrank below the block's own height and Give up was sheared off the bottom —
laid out at [368,1002][712,1076], drawn nowhere, and answering no tap there. It is in the tree and
absent from the screen, which is the failure mode nothing reports. The card's own height already
clears the keyboard; that is what the fix relies on, with the block's scroll as the fallback.

## #213 — the macOS row: the Keychain, and one file — 2026-09-05, macOS 26.6 arm64

The first time `:cli:nativeCompile` had been run on a Mac. Two findings, one of them the reason
the row was never shippable.

**The binary was eleven files, and had always been.** `checkNativeImageIsOneFile` failed on a
clean `main` before any of this branch's code existed, listing ten JDK dylibs beside the
executable — `libawt`, `libawt_lwawt`, `libfontmanager`, `libfreetype`, `libjavajpeg`, `liblcms`,
`libmlib_image`, `libosxapp`, `libjava`, `libjvm`. So the `curl … -o ~/.local/bin/loopky` install
the binary exists for was dead on macOS, and the release job would have failed at the same check
the moment anyone tagged. `-H:AbortOnTypeReachable` named it in two builds: `java.awt.Toolkit` via
`java.awt.Component.<clinit>`, and `Component` itself "present in an `Executable` object
reconstructed by reflection". Bisecting `reflect-config.json` by halves found the single entry —
`com.sun.jna.Native`, whose declared methods include `getComponentID(Component)`. Dropping its
`methods` list is **not** enough; the class entry alone does it. `jni-config.json` already carries
the same six methods and is the registration `dispatch.c` actually uses, so the reflect entry was
a duplicate the tracing agent wrote. Linux is unaffected by the identical registration, which is
why CI stayed green.

**Sign-in with Pubky Ring, driven from the `Pixel_9` emulator against staging.** The known
relay flakiness is still there — round 1 of the retry loop timed out, round 2 landed. Poll the
layout for `select-pubky-title` and `ConfirmAuthAuthorizeButton` rather than sleeping a fixed
amount: a fixed 3s tapped through Ring's cold-start screen into *Add Pubky* twice.

| Verified with the **native binary** unless noted | Result |
| --- | --- |
| `nativeCompile` output | ✅ one file, 56 MB |
| `login --env staging` via Ring on the emulator | ✅ `Silver-Otter-Sparrow`, `caps=/pub/loopky/:rw` only, Ring's sheet showed `/pub/loopky/ READ, WRITE` |
| The session lands in the **Keychain** | ✅ `security find-generic-password -s loopky.session -a session.v1` returns the item, `desc="Loopky session"`, written by the *binary* — which is what proves `ProcessBuilder` + `security -i` survive `native-image` |
| …and **not** in a file | ✅ `~/Library/Application Support/loopky/secrets.json` does not exist |
| `login` reports where it put it | ✅ `stored_at: "the macOS Keychain (service loopky.session)"` |
| `whoami --json` | ✅ `session_store` beside `config_home`, `session_live: true` |
| `whoami` text | ✅ `Session from: this machine` / `Session in: the macOS Keychain (…)` |
| Homeserver **read**, staging | ✅ `deck list` → Sparrow's 7 decks (Gross Anatomy 442, Biochemistry 1668, spanish 10 000 sentences 2156, Phrasal Verbs P1 3747, …) |
| Homeserver **write**, staging | ✅ `deck create` → `edaixtwb7hpv`, `card add` from TSV → two cards, `card list` read both back with `ord` 0 and 1000, `deck delete` cleaned up |
| `LOOPKY_CONFIG_HOME` opts back into the file | ✅ `LOOPKY_CONFIG_HOME=/tmp/loopky-disposable whoami` → exit 3 `not_signed_in`, and the real session was untouched afterwards |
| The jar distribution reads the same item | ✅ `installDist`'s `deck list` returned the same 7 decks |
| Real `security(1)` round trip | ✅ `DesktopSessionStoreTest` — missing → write → read → overwrite → delete → missing, on a throwaway service name. It caught the one bug in the write path: `-D "Loopky session"` has a space, and `security -i` splits its line the way a shell does |
| Shared + CLI suites | ✅ `:shared:jvmTest` and `:cli:test` green, 18 new tests |

**Not run:** a live `logout`. It would revoke the session this run signed in with, and getting
back in depends on the relay lottery above; `clear()` against the real keychain is covered by the
round-trip test instead. Migration from a pre-#213 `secrets.json` is covered by the fake-keychain
tests, not on a live install.

### Review round 2 — 2026-09-05, same machine

Three findings, all real. The interesting part is that verifying the third one produced the
condition it describes.

| Verified with the **native binary** | Result |
| --- | --- |
| `logout` | ✅ `revoked: true`, `cleared_locally: true`, exit 0, and the Keychain item is gone — `security find-generic-password` answers "could not be found" |
| Re-`login` through Ring on the emulator | ✅ round 1 this time (the relay lottery, not a change) |
| Staging read/write after the drain rework | ✅ `deck create` → `deck list` → `deck delete` |
| The fallback, unplanned | ✅ a sign-in during the wedged-keychain window below wrote the session to `secrets.json` and reported it — the 20s bound turning an indefinite block into a fallback, observed rather than argued |
| The migration, unplanned | ✅ the next `whoami` adopted that file session into the Keychain and left `secrets.json` empty (`[]`) — the one-copy invariant on a real install, not a fake |

**A flakiness loop wedged the whole machine's keychain, and that is the finding.** Chasing one
failing round trip with a 25-iteration loop — concurrent with a Gradle run doing the same — made
macOS raise an authorization dialog. `securityd` serialises everything behind it, so **14**
`security` processes queued up, including `gh`'s token read, and `gh api` started answering
"Requires authentication". Draining the requesters and killing `SecurityAgent` cleared it.

The hypothesis that `-U` on an existing item prompts because it re-applies the `-T` ACL is
**wrong**, tested directly: fresh add, update-with-`-T`, update-without-`-T` and delete-then-add
all returned 0 with no dialog. So there is no redesign — the volume is what did it, and neither the
product nor the suite does that. What changed is the test: only a *timeout* is excused as an
environment condition, because a blanket skip would have hidden the `-D "Loopky session"` quoting
bug this same test caught.

### Review round 3 — 2026-09-05

Two findings, both consequences of the round-1 fixes. The second is the reviewer walking back part
of their own round-1 request, correctly: deleting the stale item before falling back made
`loopky login` fail outright on a locked or absent keychain — the exact host the fallback exists
for — because `write` and `delete` fail together for almost every reason either fails.

**Neither suggested patch was taken, and the reason is worth keeping.** Both findings are
downstream of one thing: `load()` read the Keychain *first*. Inverting that to read the file first
dissolves both with no new state and no refusal. The file is empty whenever the Keychain holds the
session (a successful write clears it), so the happy path is unchanged; when it is *not* empty it
is not empty for exactly one reason — a Keychain write failed and this is the newer credential — so
preferring it is the correct answer rather than a tie-break. The stale item then cannot win, so
nothing needs deleting on the failure path and nothing needs to throw; the next `load()` that finds
the Keychain answering overwrites it on its way past, which is the same call that migrates a
pre-#213 file session up.

The invariant is restated accordingly: not "never in two places" but **when both hold something,
the file is the newer one and wins**.

| Verified | Result |
| --- | --- |
| Happy path unchanged by the inversion | ✅ `whoami` still reports the Keychain, `deck list` still returns 7 staging decks, one file lookup in an already-loaded map ahead of it |
| `logout`'s guard no longer hides the likelier case | ✅ `!clearedLocally` alone — a store that refuses a delete has usually refused the read too, so `hadSession` was false and the old guard exited 0 over a surviving credential |
| A refused delete over nothing is not a failure | ✅ `clear()` gates on `read() is Missing`, so a clean sign-out on a file-only host stops reporting a missing credential as a surviving one |

**The real-keychain round trip is now opt-in, and that is the other finding of this round — a
self-inflicted one.** It creates and deletes an item in the developer's *login* keychain on every
`:shared:jvmTest`, and running the suite a few times in a row raised macOS authorization dialogs
twice during this session, the second time with the user at the keyboard. `securityd` serialises
every caller behind such a dialog, so `gh`'s token read hangs too and `gh api` starts answering
"Requires authentication". It runs under `LOOPKY_KEYCHAIN_TESTS=1` now. Everything the wrapper
decides is covered by the fake; what the real one adds is the `security(1)` protocol, which is
worth running by hand when that changes and worth nobody's password prompt otherwise.

### Review round 4 — the LOW list — 2026-09-05

All seven taken, all verified real first. Two changed behaviour, the rest are contract and
documentation.

| Finding | Resolution |
| --- | --- |
| `fallback.clear()` can throw on the *success* path, leaving the Keychain with the new session and the file with an older one — the one arrangement file-first assumes cannot exist | The invariant is re-established rather than assumed: a failed clear writes the new session to the file instead. It does not stay loud otherwise — once a full disk recovers, `storedInFile()` migrates the *older* credential back over the newer one and nothing is left to notice |
| Both presence probes ran `find-generic-password -w`, pulling the secret through a pipe to answer "is it there?" | `Keychain.exists()` — same exit codes without `-w`. Pinned by `the probes do not pull the secret out of the keychain`, which counts reads on the fake |
| `requireArgvSafe` threw out of a `Result<Unit>` contract, so the one input it guards would have taken `save()`'s fallback with it | Returns `Result.failure`; the guard now fails on the same path as every other write failure |
| `stored_at` silently became a *file* on Linux where it had been a *directory* | Reverted to the config home; `session_store` added to `LoginResult`, so `login` and `whoami` answer with the same field name. The two sinks became `LoginSinks` to keep the signature under detekt's parameter limit |
| The release job's Keychain assertion is negative-only, so it also passes when the store was the file all along | The precondition is asserted positively — `LOOPKY_CONFIG_HOME` and `XDG_CONFIG_HOME` both empty — since a runner image exporting either is exactly what would make it vacuous |
| `location` is a blocking subprocess behind a non-suspend `val`, memoised for the process | Documented, because the signature hides both and `SecureSessionStore` is shared with the apps |
| **`XDG_CONFIG_HOME` also opts a Mac out of the Keychain**, and only `LOOPKY_CONFIG_HOME` was documented | Written down in the KDoc, `ConfigHome`, `--help` and `cli/README.md`. Verified on the binary: `XDG_CONFIG_HOME=/tmp/xdgprobe whoami` → `not_signed_in`, real session untouched |

Re-verified on the binary against staging afterwards: `whoami` still reports the Keychain and a
live session, `deck list` still returns 7, and a `deck create`/`delete` round-trip still works.

**The release skill was missing the Homebrew tap.** It covers the macOS *binary* properly — the
artifact table, the one-job-per-row constraint (`native-image` does not cross-compile), and a
by-name assertion for `loopky-macos-aarch64` and its `.sha256`. What it never mentioned is
`cli/packaging/loopky.rb`, which is a **template** with `version "0.0.0"` and zeroed `sha256`s that
has to be copied by hand into a separate tap repo per release. Left undone the tap serves the
previous version silently, and that is worse than stale: `loopky update` refuses on a Homebrew
install with exit 11 and tells the user to `brew upgrade`, so the one instruction the binary gives
them leads nowhere. Now step 12, with the two `.sha256`s to fill in and a `brew upgrade` check.

## #240 — field notes from driving the CLI as an agent — 2026-09-05, macOS arm64, staging

Six findings from an agent driving `loopky` through #213's verification. All six taken. Driven
against a **real homeserver** with the live staging session (`bzbjrj9a…`, `/pub/loopky/:rw`,
`--env staging`), from `cli/build/install/loopky/bin/loopky`, on throwaway decks created and
deleted in the same run.

| Finding | Verified |
| --- | --- |
| 1 — a blank id exited **1, internal**, the one code an agent retries | ✅ `card list ""` now `bad_input` / 9, and inside a batch too. Also `card add`, `deck show`, `card rm`, `import ""` — it is one check in `Args.requireWord` |
| 2 — `login` could not be bounded, so SIGKILL was the only tool | ✅ `login --url-only --timeout 15` prints the URL, waits, exits **13**. The `--qr-out` sweep runs, which is what `timeout -s KILL` skipped |
| 3 — every homeserver command pays ~2.5s | ✅ `loopky batch`: six operations, **28.7s / 29.4s** as separate invocations against **10.6s / 11.0s** as one batch. 2.7×, and it grows with the run |
| 4 — no machine-readable command surface | ✅ `commands --json` — 19 verbs with operand arity, flags, `needs_session`, and per-command exit codes. `--help --json` too. No session, no network |
| 5 — a killed `deck create` retried produces a second deck | ✅ `deck create --id d --if-not-exists` returns the existing deck, `created: false`, nothing written. Without the flag an existing id is refused rather than published over |
| 6 — `"Request failed: Request failed: …"` on every failure | ✅ said once. Confirmed on a live 404 from the homeserver |

A mixed batch, run against staging, is the row that exercises four of them at once:

```
op 0 create   deck create  ok=True
op 1 missing  deck show    ok=False not_found
op 2 blank    card list    ok=False bad_input     ← finding 1, through a batch
op 3 after    card add     ok=True
exit 6, "2/4 operations succeeded, 2 failed. First failure was operation 1 (deck show):
         Request failed: Server responded with an error: 404 Not Found - Not Found"
                          ↑ finding 6: one prefix, not two
```

### Worth knowing

**`withTimeout { complete() }` does not bound the login, and the wrong version passes every test
that checks the exit code.** `complete()` blocks in the FFI on a `Dispatchers.IO` thread, and
`withContext` does not hand control back until its body returns — measured with a scratch test, a
300 ms timeout around a 5 s blocking call returned `null` after **5029 ms**. The await runs on a
daemon thread with the timeout on a `CompletableDeferred` instead, and `LoginTimeoutTest` asserts
the wall clock rather than only the code.

**The CI login smoke test was hiding failures.** `timeout -s TERM 25 … || true` swallowed every
exit code, so an FFI regression looked identical to the expected non-approval. It now asserts 13
(nobody approved) or 5 (the relay was unreachable, which must not fail a PR) and fails on anything
else.

**A batch does not renew a session.** One session held for the whole run still dies after about an
hour (#165). A long batch hits that wall exactly where a long sequence of separate commands would;
what it removes is the per-command cost, not the ceiling.

**Nothing here was driven on a device**, and nothing needed to be: the change is `:cli` only, no
Compose and no SwiftUI. The Android and iOS journeys above are untouched by it.

## pt-BR localization + app-language setting — 2026-09-06

Android on `emulator-5554` (API 37) and iOS on the iPhone 17 simulator, both signed in against
staging. Not one of the 25 numbered journeys — the change touches every screen's copy rather
than any one flow, so it was verified by driving the screens each journey passes through.

| Step | Result |
| --- | --- |
| Settings → new **LANGUAGE** section | PASSED — Android `settings_language`, iOS `settings_language`. Shows the current choice and, on Android, which of the two descriptions applies |
| Picker → Português (Brasil) | PASSED — Android recomposed in place, no relaunch |
| **The choice is the OS's, not Loopky's** | PASSED — `adb shell cmd locale get-app-locales com.github.jvsena42.loopky` → `[pt-BR]`. It is the framework's per-app language preference, so it also appears in Settings → Apps → Loopky → Language |
| Home | PASSED — "PARA HOJE", "0 de 13 concluídas · continue assim!", "20 novas · 20 cartas" |
| Decks / Discover | PASSED — "Biblioteca · 7", "Colar para importar", "De quem você segue", "1 carta" / "3 cartas" |
| Deck detail | PASSED — stats bar "Total / A revisar / Novas / Dominadas" |
| Study loop | PASSED — "1 de 10191", "Toque na carta para ver a resposta", grades "Repetir / Difícil / Bom / Fácil" |
| iOS, launched `-AppleLanguages "(pt-BR)"` | PASSED — tabs "Hoje / Baralhos / Descobrir / Perfil", Settings "Idioma do app · Português (Brasil)", "Cartas novas por dia" |
| iOS bundle | PASSED — `pt-BR.lproj` with `Localizable.strings` (720), `Localizable.stringsdict` and `InfoPlist.strings` |

### Worth knowing

**The grade buttons were rendering the raw Kotlin enum**, on both platforms and in every
language: `SrsGrade.name` on Android and `shared.name` on iOS. They are catalog keys now
(`srs_grade_*`), and the `study_*` accessibility ids are deliberately *not* derived from the
label any more — a localized id is findable only in the language it was written in, and every
journey targets those ids. The iOS tab titles were four hardcoded English literals for the same
reason and now read `nav_tab_*`, which the catalog already had.

**"Novamente" does not fit the Again button.** The grade button autosizes down to an 11sp floor
and then clips with `softWrap = false`: on a 1080px-wide screen it rendered as "Novame". "Repetir"
is the same length as "Difícil", which fits with room to spare. Anything longer than seven
characters needs measuring on a device, not eyeballing in the XML.

**`carta` is feminine where `cartão` is masculine**, so the noun swap dragged agreement changes
through 157 values — including a dozen that never name a card but agree with one ("%1$d mantidas
· %2$d descartadas", "Novas", "Dominadas", "%1$d de %2$d concluídas"). Nothing in the build
reports a wrong agreement.

**Two interval labels are still produced in `commonMain`**: `SrsScheduler.formatInterval` returns
`"<10m"` and `"3d"`, which cross to both UIs as finished strings. They read the same in pt-BR
(`d` for dias, `m` for minutos) so they were left alone — but a language where they do not would
need `previewIntervals` to return days and the UIs to format, which is a change to the
`SrsRepository` interface and both platforms.

**iOS `home_deck_due_cards` has no plural variation**, so it says "1 cartas" — and "1 cards" in
English, which is how long it has been there. Pre-existing, not introduced here; Android has the
plural (`home_deck_due_cards`) and iOS has a flat key.

### Follow-ups from the same session — 2026-09-06

**iOS Settings was unreadable on a dark device, and it was never a Settings bug.** `LoopkyColor`
is a single fixed light palette with no dark variants, and every screen paints its own cream
ground — but native chrome does not follow that. A `List`, an alert, a sheet or a menu takes its
row fills, section headers and default label colour from the *system* scheme, so on a dark device
Settings drew near-black rows and dark-on-dark labels on cream, with the section headers and the
language footer effectively invisible. `.preferredColorScheme(.light)` at the `WindowGroup` root
is the fix; verified with the simulator in dark mode on Settings **and** on `FollowListScreen`,
the other `List` screen that had it. `PasteView` is the third. The alternative is a dark palette,
which is a design decision rather than a bug fix.

**The Android picker is an `ExposedDropdownMenuBox`, not a dialog of radio rows.** The menu
scrolls and sizes itself, so a fifth language costs a line in `AppLocale.SUPPORTED` and
`locales_config.xml` and nothing in the UI. All three paths driven on `emulator-5554`:

| Step | Result |
| --- | --- |
| Português (Brasil) | PASSED — `cmd locale get-app-locales` → `[pt-BR]`, screen recomposed in place |
| English | PASSED — → `[en]` |
| Padrão do sistema | PASSED — → `[]`, i.e. the framework preference is *cleared* rather than pinned to `en`, and the row reads "System default" |

The `settings_language` test tag moved to the dropdown anchor and
`settings_language_option_*` to the menu items, so the ids a journey would target are unchanged.

## Dark mode + the Theme setting — 2026-09-06

Android on `emulator-5554` (API 37, pt-BR) and iOS on the iPhone 17 simulator. Not one of the 25
numbered journeys — a palette touches every screen rather than any one flow — so it was verified by
driving the screens the journeys pass through, in both palettes.

| Step | Result |
| --- | --- |
| Settings → new **APPEARANCE** section | PASSED — Android `settings_theme` dropdown, iOS a four-segment `Picker`; System / Auto / Light / Dark |
| Dark | PASSED — home, study, decks, discover, profile, settings all repaint in place, no relaunch |
| Light | PASSED — pixel-identical to before the change, including the Today hero |
| System | PASSED — iOS simulator in dark appearance renders dark with nothing pinned; the `.preferredColorScheme(.light)` stopgap is gone |
| **Auto crosses on its own** | PASSED — window temporarily moved to a one-minute slot: ground `#0D0B11` through 08:10:10, `#FFFBF5` from 08:10:19, no interaction |
| Hours in the description | PASSED — "das 20:00 às 06:00" and, after `settings put system time_12_24 12`, "das 8:00 PM às 6:00 AM" |
| Splash on a cold launch | PASSED — dark splash on a *light-mode* device with Dark chosen, via `UiModeManager.setApplicationNightMode` |
| iOS Settings `List` chrome | PASSED — row fills, section headers and footers all legible in dark; this is what #7caa5112 pinned the scheme to avoid |

### Worth knowing

**A single light palette hides on-accent bugs completely.** Two tokens were being used as ink or
fill *on the orange hero* while belonging to the app's surface family — `accentPrimarySoft` for the
hero's captions and `surfaceCard` for the Start studying pill. Both are pale in light mode, so both
looked right; in dark mode the captions turned dark-on-orange and the pill became a hole punched in
the middle of the card. `foregroundOnAccent`/`foregroundOnAccentMuted` are the tokens for that job
and are identical in both modes, because the orange under them does not change.

**The first palette read as a purple app.** Built at the brand plum's own chroma (`#14101C`, ~29%
saturation) every surface was visibly violet. At ~15% the hue is still there — it is what keeps
these from being the default grey — without the app looking like a different product.

**On a dark theme the tonal step is the only edge a card has.** Ground→card started at 1.17:1 and
the study card had no visible boundary at all; a shadow is invisible against near-black. It is
1.37:1 now. The same mistake in a worse form: every `ModalBottomSheet` took `surfacePrimary` — the
ground — so the Speak sheet was exactly the tone of the study screen it had covered.

**The four SRS colours are the light values, unchanged, and that was measured rather than assumed.**
They read 5.3–9.6:1 as ink on the dark surfaces against 2.0–3.6:1 on cream, so there is nothing to
fix; lifting them would only cost the grade buttons, where they are fills under white ink
(2.16:1 → 1.88:1). Only `danger` moved, because `#D92C2C` is 3.9:1 on the dark ground.

**`auto` is a C reserved word.** A Kotlin enum entry named `Auto` crosses to Swift as `auto_`. The
entry is `Scheduled`; the reader still sees "Auto". A `const val` in an `object` is mangled the same
way, which is why `DayNightSchedule` exposes plain `val`s.

**Moving the schedule to a daytime window to watch it work is what found the wrap bug.** A window
that wraps midnight needs an `or` and one that does not needs an `and` — and the `or` alone answers
"night" for every minute of a non-wrapping day. Both orders are handled and tested now.

**Width-adaptive check.** Medium width on the `Pixel_Tablet` AVD (800dp portrait) and expanded on
`emulator-5554` forced to 923dp — the nav rail on `navBarBackground` stays distinguishable from the
ground, the two-pane home reads, and the Speak sheet is a centred raised surface rather than a
full-bleed one. The tablet AVD would not rotate (`user_rotation`, `adb emu rotate` and a `wm size`
override all left it at `w800dp`), so expanded width was reached by widening the phone's window
instead; `WindowWidthClass` reads the window, so it is the same code path.

**The emulator clock cannot be moved** (`date: cannot set date`, and no `su`), so the live crossing
was observed by temporarily narrowing the window to one minute a few minutes ahead, not by
travelling in time.

## Backup dropped from the create-account flow — 2026-09-06, `iPhone 17 Pro` sim (staging)

Signup and the unregistered-key registration now land on **home**; the backup menu is reached only
from the Profile card above sign-out and the Settings row. Journey **22** updated to match, and its
"lands on the BACKUP screen" step is now "lands on HOME, with the nag present".

| Check | Result |
| --- | --- |
| iOS: Settings → `settings_back_up_account` | ✅ PASS — opens the backup sheet over Settings |
| iOS: push a method (`backup_method_file`) | ✅ PASS — `backup_file_passphrase` on screen, so the new private `BackupRoute` enum drives the stack |
| iOS: `signup_back` from the method screen | ✅ PASS — back to the menu with "Encrypted file ✓ Done" still ticked |
| iOS: `backup_later` | ✅ PASS — sheet dismissed onto **Settings**, not onto a second home |
| Android build + `detektAll`, `:shared:jvmTest`, `lintSwift`, iOS `simulator build` | ✅ PASS |
| Android on device | ⚪ **NOT RUN — the emulator is signed out and cannot sign back in.** See below |
| The signup landing itself, on a device | ⚪ **NOT RUN** — needs a hand-issued invite code (the same gap recorded for #147 and #149) |

Two shared tests pin the behaviour instead:
`LocalSignupViewModelTest.aCreatedAccountGoesStraightIntoTheAppRatherThanIntoABackupWall` and
`UnregisteredKeyViewModelTest.aRegisteredKeyGoesStraightIntoTheAppRatherThanIntoABackupWall`.

**`emulator-5554` is signed out again, and both doors back in are shut.** Reaching the Android
backup entry points needs a *Loopky-held* key — the Settings row and the Profile nag are both
absent for the Ring-held account that was signed in — so this session signed out to restore the
staging local-key account by phrase. Neither route came back:

- **Restore by phrase** derives the right pubky (`ma8tmsmd…`, and the account is live: staging Nexus
  answers 200 for it) and then fails inside `sign_in_cookie` — `Establishing new session exchange`
  → `PubkyError`, five attempts, before and after a radio toggle. Nexus reads work throughout, so
  it is not connectivity.
- **Ring sign-in** fails with `AuthRelayUnreachable` ("O serviço de acesso não está respondendo"),
  which is the persistent `httprelay.pubky.app` blocker already recorded above — nine attempts.

One new detail that breaks the scripted cadence in that older note: Android now raises an
**app-chooser** ("Open with Pubky Ring — Just once / Always") between `onboarding_signin` and Ring's
Select Pubky sheet. A blind tap script written before it walks straight past the sheet and starts
tapping the guest shell. Answer **Always** once, then the recorded cadence applies again.

## Copy to edit — Follow is the only offer, and the copy is named (#254) — 2026-09-06

`journeys/26-copy-to-edit.xml`, new with this change. Driven on the **`iPhone 17` simulator**
(staging, account `aefhomm…`) end to end; Android verified as far as a signed-out emulator allows.

Follow and Clone used to sit side by side as equal pills. Clone is gone from deck detail: a deck
you do not own offers **Follow** alone, and the copy is reached by tapping **Edit** on a deck you
*follow* — the one moment owning your own version is the answer. The confirm names what a copy
costs in one sentence and takes the copy's own name, which is mandatory and may not be the
source's.

| Step | Result |
| --- | --- |
| iOS: stranger's deck, not followed → only `deck_follow`; no `deck_clone`, no `deck_edit` | ✅ PASS |
| iOS: follow it → `deck_edit` appears; still no `deck_delete` | ✅ PASS |
| iOS: `deck_edit` raises "Make your own copy?" instead of the editor | ✅ PASS |
| iOS: `deck_clone_confirm` disabled while `deck_clone_title` is empty | ✅ PASS |
| iOS: typing the source's own title (`yyyyyZ` → autocapitalised `YyyyyZ`) → `deck_clone_name_error`, confirm stays disabled | ✅ PASS — the rule is case- and space-insensitive, so this is the interesting case |
| iOS: a distinct name → error clears, confirm enables | ✅ PASS |
| iOS: confirm → "Copying deck…" → **screen moves to the copy** | ✅ PASS — "IN YOUR LIBRARY", "Cloned from Cosmic-Crystal-Panda", Edit + Delete, 3 cards, "Start studying · 3 new" |
| iOS: copy in the library under its new name, beside the original | ✅ PASS |
| Android: guest view of a stranger's deck — `deck_follow` only, full width, no `deck_clone`/`deck_edit` | ✅ PASS (`emulator-5554`) |
| Android: follow → Edit → copy | ⚪ **NOT RUN** — the emulator is still signed out (see the note above); needs an account |
| `:shared:jvmTest`, `detektAll`, `lintSwift`, `:composeApp:assembleDebug`, iOS `simulator build` | ✅ PASS |

**Two bugs that only a device found, both older than this change and both fatal once the copy
became the only route.**

*The copy finished and the screen never came back.* `isCloning` was cleared **inside** the share
offer, which returns early — without clearing it — when "Share on Pubky" is off. With that one
setting off, a copy that had fully succeeded left the screen on "Copying deck…" forever. It was
invisible before because iOS only greyed the Clone button with it; the full-screen progress state
this change added made it fatal, and it would have shown on Android all along. Now cleared on the
success path itself, whatever the setting says, and pinned by
`DeckDetailViewModelTest.a copy clears its spinner even with announcements switched off`.

*The copy showed the deck it was copied from.* `RootView.replaceDeck` swaps the path entry, so
SwiftUI keeps the same view identity and the same `@State` — and `DeckDetailScreen.attach()` bails
on a ViewModel that is already there. The result was the source deck, still saying "Following",
which reads exactly like the copy having failed. `DeckDetailScreen` now re-attaches on
`onChange(of: deckId)`.

**Two smaller findings.** An `.alert`'s `message` is snapshotted when it is presented, so the
"pick a different name" line could never appear as the reader typed — only the confirm button's
disabled state updated, leaving a greyed button with nothing saying why. The iOS prompt is a
`CopyDeckSheet` for that reason; the button-disabling half of the alert did work. And the field
needs `.autocorrectionDisabled()`: on a Portuguese keyboard it turned "Diag copy two" into
"Diga copa tão" before the name ever reached the ViewModel.

Filed while driving this: [#255](https://github.com/jvsena42/loopky/issues/255) — Discover deck
tiles whose cover does not fill the card, and a last row that scrolls under the tab bar.

---

## "What should we call you?" — the name prompt on a nameless profile — 2026-09-06, `iPhone 17 Pro` sim + `Pixel_Tablet` (staging)

An account with no display name falls back to its pubky everywhere it appears, and nothing in the
app said so. Profile now carries a one-time card under the hero — title, one line, **Add name** /
**Not now** — and "Not now" is remembered on the device (`AppPreferences.nameNudgeDismissed`), so
someone who would rather stay a key is asked once. Publishing a name retires it on its own.

Driven on the `ma8tms` staging account, whose name was blanked and restored for the run — on the
iPhone 17 Pro simulator and, contrary to the sign-out note above, on the
**`Pixel_Tablet` emulator, which still holds a session for that same account** (only the phone
emulator `emulator-5554` is signed out).

| Step | Result |
| --- | --- |
| Named account (`Name test`) → no card | ✅ PASS |
| Clear the name and save → hero falls back to `ma8tms`, card appears under it | ✅ PASS |
| `profile_name_nudge_action` opens the Edit Profile sheet | ✅ PASS |
| `profile_name_nudge_dismiss` ("Not now") removes the card | ✅ PASS |
| Relaunch the app → card stays gone | ✅ PASS — the flag is in `NSUserDefaults`, not in state |
| Restore the name → account back to `Name test` | ✅ PASS |
| Android (`Pixel_Tablet`, medium 800dp and expanded 1280dp): named → no card; nameless → card under the hero in both | ✅ PASS — the card lives in `ProfileIdentityPane`, so the two-pane layout gets it in the left column without a second call site |
| Android: `profile_name_nudge_action` opens the sheet with an empty name field; `profile_name_nudge_dismiss` removes the card; force-stop + relaunch → still gone | ✅ PASS |
| Android: restore the name → hero back to `Name test`, no card | ✅ PASS |
| `:shared:jvmTest`, `ciCheck` (detekt + `lintSwift` + iOS klib), `:composeApp:assembleDebug`, iOS `simulator build-and-run` | ✅ PASS |

### Worth knowing

**The edit sheet was seeded one tap late, and the prompt made it dangerous.** `ProfileScreen.swift`
set `editName` from `uiState.editName` *before* calling `onEditProfileClick()`, which is the call
that fills that field — so the first open of the sheet showed empty fields, and the second showed
the values from the first. Saving from a first open published a blank name over a real one. It now
seeds from `uiState.identity` instead. Fixed here rather than filed because "Add name" opens that
same sheet: the prompt would have been an invitation to erase your own name.

**`user_rotation` is inverted on this `Pixel_Tablet` AVD.** Its natural orientation is landscape
(`init=2560x1600`), so `user_rotation 0` is the **expanded** 1280dp landscape and `1` is the
medium 800dp portrait — the opposite of the phone emulators, and `dumpsys display`'s `rotation=`
stayed `0` throughout while `dumpsys window displays`'s `cur=` told the truth. `wm size`,
`adb emu rotate` and `cmd window user-rotation` all did nothing here.

**A container's `.accessibilityIdentifier` overwrites its children's.** With one on the card,
both buttons came back from `snapshot-ui` as `profile_name_nudge` — the journey could tap "Add
name" and "Not now" only by position. The card carries no identifier now; its two buttons do.

---

## "How about a photo?" — the avatar prompt, and where a photo is actually set — 2026-09-06, `iPhone 17 Pro` + `iPad Air 13"` sims + `Pixel_Tablet` (staging)

Loopky cannot set a profile photo: the write it owns is name and bio, and the picture is a file
record uploaded by pubky.app. So the hero avatar gains a **camera badge** that raises a dialog
pointing there, and a named account with no photo gets the same one-time card the name prompt
uses — **only when the name card is not on screen**, with its own device-local flag
(`AppPreferences.avatarNudgeDismissed`).

Driven on the `ma8tms` staging account ("Name test", no photo), whose name was blanked and
restored to check the precedence rule.

| Step | Result |
| --- | --- |
| Camera badge on the hero avatar, own profile only | ✅ PASS — `ProfileHero`'s `onEditAvatar` is null on a friend's profile |
| Tap it → "Change your photo" dialog, "Open pubky.app" / "Not now" | ✅ PASS on both platforms |
| "Open pubky.app" → this environment's profile page in the browser | ✅ PASS — a debug build landed on `staging.pubky.app`, not production |
| Named account with no photo → "How about a photo?" card under the hero | ✅ PASS |
| Nameless account → **only** the name card; the photo card yields | ✅ PASS — blanked the name and saved, then restored it and the photo card came back |
| `profile_avatar_nudge_action` ("Open pubky.app") on the card opens the browser directly, no second dialog | ✅ PASS |
| `profile_avatar_nudge_dismiss` ("Not now") removes the card, badge stays | ✅ PASS |
| Relaunch → card stays gone, badge stays | ✅ PASS on both platforms (SharedPreferences / `NSUserDefaults`) |
| Android `Pixel_Tablet` at medium 800dp and expanded 1280dp | ✅ PASS — badge and card both live in `ProfileIdentityPane`, so the two-pane layout gets them in the left column |
| iOS compact (`iPhone 17 Pro`) and regular (`iPad Air 13"`) | ✅ PASS — same `identityPane` on both |
| `:shared:jvmTest`, `ciCheck` (detekt + `lintSwift` + iOS klib), `:composeApp:assembleDebug`, iOS `simulator build-and-run` | ✅ PASS |

### Worth knowing

**`Modifier.shadow` clips its content, and it cut the badge into a crescent.** The avatar's glow is
drawn by `shadow(elevation, shape = CircleShape)` on a wrapping `Box`, and that overload's `clip`
defaults to `elevation > 0.dp` — so a corner-aligned child is clipped to the circle. The badge
compiled, laid out, and rendered as a pac-man on the device only. The badge now sits in an outer
`Box` beside the shadowed one. Found by screenshot; `assembleDebug` and `snapshot-ui`/`android
layout` all said the button was there and whole.

**`user_rotation` still does nothing on `Pixel_Tablet`, but `adb emu rotate` does.** Contrary to
the note from the name-prompt run above, `adb -s emulator-5554 emu rotate` flipped this AVD between
`cur=1600x2560` (medium) and `cur=2560x1600` (expanded) first time, every time, while
`settings put system user_rotation 0|1` left `cur=` untouched however many times it was set. Use
`emu rotate` and read `dumpsys window displays`'s `cur=`.

**Resetting a nudge flag without signing out:** `adb shell run-as com.github.jvsena42.loopky cat
shared_prefs/loopky.preferences.xml`, strip the `*_nudge_dismissed` lines on the host, `adb push`
to `/data/local/tmp` and `run-as … cp` it back, with the app force-stopped. `run-as sh -c` starts
in `/data/user/0/…` where `shared_prefs` is not visible, so the redirect-in-place forms all fail;
`run-as` without `sh -c` is the one that works.

## The CLI pitch on the file-import screen — 2026-09-06, `Pixel_Tablet` + `iPhone 17` sim (staging)

`journeys/14-file-import.xml`, the empty state only. The screen's premise is that the deck already
exists somewhere else, which is exactly the reader who might rather have an agent write one — so
the idle state now ends with a card pitching `loopky` and a button that copies a ready prompt. The
AnkiWeb row that sat there is gone.

| Step | Result |
| --- | --- |
| Decks → `decks_import_file` → empty state | ✅ PASS — card renders under the size ceiling, no AnkiWeb row |
| Tap `bulk_cli_copy` | ✅ PASS on both platforms — label flips to "Copied" and back after ~2s |
| Paste the clipboard into `paste_input` | ✅ PASS — the whole prompt, line breaks, accents and the quoted `--title` intact |
| Portuguese (`pt-BR`) | ✅ PASS on both — Android via Settings → App language, iOS via `launch-app --launch-args -AppleLanguages "(pt-BR)"` |
| Dark mode | ✅ PASS on both — `surfaceCard` over the ground, soft accent button legible |
| Tablet portrait (1600dp) and landscape (2560dp) | ✅ PASS — card is inside `contentPane(Reading)`, so it caps with the rest |
| `:composeApp:assembleDebug`, `detektAll`, `lintSwift`, iOS `simulator build-and-run` | ✅ PASS |

### Worth knowing

**A soft accent button on a soft accent card is invisible.** The CTA card was first painted
`accentPrimarySoft` to set it apart from the two neutral format cards above it, and both
`LoopkySecondaryButton` and `.loopkySoft` fill with the same token — so "Copy prompt" rendered as
text floating on the card with no pill at all. The card is `surfaceCard` + `borderSubtle` now,
matching the format cards, and the button carries the accent. Nothing in either build reports this;
it is only visible in a screenshot.

**`launch-app --launch-args -AppleLanguages "(pt-BR)"` is the fast way to check an iOS
translation** — no walk through Settings → Loopky → Language, and it lasts exactly one launch, so
there is nothing to restore afterwards.

## Main-screen loading time — 2026-09-07, `Pixel_Tablet` landscape (staging)

Driven end to end for `journeys/03-study-loop.xml`'s Home entry, plus the Decks, Discover and
Profile tabs and deck detail. The complaint was that the app spends too long loading, and most of
it on startup — measured before touching anything, on a **one-deck** account:

| Phase | Before | After |
| --- | --- | --- |
| `loadPersistedSession` (the branded splash) | 5,897 ms | **~75 ms** |
| Home load (session resolved → content) | 2,319 ms | 3,380 ms |
| Launch → something other than a spinner | 8,331 ms | **~1,200 ms** |
| Launch → real due counts | 8,331 ms | 3,450 ms |
| Homeserver round trips per Home load | 9 | 5 |
| `listByAuthor` per Profile load | 2 | 1 |

Home's own load got *longer* on purpose: the FFI's client build and PKARR homeserver resolve
(~1.65 s together, once per process) used to be paid by the self-tag write while the splash was
up, and are now paid by Home's first listing — with the cached library already on screen.

| Step | Result |
| --- | --- |
| Cold start, cache warm | ✅ PASS — greeting, deck tile and hero render at ~1.2 s; hero shows "—" and "Checking what's due…", not a number |
| Counts land | ✅ PASS — hero flips to "2 / 0 of 2 done", tile to "2 cards · 2 new"; nothing moves |
| Tap a deck tile **during** the cached paint | ✅ PASS — deck detail re-fetches and shows Total 2 / Due 0 / New 2, cards listed |
| Start studying **during** the cached paint | ✅ PASS — "1 of 2", card renders |
| Flip → Good → close | ✅ PASS — Home returns "1 card to review", "1 of 2 done", "1 of 20 new cards today" |
| Decks tab | ✅ PASS — "Library · 1", one `listByAuthor` |
| Discover tab | ✅ PASS — trending tags, 12 decks, tile captions still read "24 cards · jvsena42" |
| Tablet portrait, 1600dp (medium) | ✅ PASS — the compact path is `TodaysDecksSection`/`DeckRow`, a different pair of composables from the expanded `TodaysDecksGrid`/`DeckTile`; both were changed and both were checked. Row reads "Session test / 2 cards" with a "—" pill, not "0" |
| Profile tab | ✅ PASS — Decks 1 / Cards 2, one `listByAuthor` (was two) |
| `ciCheck`, `:shared:jvmTest` (1,385), iOS `simulator build` | ✅ PASS |

### Worth knowing

**A fire-and-forget write was the whole splash.** `loadPersistedSession` awaited
`selfTagAsLoopkyUser`, whose result nothing reads. That write is the process's first network call,
so it also paid for building the FFI's HTTP client (1.9 s) and resolving the homeserver over PKARR
(2.3 s) before its own 1.5 s PUT — and the onboarding VM's KDoc says in as many words that the
screen sits on the splash until it resolves. Nothing in a build, a lint or a test reports a `suspend`
call that nobody needed the answer to; it is only visible in a timestamped logcat.

**The cached paint's first draft congratulated you for being caught up.** With the decks cached and
the review state not, `dueToday` and `newToday` are both 0, and `isCaughtUp` is exactly that
predicate — so the launch opened on "🎉 You're all caught up" over a deck with two cards due, then
corrected itself a second later. `Content.countsKnown` now separates "zero" from "not asked yet",
and everything that reads a count checks it. **A cache that fills a field with a default inherits
every meaning the app attaches to that default** — worth checking for anywhere else a placeholder
gets a real value's type.

**A fake that skips the encode step is a fake that tests nothing.** `DeckCacheStore` drops the
chunk table inside `encodeDeckCache`, and the first `FakeDeckCacheStore` held the objects directly
— so its snapshots came back carrying chunks, which is the single thing the store exists to
prevent. It holds the encoded payload now, like every real implementation. The test caught it on
the first run.

**Both `dueCaption` and the tile's own meta row print the card count.** Passing "2 cards" as the
tile's author label while counts were unknown rendered "2 cards · 2 cards". The label is blank in
that state now, and `DeckTile` skips its separator on a blank one instead of leaving it dangling.

### Review round 1 follow-ups — 2026-09-07, `iPhone 17` sim (staging)

Eleven findings on #266. Two were live regressions in this PR, and both were the same shape as the
bug it exists to fix: a screen reporting a number it has no basis for.

| Step | Result |
| --- | --- |
| iOS cached paint, `card_count` plural | ✅ PASS — a one-card deck now reads **"1 card"**, not "1 cards" |
| iOS hero while counts are unknown | ✅ PASS — dash, drawn track, "Checking what's due…"; card height identical to the loaded state, so nothing moves |
| iOS loaded state | ✅ PASS — "20 cards to review", "0 of 20 done", per-deck badges |
| Android cached paint (`Pixel_Tablet`, landscape) | ✅ PASS — "—" and "Checking what's due…", then "1 card to review" |
| Android Profile | ✅ PASS — Decks 1 / Cards 2, still one `listByAuthor` per load |
| `ciCheck`, `:shared:jvmTest` (1,391) | ✅ PASS |

### Worth knowing

**`ProgressView()` with no value renders as a motionless track on iOS.** Measured, not assumed:
two screenshots 0.45 s apart during the cached paint are byte-identical over the bar's band. It is
documented as indeterminate and Android's equivalent genuinely animates, so the two platforms were
not doing the same thing — and the style SwiftUI falls back to is its choice, a spinner among them,
which would change the card's height. The bar is a drawn `Capsule` now: same look, pinned.

**A fake that ignores a scope parameter makes the bug it exists to catch untestable.**
`FakeSrsRepository.countsToday(decks)` unioned `decks` into its own `knownDecks` and answered for
all of them, so Profile passing owned-only and Profile passing owned+followed produced identical
results. The finding-1 test passed against the *unfixed* code until the fake was made to honour
`decks` as the scope the real implementation treats it as. **Write the test, then reintroduce the
bug and watch it fail** — two of the four tests added this round were green against broken code
first, this one and the account-erase one.

**An assertion that runs after sign-out asserts nothing.** `deletingTheAccountEmptiesTheSnapshot`
checked `repo.listCached()`, which returns null with no session — which deleting the account has
just cleared. It passed with the wipe removed. It reads the store directly now.

**`emulator-5554` is a port, not a device, and reading the wrong one produced a wrong finding
that was committed.** The `Pixel_Tablet` this session started had exited, another emulator took
5554, and the app there came up in the guest shell — which was written up here as "the tablet
signed itself out mid-session", with a theory about the staging session ageing out attached. It had
not: booted on its own port it restored its session in 127 ms. Whichever emulator boots first takes
5554, so **resolve the serial with `adb -s <serial> emu avd name` before trusting any reading**,
boot with an explicit `-port`, and pass `-s` / `--device` on every call. A guest shell is
indistinguishable from a real sign-out, so this failure mode looks exactly like a regression.

**A degraded read must not be persisted as authoritative.** Three findings were one idea: both deck
listings deliberately tolerate partial failures, and the snapshot is what the *next* launch paints,
so writing a partial one gives the user a quietly wrong library on every subsequent start with
nothing left to correct it. `Listing<T>(items, complete)` now carries that distinction, and
`loadSubscriptions` no longer memoises an incomplete read for the rest of the process.

### Review round 2 follow-ups — 2026-09-07, `Pixel_Tablet` + `iPhone 17` sim (staging)

The three Low items the approving review left as follow-up material, done on the same branch.

| Step | Result |
| --- | --- |
| Android cached paint → loaded | ✅ PASS — "—" / "Checking what's due…" → "1 card to review"; goal line unchanged across the swap |
| `PubkyPagingTest` (new, 5 cases) | ✅ PASS — the loop's three exits, and which may be believed |
| `ciCheck`, `:shared:jvmTest` (1,398), iOS `simulator build` | ✅ PASS |

**The paging loop reported two truncated listings as complete**, which is finding 3 through a
different door: `listByAuthorListing` fed `complete` straight into the snapshot, so a homeserver
ignoring `cursor` would have cached its first page as the whole library. `PubkyListing` now carries
`truncated` beside `failure` — deliberately separate, because every read *succeeded*, so
`listAllEntries` still hands back what it collected and a broken homeserver shows the user a first
page rather than an error. Only `isComplete` (neither failed nor truncated) may be persisted.

Two smaller ones: the cached paint read `newCardsPerDayGoal` off an unloaded repository and got the
built-in 20, telling a reader whose goal is 50 "0 of 20 new cards today" — there is a
`restoreCachedSettings()` now, the device-mirror half of `ensureLoaded` with no round trip. And
`AccountEraser` calls `DeckCacheStore.clear()` rather than saving an empty snapshot, which did the
visible job and still left a record naming the deleted pubky on the device.

### Worth knowing

**A short page is the end of a listing; a full one that adds nothing is a homeserver ignoring the
cursor.** The old loop collapsed both into one `break` and reported each as success. Separating
them is what makes "is this the whole listing" answerable at all — and the first repository-level
test written for it passed vacuously, because two decks is a short page and can never be truncated.
It seeds a genuinely full page now (`FakePubkyClient.ignoresListCursor`).

**All three fixes this round were confirmed by reintroducing the bug and watching the test fail.**
That is now five of seven tests added across the two review rounds that were green against broken
code on the first attempt — the check is worth doing every time, not when something feels off.

## 2026-09-07 — CLI QR unscannable on Terminal.app (macOS)

`loopky login` printed a QR that Pubky Ring's camera would not pick up at all — reported from a
Terminal.app session on macOS 15.6, `loopky` 0.11.0 from Homebrew. Not a network or relay problem:
the auth URL was minted correctly and the relay poll started. The picture was the failure.

**Measured off the reported screenshot, not inferred.** A column through the top-left finder's left
bar, which should be 98px of unbroken dark:

```
     ####################  ← 22px          ideal: one 98px run
     ++++++                ← 6px background, full width of the code
     ####################  ← 22px
     ++++++
     ####################  ← 22px
```

Terminal.app draws a 28px line box and inks the block glyph into only its top 22px, so 6px of
*background* crossed every text-row boundary. Finder detection is run-length ratios, so a bar in
three pieces is not a finder. The row above claiming this was "scannable in a dark-themed terminal"
was recorded on a terminal that special-cases U+2580 and fills the cell — iTerm2, Kitty and WezTerm
all do, Terminal.app does not, and nothing in a build or a test distinguishes them.

The fix paints the module pair as **foreground + background of a single `▀`** rather than choosing
a glyph against a fixed background, so a run of dark modules is a run of dark backgrounds with no
seam. Same 53×27 footprint — the code did not have to get bigger.

| Verified | Result |
| --- | --- |
| The reported screenshot decodes | ❌ `CIDetector` high-accuracy: no QR on a tight crop of the code |
| Longest unbroken run, finder left bar | ❌ 22px shipped → ✅ 84px fixed (98px nominal; the ends land mid-cell) |
| Real binary output, rasterised at the measured 14×28/22px metrics | ❌ 0.11.0 "NO QR DETECTED" → ✅ fixed decodes to the full `pubkyauth://…&secret=…` |
| `TerminalQrRenderTest` (new, 5 cases) | ✅ PASS — and 2 of them fail against the old renderer, checked by putting it back |
| `:cli:test`, `detektAll` | ✅ PASS |
| Scanned with Pubky Ring on a phone | ✅ PASS — 2026-09-07, Terminal.app on macOS 15.6, the terminal that produced the unscannable code above. Ring picked it up and the login completed. |

### Worth knowing

**A QR that reproduces the matrix exactly can still be unscannable.** Every module in the shipped
render was the right colour at its centre; what was wrong was the 21% of the code's height that
belonged to no module at all. Any future assertion that the code "renders" has to be about
contiguity, which is why the new test asserts on the *background* a cell is painted with and never
on the glyph.

**Colour indices 0–15 are the ones themes remap.** The reporting terminal painted ANSI 47 at 78%
grey. It was not what broke the scan, but it is free to avoid: 16 and 231 are black and white in
every palette that has a 256-colour cube.

**Confirmed on the configuration that failed**, not on a terminal that was going to work anyway —
the same Terminal.app session, scanned with the same phone. That matters more than usual here,
because every measurement behind the fix was Apple's detector on synthetic pixels, which is more
forgiving than a camera at an angle: the rasteriser could have been wrong about Terminal.app in a
way that flattered the fix, and only a real scan closes that gap.

## Success and failure felt the same — the study haptics, remade as waveforms — 2026-09-08, `Medium_Phone` (staging)

Journeys 03 and 09 again, for the reason the 2026-09-04 run could not have caught: an emulator's
`dumpsys vibrator_manager` reported `CONFIRM` and `REJECT` as two *different* prebaked effects,
and on the hardware someone actually holds they came out as the same generic buzz. Prebaked is a
*name* handed to the vibrator HAL — an actuator with no distinct rendering for one of a pair falls
back for both, and "right" stops being distinguishable from "wrong" with nothing anywhere
reporting it.

What survives any actuator is rhythm, so the verdicts are `createWaveform` patterns now
(`StudyHapticPlayer`) and only `Tick` still goes through `performHapticFeedback`. Read back the
same way, from `Recent vibrations`:

| Step | `played:` |
| --- | --- |
| Flip a card | ✅ `Prebaked=TICK`, `constant=6` — unchanged, still the framework's |
| Grade | ✅ same tick, one per grade |
| Speak on a deck with no recogniser (emulator) → "Didn't catch that" | ✅ **Failure**, 261 ms: `55ms@1.00, 45 off, 55ms@0.75, 45 off, 60ms@0.49` |
| Type a wrong answer, Check | ✅ **Warning**, 96 ms: one `95ms@0.65` pulse |
| Grade the last card of a 3-card session | ✅ **Success**, 160 ms: `28ms@0.35, 72 off, 58ms@1.00` |
| Set the goal to 1, grade a new card | ✅ **Celebration**, 392 ms: `35ms@0.33, 35ms@0.55, 35ms@0.76, 140ms@1.00` |

Every one carried `usage: TOUCH`, which is what makes the system mute them for a reader who has
turned haptics off.

### Worth knowing

**The goal's own grade tick is `cancelled_superseded`, and that is the right outcome.** The
celebration lands ~6 ms behind the grade's tick and cuts it off after 6 ms of it, so the thumb
feels the flourish and nothing else. It is not suppressed up front the way the *last* card's tick
is (`tapHaptic`), because whether a grade reaches the goal is not known until the review has been
recorded — predicting it would put the goal predicate in a second place to drift from the first.
Both orderings are fine: a slow write lets the tick finish and the celebration follows it, a fast
one supersedes it.

**A celebration and a completion can never collide.** `celebrateGoalIfOwed` returns early when
there is no card behind the celebration to keep studying, so a goal met on the final grade goes to
"All done!" and its `Success` — never both.

**iOS is written but unrun, again.** `Haptics.swift` gains `Celebration` as three rising
`UIImpactFeedbackGenerator`s placed in time ahead of `.success` — UIKit has no waveform to hand a
pattern to, and a generator is one style for life, so each beat needs its own generator. There is
no macOS on this machine, so the Swift half has not been compiled. It owes a run.

---

## The announce post names the deck and credits its author by pubky — 2026-09-08, `Medium_Phone` (staging)

The follow announcement read `📚 Now following the Loopky deck O deck definitivo… by jvsena42` —
the title ran into the sentence with nothing marking where it began, and the credit was a display
name. A name is self-declared, changeable and absent on most accounts, so two authors can credit as
the same person and one can rename out of a credit already posted. The title is now quoted after a
colon and the credit is the author's pubky.

Driven on the debug APK, signed in as `kfezy1`, following `jvsena42`'s public deck from Discover.

| Step | Result |
| --- | --- |
| Discover → "Biomas e Sub-ecossistemas Brasileiros" (author `3jubjy…3f4rjo`) → Follow deck | ✅ PASS — pill flips to Following |
| The announce prompt's `share_prompt_preview` | ✅ PASS — `Now following the Loopky deck: "Biomas e Sub-ecossistemas Brasileiros" by 3jubjyq4fkh4dq38exrpuo8we6xta8a6rhxnjjzyoo7j4r3f4rjo`, then the manifest URI |
| "Not now" → nothing posted; unfollow to restore the account | ✅ PASS — unfollowing is never announced |
| `:shared:jvmTest`, `detektAll`, `:composeApp:assembleDebug` | ✅ PASS |

**Created and Cloned were not driven** — same `content` getter, different `when` branch, covered by
`DeckAnnouncementTest`. Creating one needs an empty library (journey 24) and publishing a throwaway
deck to a real homeserver, which this run did not do.

Filed while driving this: the preview opened with a bare `B` rather than the deck's 🇧🇷 cover
emoji, so a flag's second regional indicator is being dropped somewhere between the manifest and
`coverEmoji`. Pre-existing and unrelated to the text.
