# Loopky

Loopky is a free, open source flashcards app with spaced repetition, and a full replacement for
Anki for most learners. Import your Anki decks, study on your phone or tablet, and manage decks
from your computer with the command line tool. It reads cards aloud, checks your pronunciation, and
shares decks with your class through one link.
[Get it on Google Play](https://play.google.com/store/apps/details?id=com.github.jvsena42.loopky) ·
[loopky.app](https://loopky.app)

## Features

- **Spaced repetition** with Again, Hard, Good and Easy. Each button shows the interval it gives,
  and you can change the intervals.
- **Import** Anki `.apkg` files, `.txt` and `.csv`, or paste a list straight in.
- **Listen, Speak and Type** modes in 35 languages: hear the card, say the answer and have your
  pronunciation checked, or type it.
- **Reverse cards and images** on either side of a card.
- **Two-pane tablet layouts**, not a stretched phone screen.
- **Share decks by link.** Publish a deck, follow and copy other people's, and find new ones in
  Discover.
- **A desktop command line tool** to manage decks and cards in bulk, or to let an AI agent build
  them for you — see [`cli/README.md`](./cli/README.md).
- **No ads and no analytics.**

## Guides

- [Switch from Anki](https://loopky.app/anki-alternative/)
- [Loopky for language learning](https://loopky.app/language-learning/)
- [Loopky for medical students](https://loopky.app/medical-students/)
- [Loopky for teachers](https://loopky.app/teachers/)
- [AI flashcards](https://loopky.app/ai-flashcards/)
- [Compare Loopky with Anki and Quizlet](https://loopky.app/compare/)
- [FAQ](https://loopky.app/faq/)

## How it works

Loopky blends Duolingo TinyCards' playfulness, Anki's spaced repetition, and Pubky's decentralized
identity and social graph. iOS + Android, built with Kotlin Multiplatform.

There is no Loopky account and no Loopky server. You sign in with a key held by
[Pubky Ring](https://pubky.org), and your decks and study progress are written to a Pubky
homeserver you hold the key to.

- **Architecture:** [`docs/Architecture.md`](./docs/Architecture.md) — module layout, layering,
  homeserver data model, Pubky/Nexus integration, what is still open. Start here.
- **Import spec:** [`docs/specs.md`](./docs/specs.md) — the paste-to-import flow and the parser
  rules the test suite is written against.
- **Privacy:** [`PRIVACY.md`](./PRIVACY.md) — what reaches a homeserver, and whose it is.

---

## Screenshots

Captured against a live Pubky homeserver on both platforms. Full-resolution files live in
[`screenshots/`](./screenshots) and double as the store asset sets.

### Android

Captured on the v0.6.0 debug build — a Pixel phone emulator (1080×2400) and the `Pixel_Tablet`
emulator in landscape (2560×1600), the two width classes the adaptive layouts target.

#### Tablet

Home, deck detail and onboarding are real two-pane layouts at expanded width, with a navigation
rail in place of the tab bar — not a stretched phone column.

<table>
<tr>
<td align="center"><img src="screenshots/tablet/01-home.png" width="380" alt="Loopky on an Android tablet: today's queue beside the deck grid"><br><sub><b>Today</b> — queue beside the deck grid</sub></td>
<td align="center"><img src="screenshots/tablet/02-decks.png" width="380" alt="Loopky on an Android tablet: the deck library in four columns"><br><sub><b>Library</b> — four columns at expanded width</sub></td>
</tr>
<tr>
<td align="center"><img src="screenshots/tablet/03-deck-detail.png" width="380" alt="Loopky on an Android tablet: deck details beside the full card list"><br><sub><b>Deck</b> — metadata beside the full card list</sub></td>
<td align="center"><img src="screenshots/tablet/04-study-front.png" width="380" alt="Loopky on an Android tablet: studying a card, with Listen and Speak buttons"><br><sub><b>Study</b> — the prompt side</sub></td>
</tr>
<tr>
<td align="center"><img src="screenshots/tablet/05-study-answer.png" width="380" alt="Loopky on an Android tablet: the grade buttons beside the card"><br><sub><b>Grade</b> — grades move beside the card</sub></td>
<td align="center"><img src="screenshots/tablet/06-discover.png" width="380" alt="Loopky on an Android tablet: Discover, with trending tags and decks to follow"><br><sub><b>Discover</b> — trending tags and follows</sub></td>
</tr>
<tr>
<td align="center"><img src="screenshots/tablet/07-profile.png" width="380" alt="Loopky on an Android tablet: your profile and study totals"><br><sub><b>Profile</b> — identity and totals</sub></td>
<td align="center"><img src="screenshots/tablet/00-onboarding.png" width="380" alt="Loopky on an Android tablet: sign-in on one side, what Loopky is on the other"><br><sub><b>Sign in</b> — split across two panes</sub></td>
</tr>
</table>

#### Phone

<table>
<tr>
<td align="center"><img src="screenshots/phone/00-onboarding.png" width="170" alt="Loopky on an Android phone: sign in with Pubky Ring, no account or password"><br><sub><b>Sign in</b><br>A key, not an account</sub></td>
<td align="center"><img src="screenshots/phone/01-home.png" width="170" alt="Loopky on an Android phone: today's queue of cards to review"><br><sub><b>Today</b><br>The daily queue</sub></td>
<td align="center"><img src="screenshots/phone/02-decks.png" width="170" alt="Loopky on an Android phone: the deck library"><br><sub><b>Library</b><br>Your decks</sub></td>
<td align="center"><img src="screenshots/phone/03-deck-detail.png" width="170" alt="Loopky on an Android phone: a deck with its cover, tags, study stats and cards"><br><sub><b>Deck</b><br>Stats, tags, cards</sub></td>
<td align="center"><img src="screenshots/phone/04-study-front.png" width="170" alt="Loopky on an Android phone: studying a card, with Listen and Speak buttons"><br><sub><b>Study</b><br>Listen and Speak</sub></td>
</tr>
<tr>
<td align="center"><img src="screenshots/phone/05-study-answer.png" width="170" alt="Loopky on an Android phone: the answer side, with Again, Hard, Good and Easy buttons showing each interval"><br><sub><b>Grade</b><br>The button says the interval</sub></td>
<td align="center"><img src="screenshots/phone/06-discover.png" width="170" alt="Loopky on an Android phone: Discover, with trending tags and decks to follow"><br><sub><b>Discover</b><br>Decks and tags</sub></td>
<td align="center"><img src="screenshots/phone/07-profile.png" width="170" alt="Loopky on an Android phone: your profile and study totals"><br><sub><b>Profile</b><br>Your Pubky identity</sub></td>
<td align="center"><img src="screenshots/phone/08-paste-import.png" width="170" alt="Loopky on an Android phone: importing a deck by pasting a list of cards"><br><sub><b>Paste import</b><br>Separator auto-detected</sub></td>
<td align="center"><img src="screenshots/phone/09-signup.png" width="170" alt="Loopky on an Android phone: creating an account by SMS, Lightning or invite code"><br><sub><b>Signup</b><br>SMS, sats or invite</sub></td>
</tr>
</table>

> Settings and the recovery-phrase screens are `FLAG_SECURE`, so they cannot be screenshotted —
> that is deliberate, and why they are absent here.

### iOS

Captured on the **Release** build (production Nexus + Homegate) against a live homeserver, on the
iPhone 17 Pro Max simulator (1320×2868) and the iPad Pro 13-inch simulator (2064×2752) — the two
sizes App Store Connect asks for. The SwiftUI screens are native, not Compose Multiplatform, and
the iPad has its own regular-size-class layouts rather than a stretched phone column.

#### iPad

<table>
<tr>
<td align="center"><img src="screenshots/ios-tablet/01-home.png" width="380" alt="Loopky on an iPad: today's queue beside the deck grid"><br><sub><b>Today</b> — queue beside the deck grid</sub></td>
<td align="center"><img src="screenshots/ios-tablet/02-decks.png" width="380" alt="Loopky on an iPad: the deck library in several columns"><br><sub><b>Library</b> — multi-column at regular width</sub></td>
</tr>
<tr>
<td align="center"><img src="screenshots/ios-tablet/04-study-front.png" width="380" alt="Loopky on an iPad: studying a card, with Listen and Speak buttons"><br><sub><b>Study</b> — the prompt side</sub></td>
<td align="center"><img src="screenshots/ios-tablet/05-study-answer.png" width="380" alt="Loopky on an iPad: the grade buttons beside the card"><br><sub><b>Grade</b> — grades move beside the card</sub></td>
</tr>
<tr>
<td align="center"><img src="screenshots/ios-tablet/06-discover.png" width="380" alt="Loopky on an iPad: Discover, with trending tags and decks to follow"><br><sub><b>Discover</b> — four columns of decks and tags</sub></td>
<td align="center"><img src="screenshots/ios-tablet/07-profile.png" width="380" alt="Loopky on an iPad: your profile and study totals"><br><sub><b>Profile</b> — identity and totals</sub></td>
</tr>
<tr>
<td align="center" colspan="2"><img src="screenshots/ios-tablet/00-onboarding.png" width="380" alt="Loopky on an iPad: sign in by scanning a QR code with Pubky Ring on your phone"><br><sub><b>Sign in</b> — a simulator has no Pubky Ring, so the QR handoff is raised automatically</sub></td>
</tr>
</table>

#### iPhone

<table>
<tr>
<td align="center"><img src="screenshots/ios-phone/01-home.png" width="170" alt="Loopky on an iPhone: today's queue of cards to review"><br><sub><b>Today</b><br>The daily queue</sub></td>
<td align="center"><img src="screenshots/ios-phone/02-decks.png" width="170" alt="Loopky on an iPhone: the deck library"><br><sub><b>Library</b><br>Your decks</sub></td>
<td align="center"><img src="screenshots/ios-phone/03-deck-detail.png" width="170" alt="Loopky on an iPhone: a deck with its cover, tags, study stats and cards"><br><sub><b>Deck</b><br>Stats, tags, cards</sub></td>
<td align="center"><img src="screenshots/ios-phone/04-study-front.png" width="170" alt="Loopky on an iPhone: studying a card, with Listen and Speak buttons"><br><sub><b>Study</b><br>The prompt side</sub></td>
</tr>
<tr>
<td align="center"><img src="screenshots/ios-phone/05-study-answer.png" width="170" alt="Loopky on an iPhone: the answer side, with Again, Hard, Good and Easy buttons showing each interval"><br><sub><b>Grade</b><br>Listen, Speak, and the interval on the button</sub></td>
<td align="center"><img src="screenshots/ios-phone/06-discover.png" width="170" alt="Loopky on an iPhone: Discover, with trending tags and decks to follow"><br><sub><b>Discover</b><br>Decks and tags from people you follow</sub></td>
<td align="center"><img src="screenshots/ios-phone/07-profile.png" width="170" alt="Loopky on an iPhone: your profile and study totals"><br><sub><b>Profile</b><br>Your Pubky identity</sub></td>
</tr>
</table>

---

## Status

**Android is feature-built end to end.** Onboarding and Pubky Ring sign-in, homeserver signup
(SMS / Lightning / invite code), the daily study queue, the SRS study loop with Listen / Speak /
Type, deck library and editor, paste import and bulk file import (`.txt` / `.csv` / `.apkg`),
publishing, discovery and tag browse, profiles and follows, and settings.

**iOS is at feature parity.** Browse without an account, then sign in with Pubky Ring — by
deeplink, or by scanning a QR from the phone that holds your key — or create an account on a
homeserver (SMS, Lightning or invite code), restore one from a recovery phrase or file, and back a
key up three ways. Then the daily study queue, the SRS study loop with Listen / Speak / Type and
reverse cards, the deck library and editor, paste import and bulk file import
(`.txt` / `.csv` / `.apkg`), publishing, discovery and tag browse, profiles and follows, and
settings including the synced study intervals. Driven against a real homeserver on the iPhone 17
simulator; see the iOS sections of [`journeys/RESULTS.md`](./journeys/RESULTS.md), which also
record what could not be reached there and why.

iPad and every width but a phone is [#173](https://github.com/jvsena42/loopky/issues/173).

Roughly 1,300 shared tests run on every PR.

---

## Architecture at a glance

**Business logic is shared; UI is native per platform.** That is the one rule to internalize.

- `shared/` — KMP module holding domain models, repositories (which own the business logic — there
  is no use-case layer), and the ViewModels both platforms consume. Platform glue (Pubky FFI, TTS,
  speech recognition, background work) is either `expect`/`actual` or a Koin-bound interface.
- `androidApp/` — the Android app. Jetpack Compose screens, Navigation Compose, Koin. Android-only,
  and a plain `com.android.application` module rather than a KMP one; Compose Multiplatform UI is
  not used for iOS.
- `iosApp/` — the iOS app. SwiftUI screens, `NavigationStack`, Koin bootstrap.

Pubky is reached through one interface, `PubkyClient`, over the UniFFI bindings generated by
`pubky-core-ffi-fork` and checked in. Published decks live on the author's homeserver as a manifest
plus chunked card records; the homeserver is the source of truth and the app is not offline-first.
Global questions a single homeserver cannot answer — trending tags, search, "who else uses Loopky"
— go to the Pubky Nexus indexer.

### Module layout

```
loopky/
├── shared/
│   └── src/
│       ├── commonMain/kotlin/com/github/jvsena42/loopky/
│       │   ├── domain/        # models (pure Kotlin, no framework imports)
│       │   ├── data/          # repositories, PubkyClient, Nexus, storage
│       │   └── presentation/  # ViewModels (StateFlow + SharedFlow)
│       ├── commonTest/        # the whole automated suite
│       ├── androidMain/       # Pubky FFI, TTS, speech, WorkManager, Koin
│       └── iosMain/           # Pubky adapter, TTS, speech, BGTaskScheduler, Koin
│
├── androidApp/src/main/kotlin/com/github/jvsena42/loopky/
│   ├── ui/                    # Compose screens + navigation
│   ├── LoopkyApp.kt           # Application; starts Koin
│   └── MainActivity.kt
│
├── iosApp/iosApp/
│   ├── Views/                 # SwiftUI screens
│   ├── Navigation/            # NavigationStack
│   ├── DI/                    # Koin bootstrap + Flow→SwiftUI bridge
│   ├── Pubky/                 # IosPubkyClient + generated bindings
│   └── iOSApp.swift
│
├── cli/                       # `loopky`, the headless client — a GraalVM binary on :shared's
│                              # jvm() target, so an agent can drive Loopky without a screen
│
└── journeys/                  # scripted end-to-end journeys + results
```

### Stack

| Concern | Choice |
|---|---|
| UI (Android) | Jetpack Compose + Material 3 Expressive |
| UI (iOS) | SwiftUI + NavigationStack |
| Shared logic | Kotlin Multiplatform (`commonMain`) |
| DI | Koin |
| Async | Coroutines + Flow; hand-rolled Swift bridge (`IosFlowWatcher` / `FlowObserver`) |
| Persistence | Pubky homeserver + in-memory session cache — no local database |
| Secrets | Liftric KVault → Android Keystore / iOS Keychain |
| Identity / social | Pubky (`pubky-core-ffi-fork`, UniFFI) + Nexus indexer for global reads |
| Navigation | Per-platform native |
| Lint | detekt (Kotlin) · SwiftLint (Swift) |

Android `minSdk` 29, `targetSdk` 36.

---

## Build and run

### Android

```shell
./gradlew :androidApp:assembleDebug
```

Or use the run configuration from your IDE's toolbar.

### iOS

Open [`/iosApp`](./iosApp) in Xcode and run. `shared` is consumed as a static framework.

### Tests and checks

```shell
./gradlew :shared:allTests              # shared KMP tests (~1,300)
./gradlew :shared:compileKotlinMetadata # fast commonMain compile check
./gradlew detektAll                     # Kotlin lint (add --auto-correct to fix formatting)
./gradlew lintSwift                     # Swift lint (needs `brew install swiftlint`)
```

CI runs detekt, the unit tests, and an Android debug build on every PR.

End-to-end coverage is manual and scripted: [`journeys/`](./journeys) holds 25 numbered journeys
driven on a device with `android-cli`, with results and dates in
[`journeys/RESULTS.md`](./journeys/RESULTS.md). A green build says nothing about what the screen
renders.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html).
