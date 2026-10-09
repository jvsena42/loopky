# Journeys

28 numbered `*.xml` scripts, driven by hand on a device: `android-cli` on Android, `xcodebuildmcp`
on iOS. The CLI has none — it has no screen — and its equivalent is the `cli-linux` and `cli-binary`
jobs in `.github/workflows/ci.yml`.

**Where a run's result goes: the PR description.** Say which journeys you ran, on which device, and
what happened, including what you could not reach. This file is not a log. Edit it in place, and
only when one of the three lists below changes — a gap closes, a new one opens, or a driving trap is
found. Results up to 2026-10-06 were kept in `RESULTS.md`, which is in this directory's git history
(`git log --follow -- journeys/RESULTS.md`).

## Driving notes

### Android

- **Chain the sign-in taps in journey 01.** Sign in, "I have Pubky Ring on this device", the
  identity row and Authorize go with ~1s, 2s and 1s between them and nothing in between — no layout
  dump, no screenshot. At ~15s from `beginSignIn` to Authorize the flow fails with "the
  authorisation relay isn't responding" on a healthy network; at ~3s it passes first try. Verify
  after the chain, and on failure retry the whole chain.
- **Signing out on the emulator can be a one-way door** when the relay is in that state, so do not
  sign a working account out to test something else.
- **The sign-in deeplink is pinned to the cookie variant** (#321). Ring v1.19's APK ships a
  pre-0.10 `libpubkycore.so` whose parser rejects `pubkyauth://signin_grant` as "Unknown input
  format" (pubky/pubky-ring#375).
- **Ring does not return to Loopky by itself** after approval: its success screen shows OK and never
  fires `x-success`. Switch back by hand. Bitkit as the signer does the same, and also drops a deep
  link that arrives during its cold start, so launch it and let it settle first.
- **The emulator image has no on-device speech recogniser.** `SpeechRecognizer` either reports
  unavailable or accepts a listen and never finishes it, so only the permission path and the
  failure sheet are testable there.
- **`Expect rustls-platform-verifier to be initialized`** turns up intermittently in the Pubky
  stack on the emulator and clears after toggling the radios.
- **`Pixel_Tablet` often refuses to rotate** (`user_rotation`, `accelerometer_rotation` and
  `adb emu rotate` all leaving `rotation=0`) and sometimes boots with no network. `wm density 240`
  on a phone AVD reaches the expanded width class through the same `currentWindowAdaptiveInfo`
  path; say so in the PR when that is what was done.
- **Insets are only wrong in the configurations nobody holds a device in** (#339). Check a layout
  change with a cutout and three-button navigation, rotated both ways:
  `cmd overlay enable-exclusive --category com.android.internal.display.cutout.emulation.tall` and
  `…systemui.navbar.threebutton` (`…navbar.gestural` to go back). The frames to compare against are
  in `dumpsys window | grep 'InsetsSource id'` — `statusBars`, `navigationBars`, `displayCutout`
  and `ime`.
- **An AVD with `hw.keyboard=yes` never shows the soft keyboard**, so every keyboard check passes.
  `settings put secure show_ime_with_hard_keyboard 1` brings it back.
- **The keyboard's state is readable:** `adb shell dumpsys input_method | grep mInputShown`. Poll
  it for a second after an action — a keyboard that flashes is seen in one sample out of twelve.
- **Chrome's "Copy image" wedges the emulator**, with or without Loopky in the foreground, and
  `cmd clipboard clear` does not exist on the image.
- **Measuring jank:** `dumpsys gfxinfo <pkg>` for frame counts and `atrace --async_start view gfx`
  for what a slow frame was waiting on. Bench a control (a tab switch on the same build) first: two
  runs of one build have disagreed by ten points. Architecture.md §5.1 has the flip's numbers.

### iOS

- **A simulator signs in by QR**, scanned from a real phone: Pubky Ring cannot be installed on one.
  A signed-out iPad simulator therefore stays signed out unless a phone is at hand.
- **`ui-automation type-text` is lossy** in a SwiftUI `TextEditor`. Prefer `--replace-existing`,
  re-read the element ref between steps (it changes), and use `key-press --key-code 40` for a
  newline; multi-line `--text` is rejected.
- **The automation cannot reach out-of-process system UI:** the document picker, the share sheet,
  the paste menu and ⌘V, Slide Over and Split View.
- **`BGProcessingTaskRequest.submit` throws on a simulator.**
- **The saved-password "Sign In" sheet ignores synthetic input.** `snapshot-ui` does not list it,
  and AXe's coordinate `tap` and `touch` report success and do nothing. "Use Password" needs a
  hand on the Simulator window. The "Save Password" alert before it is an ordinary alert and taps.
- **A translation is checked with `-AppleLanguages "(ja)"`** as a launch argument.

## Not run on a device yet

Carried over from the last recorded run of each, not re-checked when this file was written. Remove
a line when you close it.

- **07 — Triage edit, on Android.** Passed on iOS 2026-09-01; the Android script has never been run.
- **08 — the system photo picker** (gallery path), and pasting an image as bytes (#168).
- **09 — the Correct/Wrong outcome of Speak**, the `LanguageUnavailable` sheet, and which language
  each engine actually uses. Needs a device with Google speech.
- **15 — a deep link while signed out**, on both platforms. And a `https://loopky.app/…` link
  tapped in Notes on a TestFlight build: a simulator opens them (`simctl openurl`, warm and cold),
  but that delivery has never called `onContinueUserActivity`, so the fallback is unexercised.
- **22 — a real local signup end to end.** It needs a signup token (SMS, sats or an invite code).
  Redemption, the backup screens and the unbacked sign-out warning are covered by unit tests only.
- **28 — the password manager, end to end.** On Android, saving, the read-back check and restoring
  all need a device with a credential provider; no emulator here has one, so only the empty path
  (no provider: the save fails, the restore picker returns nothing) has been driven. On iOS the
  simulator saves and raises the "Sign In" sheet for the right pubky, but nothing has accepted
  that sheet, so the verified state and a restore from a saved entry are undriven — and nothing at
  all has run on a device, which is the only place the `webcredentials` association is enforced.
  Also undriven: whether that association makes Password AutoFill offer the phrase on the
  recovery-file passphrase and Unsplash key fields (a simulator with a hardware keyboard shows no
  QuickType bar), the unchecked state, and a rotation with either sheet up.
- **Backup and restore by file, and Ring export** — the confirmed-write halves end in system UI the
  automation cannot reach.
- **In-app update prompt:** Play's consent screen, the download and the installing restart need a
  build from a Play track; the iOS path needs a real App Store listing.
- **iOS, written on Linux and not driven:** Discover paging and its error state (04, 13), the
  several-topic filter, the deck-detail feature badges, preview → sign in auto-follow, and the
  de/es/fr/it/vi/ja/ko/zh catalogs — the ja pass should include the study grade row, which clipped
  "もう一度" on Android.
- **Tablets:** 04's two-pane browse grid full of decks, in both orientations; the study screen's
  grade column in a signed-in session; Today's hero card on Android at either width class.
- **A landscape tablet hides Check and Give up behind the keyboard** on a typed card. The
  keyboard's action key still checks the answer; Give up needs the keyboard dismissed first.
- **A phone in landscape cannot type on a card** — at `h411dp` the keyboard leaves the card a
  ~40 px strip. A height problem, not a width-class one.
- **CLI:** `card edit` clearing an image and `card check-images`, against staging (#453, #454); a
  write behind a refusing proxy from the macOS and Windows binaries; a host without AVX2; a
  **failed** remote revoke on `signOut`, which tells the user "Signed out" while the token is live.

## Seen once, not explained

- **The first restore-with-phrase on a fresh iOS install** returned "That's not a valid recovery
  phrase"; two later attempts with the identical phrase signed in, and
  `validate_mnemonic_phrase` had answered `true`. Not reproduced since 2026-08-29; do not assume it
  is fixed.
- **#423, a band above the deck-detail cover on iOS.** Not reproduced on 2026-10-01; no change made.
- **A second code scanner after rotating the first one** (04, Android). On `Pixel_Tablet` (API 35),
  scan → rotate with the scanner open → Back once left a fresh scanner on screen, with Loopky
  already told the scan was cancelled. Not reproduced in nine further runs on 2026-10-08, in either
  direction, nor in one on `main`.
