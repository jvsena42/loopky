# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Loopky is a Kotlin Multiplatform flashcards app (iOS + Android), plus `loopky` — a headless CLI on
the same shared logic, so an agent can create and manage decks without a phone screen (#54,
Architecture.md §13). It fuses TinyCards-style playfulness, Anki-style spaced repetition, and Pubky-based decentralized identity/social graph. See `docs/specs.md` (the deck-import spec) and `docs/Architecture.md` (technical architecture — this is the source of truth for module layout, layering, and open questions).

## Session start

A fresh session has none of this in context, so establish it before the first edit:

- **Both device toolchains are set up — use them, don't shell out to raw tooling.** Android is
  `android-cli` (`android run`, `android layout`, `android screen capture`, `adb shell input tap`);
  iOS is the XcodeBuildMCP CLI / `xcodebuildmcp` MCP server (see Build & run below), never bare
  `xcodebuild`, `xcrun` or `simctl`. Their skills — `android-cli` and `xcodebuildmcp-cli` — are
  installed; invoke the relevant one rather than reconstructing commands from memory.
- **`journeys/` is the end-to-end suite, and it is how UI work is verified.** 26 numbered
  `journeys/*.xml` scripts (onboarding, import, study, discovery, signup/restore …) driven by hand
  on a device, with dated outcomes in `journeys/RESULTS.md`. Read `RESULTS.md` first for the current
  known-good/known-broken state — it records blockers a green build says nothing about — then
  re-run the journeys your change touches and update it with the result and the date.

## Build & run

```shell
./gradlew :androidApp:assembleDebug     # Android debug build
./gradlew :shared:allTests              # shared KMP tests
./gradlew :shared:jvmTest               # the same suite on the desktop target — fastest full run,
                                        # and the only one that loads the real libpubkycore
./gradlew :shared:compileKotlinMetadata # fast commonMain compile check
./gradlew :cli:installDist              # build `loopky` -> cli/build/install/loopky/bin/loopky
./gradlew :cli:nativeCompile            # the shipped artifact: one binary, needs GRAALVM_HOME
                                        # (GraalVM for JDK 25). native-image does NOT cross-compile;
                                        # for Linux use:
                                        #   docker build -f cli/Dockerfile --target export \
                                        #     --output type=local,dest=cli/build/native/linux-x86-64 .
./gradlew detektAll                     # lint Kotlin on all subprojects (detekt + compose rules)
./gradlew lintSwift                     # lint iOS Swift (SwiftLint; needs `brew install swiftlint`)
./gradlew ciCheck                       # everything CI runs, in one command — and on a Mac also
                                        # :shared:compileKotlinIosSimulatorArm64 + lintSwift, the two
                                        # checks a Linux runner cannot do. Not :cli:nativeCompile.
```

iOS goes through the **XcodeBuildMCP CLI** (`xcodebuildmcp`, Homebrew: `brew tap getsentry/xcodebuildmcp
&& brew install xcodebuildmcp`), never raw `xcodebuild`/`xcrun simctl`. It is enabled for this repo as a
project MCP server in `.mcp.json`, and the same binary works straight from the shell. The project has a
single scheme, `iosApp`:

```shell
xcodebuildmcp simulator build-and-run --project-path iosApp/iosApp.xcodeproj --scheme iosApp \
  --simulator-name 'iPhone 17'                       # build + install + launch
xcodebuildmcp simulator build --project-path iosApp/iosApp.xcodeproj --scheme iosApp \
  --simulator-name 'iPhone 17'                       # compile-only check
xcodebuildmcp ui-automation snapshot-ui              # semantic UI tree (the iOS `android layout`)
xcodebuildmcp ui-automation tap --element-ref e1     # drive it; also type-text, swipe, screenshot
xcodebuildmcp <workflow> --help                      # discover everything else; don't memorise flags
```

Xcode still works for hands-on debugging (open `iosApp/`), but prefer the CLI so a session can see the
result. The `shared` module is consumed as a static framework (`baseName = "Shared"`, `isStatic = true`) — see `shared/build.gradle.kts`. **iOS runs against a real homeserver, and the core loop is verified** — see the iOS section of `journeys/RESULTS.md`. `iOSApp.swift` starts Koin via `doInitKoin(rawPubkyClient:)`, handing in the Swift `IosPubkyClient` — a dumb `[status, payload]` pass-through, since `kotlin.Result` and suspend functions cannot be implemented from Swift — which `IosPubkyClientAdapter` wraps into the shared `PubkyClient` contract on the Kotlin side. Sign in, paste import, publish, card editing, the study loop (including Type the answer and Listen), profiles, follows and settings all work.

**A simulator signs in by QR, not by deeplink.** Pubky Ring cannot be installed on one, so `pubkyauth://` is a dead end there; the QR sheet is raised automatically because `ringInstalledHere` is false, and you scan it from a real phone. The relay poll underneath is the same either way.

**iOS is at feature parity as of #113.** Guest browsing, signup, restore, backup, tag browse,
bulk import, Speak — all present. Two things worth knowing about how the assisted study modes got
there: **Listen and Speak need no Kotlin binding**. The shared ViewModel emits `Speak` and
`StartSpeechRecognition` effects and takes speech back through `onSpeechResult`/`onSpeechError`, so
both platform halves live in Swift (`SpeechSpeaker`, `SpeechListener`) — `IosSpeaker` exists for
the deck editor's voice list, nothing more, and there is no `IosSpeechRecognizer` because nothing
needs one. Speak's two `Info.plist` usage strings are **not** optional: iOS terminates the app on
the first request without them. iPad and any width but a phone is #173.

**Strings ported from Android need their format specifiers converted.** Java's `%1$s` is a **C
string** on iOS — a Swift `String` handed to it faults inside `strlen` — so it must become `%1$@`.
A Swift `Int` is 64-bit, so `%1$d` must become `%1$lld`. And the *argument order* is part of the
string: `edit_card_context` reads `Card %1$lld of %2$lld · %3$@`, counts before title. None of the
three is visible to the compiler or to SwiftLint; all three segfault at render time.

**Four SwiftUI traps that fail silently, all found by driving the app rather than building it.**
`navigationDestination(item:)` drives **one** destination at a time: assigning a new value while
one is on screen leaves it there, so a screen-to-screen hop does nothing at all. Both stacks in
`RootView` are path-based for this reason — push, never reassign. A bare `.onTapGesture` is not a
control: VoiceOver does not announce it and `snapshot-ui` cannot find it, so anything tappable that
is not a `Button` is reachable by a finger and by nothing else. And a `confirmationDialog`'s
`.cancel` button is detached from its action list and may not render at all — use `.alert` when the
safe option has to be visible. And an `.alert`'s **`title` and `message` are snapshotted when it is
presented**: its buttons' `.disabled` state keeps updating, so a validated field there ends up as a
greyed button with nothing saying why. Anything whose text has to change while the reader types
needs a sheet (`CopyDeckSheet`), not an alert.

**A string whose catalog value contains `%` must never be passed to `Text(_:)` as a bare key** —
it renders the specifier. After porting strings, walk the catalog for values containing `%` and
grep for bare `Text("key")` uses of them; that check has caught three separate instances.

**Five bridge traps worth knowing before touching Swift here.** Kotlin enum entries export lowercased with no separators (`ErrorReason.sessionexpired`, `RingHandoff.thisdevice`), and getting one wrong produces a misleading error pointing at the enclosing view — and an entry whose lowercased name is a **C reserved word** gains a trailing underscore on top of that (`Auto` arrives as `auto_`), so rename the entry rather than ship the underscore. A `const val` in an `object` is mangled the same way, which is why `DayNightSchedule` exposes plain `val`s. Kotlin's `description` property exports as `description_`; plain `.description` compiles and returns the object dump. A sealed interface crosses as an ObjC *protocol*, so casting an erased value to a generic parameter bound to it silently yields `nil` — hold state as `Any?` and match the concrete classes. And a text field must own its own `@State` while typing: binding `get` to state that round-trips through a ViewModel drops characters. Finally, a **`value class` is treated differently on the two sides of a call** — boxed as an element of a `List<Tag>`, but erased to its underlying `String` at a *parameter* position — so handing a `Tag` taken out of state back to a function expecting one passes a Kotlin object where the bridge wants an `NSString`; the pointer is reinterpreted, `value` reads back null, and Kotlin segfaults on a null it believes cannot exist. Give any such function a `String`-taking entry point for Swift (`onTagLabelSelected`) and rebuild the value class on the Kotlin side.

Kotlin lint is detekt (`config/detekt/detekt.yml`, with `detekt-formatting` + `detekt-compose-rules`); run `./gradlew detektAll` (use `--auto-correct` to fix formatting findings). Swift lint is SwiftLint (`iosApp/.swiftlint.yml`, generated `pubkycore.swift` excluded); run `./gradlew lintSwift` or `swiftlint` from `iosApp/`. `shared/src/commonTest` holds a real suite (~1,300 tests per target, 105 files): repository tests over a `FakePubkyClient`, ViewModel tests over `FakeRepositories`, and parser/scheduler tests. Run `./gradlew :shared:allTests`.

## Architecture

**Business logic is shared; UI is native per platform.** This is the core rule — internalize it before making changes.

- `shared/src/commonMain/kotlin/com/github/jvsena42/loopky/` holds all cross-platform code:
  - `domain/model/` — pure Kotlin data classes (`Deck`, `Card`, `ImportDraft`, `SrsState`, `AppError`, etc.). No framework imports.
  - `data/repository/` — the 11 repository interfaces in `Repositories.kt` (Identity, KeyBackup,
    Deck, Card, Signup, Import, Tag, Discovery, Srs, Media, Settings), all implemented under
    `data/repository/impl/` alongside `SessionRevalidatorImpl`, `AccountEraser`, `DeckCompactor`
    and `DeckMediaSweeper`. **Repositories own the business logic** — parsing, triage, publishing,
    SRS grading, follow/unfollow and sign-in/out are methods on the relevant repo, not a use-case
    layer. They are Pubky-only: writes and reads go through `PubkyClient`, with an in-memory
    per-session cache. No SQLDelight — the app is not offline-first, Pubky is the single source of
    truth. `TagRepositoryImpl` also reads trending, tagged subjects and tagger counts from the
    Nexus indexer (`data/nexus/NexusClient`, §7.6). **Which namespace a tag record goes in depends
    on its subject** — a profile in `/pub/pubky.app/tags/`, a deck manifest in `/pub/loopky/tags/`
    — because that decides whether Nexus indexes it at all; read Architecture.md §7.7 before
    touching tag writes.
  - `data/pubky/` — `PubkyClient` interface + DTOs (`ManifestDto`, `CardDto`, `MediaRefDto` in `DeckDtos.kt`, `ProfileDto`) and path helpers (`PubkyPaths`, `Hashing`) that map between domain models and the on-homeserver JSON layout defined in `docs/Architecture.md §8.0`. `SessionProvider`/`MutableSessionProvider` is the tiny read-only abstraction repos use to author writes without depending on `IdentityRepository`. `SessionRevalidator` + `SessionRetry` + `SessionPayloadParser` handle expired-session retry.
  - `data/pubky/PubkyClient.kt` — the single interface that wraps `pubky-core-ffi-fork`. All Pubky calls must route through this. It is a **thin** 1:1 mirror of the FFI surface (keys, mnemonics, recovery, auth, records, DHT). Do not add deck/card concepts here — those belong in repositories. It is a plain interface Koin-binds per platform, not `expect`/`actual`: Android and desktop share `UniffiPubkyClient` (jvmSharedMain, over the JNA bindings); on iOS, Swift's `IosPubkyClient` implements the `[status, payload]` `RawPubkyClient` pass-through and `IosPubkyClientAdapter` wraps it into the contract (see Build & run above) — it is real, and has been driven against a homeserver.
  - `data/storage/` — `SecureSessionStore` interface for persisting the signed-in `Session`, backed by the platform keystore via Liftric KVault (`AndroidSecureSessionStore` wraps EncryptedSharedPreferences; `IosSecureSessionStore` wraps Keychain). This resolves the secret-storage open question — see "Non-obvious rules" below. Non-secret preferences use the separate `AppPreferences` (SharedPreferences / NSUserDefaults) in the same package — don't put a plain setting through the keystore, or a secret through `AppPreferences`.
  - `di/SharedModule.kt` — Koin graph binding repos, ViewModels, and `SessionProvider`; platforms override `PubkyClient` + `SecureSessionStore` via `PlatformModule.{android,ios}.kt`.
  - `presentation/` — KMP ViewModels, one per screen, each extending the multiplatform `androidx.lifecycle.ViewModel` (`viewModelScope`) and exposing `StateFlow<UiState>` + `SharedFlow<UiEffect>` (see "Coding conventions" below). **Implemented** across `onboarding/` (`OnboardingViewModel` + UiState/Effect), `home/` (`HomeViewModel`), `decks/` (`DecksLibraryViewModel`, `DeckDetailViewModel`, `DeckEditorViewModel`, `EditCardViewModel`), `import/` (`PasteImportViewModel`, `PublishDeckViewModel`), and `profile/` (`ProfileViewModel`). Coroutines + Koin are wired (no longer blocked).
- `shared/src/jvmSharedMain/` — the **JVM family**, shared by `androidMain` and `jvmMain`: the
  UniFFI-generated JNA bindings, `java.time`/`java.security` actuals, the `java.util.zip` helpers,
  and `HttpURLConnection`. One copy of `uniffi/pubkycore/pubkycore.kt`, not two — it is generated
  in the fork and checked in here, and a duplicate would have to stay byte-identical forever with
  nothing reporting it when it stopped. The group is declared *through*
  `applyDefaultHierarchyTemplate`; bare `dependsOn` edges silently switch the template off and
  `iosMain` stops belonging to any compilation. Its membership predicate is
  `withCompilations { it is KotlinMultiplatformAndroidCompilation }` and **not** `withAndroidTarget()`,
  which matches only the old `com.android.library` target type: under
  `com.android.kotlin.multiplatform.library` the group is built *without* the Android target in it,
  `androidMain` falls back to `commonMain`, and the generated `uniffi.pubkycore` bindings vanish
  from the Android compile (KT-80409, still open). Both failure modes are quiet, so
  `shared/build.gradle.kts` asserts them in an `afterEvaluate` block — reproduce either by swapping
  the predicate back and watching the check fire.
- `shared/src/jvmMain/` — the desktop half: 0600 JSON file stores under `$XDG_CONFIG_HOME/loopky`,
  a `javax.imageio` `MediaProcessor` that degrades rather than throws, no-op `Speaker`/
  `SpeechRecognizer`/`BackgroundTasks`, and `libpubkycore` under JNA's resource layout
  (`resources/linux-x86-64/`, `resources/darwin-aarch64/`).
- `shared/src/{android,ios}Main/` — platform glue only (Pubky FFI, TTS, speech recognition, haptics, file I/O). Nothing else lives here. Some is `expect`/`actual`; some is a plain interface bound per-platform in Koin (`Speaker`, `SpeechRecognizer`, `BackgroundTasks`, `PubkyRingPresence`), which is the right form when the implementation needs platform context or lifecycle.
- `androidApp/src/main/` — Android app. Compose screens in `ui/`, Koin in `di/`, `MainActivity` as entry point. Uses Jetpack Navigation Compose.
- `cli/` — `loopky`, the headless client. A plain JVM module on `:shared`'s `jvm()` target; it
  resolves repositories from Koin and never touches `presentation/`. See `cli/README.md` for the
  surface and Architecture.md §13 for the decisions.
- `iosApp/iosApp/` — iOS app. SwiftUI screens in `Views/`, `NavigationStack` in `Navigation/`, Koin bootstrap in `DI/`. Compose Multiplatform UI is **not** used for iOS screens.

### Non-obvious rules

- **The CLI's session can only write `/pub/loopky/`, and that is structural, not a setting.**
  `:cli` asks Ring for `/pub/loopky/:rw` and never `DEFAULT_CAPABILITIES`, so an agent session
  cannot write a post, a follow or a profile edit under any bug or any prompt injection — it was
  never handed the capability. Three consequences not to undo. The announce confirmation (#39) is
  gone **by construction** rather than behind a `--no-announce` flag: there is nothing to confirm.
  Deck tags still work, because `TagRepositoryImpl` routes on the *subject* and a deck manifest's
  tag record lives in `/pub/loopky/tags/` (§7.7) — so widening the scope "so tagging works" fixes
  nothing and gives away everything. And `whoami` reports no display name, because that lives in
  `/pub/pubky.app/profile.json`; adding one means widening the scope.
- **`loopky` ships as a single `native-image` binary, and "single" is a property that has to be
  enforced rather than assumed.** `native-image` does **not** fail when it cannot fold a JDK native
  library into the executable — it emits the library *beside* it and reports success. Anything that
  reaches `javax.imageio` pulls in AWT, and on Linux the output becomes eight files including
  `libawt_xawt.so`, an X11 library, in a sandbox with no display; `curl … -o ~/.local/bin/loopky`
  quietly stops being an install with a green build behind it. `:cli:checkNativeImageIsOneFile`
  fails the build, and CI builds the binary on every PR because nothing else in the suite would
  notice. It has already caught this twice — the `--qr-out` PNG writer (now a hand-rolled encoder
  in `TerminalQr`) and the Koin binding for `MediaProcessor` (now `PassThroughMediaProcessor`;
  `initKoinJvm` takes it as a **required** argument for exactly this reason, since a default would
  make `JvmMediaProcessor` reachable again). What that costs is real and is stated rather than
  hidden: `loopky import deck.apkg` (#211) is the one command that uploads **bytes** rather than a
  URL, and with no codec in the binary it uploads them at full resolution where a phone sends
  1024px JPEG — so `--dry-run` reports the byte total against the 1 GB quota and a real run warns
  on stderr. Do not "fix" that by binding `JvmMediaProcessor`; the fix is not available. Find the next one with
  `-H:AbortOnTypeReachable=<type>`. Four more things not to undo: the reachability metadata in
  `cli/src/main/resources/META-INF/native-image/` is hand-curated from a tracing-agent run against
  a *real* homeserver and the community metadata repository is deliberately **off** (it was a
  subset, and it dragged AWT in); **`com.sun.jna.Native` is registered for JNI and deliberately
  not for reflection**, because a reflection entry makes `native-image` reconstruct an
  `Executable` for every method the class declares — `getComponentID(Component)` among them — and
  ten JDK dylibs land beside the macOS binary (dropping the entry's `methods` list is not enough;
  the class entry alone does it, and `dispatch.c` looks those six up with `GetStaticMethodID`
  anyway); `-march=compatibility` is there because the default targets x86-64-v3 and a downloaded
  binary dies with SIGILL on a host without AVX2; and the Linux build runs inside `ubuntu:22.04`
  because a native image links against its builder's glibc and `libpubkycore.so`'s floor is 2.34,
  not the runner's. **The two rows fail differently and CI only built one of them**: that JNA
  entry left Linux clean and macOS at eleven files, and nobody saw it until the row was built on a
  Mac, so build both before believing a metadata change is harmless.
- **On macOS the CLI's session is in the Keychain, and the Linux row's reasoning does not reach
  it.** `desktopSecureSessionStore` picks by OS behind `SecureSessionStore`, so nothing above the
  binding changes (#213). Five things not to undo. It shells out to **`security(1)`** rather than
  calling Security.framework through JNA, because `SecItemAdd` ties the item's ACL to the running
  executable and `loopky update` replaces it — the upgrade would start prompting for a password.
  The write goes through **`security -i`**, which takes its command on *stdin*, because macOS lets
  any local user read another process's `argv`; that is why the payload is Base64 and why a value
  needing quotes fails the write instead. `-T /usr/bin/security` buys no confidentiality against
  anything that can run that tool — what the Keychain buys is encryption at rest and a credential
  that goes with a locked keychain. The **file store stays underneath** as the fallback, the
  migration source for pre-#213 installs and the second thing `clear()` empties, under one rule: a
  usable credential never sits in both places. And an explicit **`LOOPKY_CONFIG_HOME` opts back
  into the file**, because that variable means "keep everything here" and a Keychain item shared
  across every config home would let a disposable one overwrite the real session. `whoami` reports
  both `config_home` and `session_store`, the latter probed rather than asserted.
- **The version check reports and never acts, and it adds no host to anyone's allowlist.** A
  `curl`-installed binary in an ephemeral sandbox never finds out it is stale, and for this client
  that is a *correctness* problem rather than a distribution one: `--json` is a versioned API an
  agent branches on, and an old binary writes an old homeserver shape. So a newer release arrives
  on stderr and as the envelope's `update_available` — a nullable object, never stdout — and
  `loopky update` is the only thing that acts on it. Four things not to undo. The check reads
  `releases/latest/download/latest.json`, the **same path the installer already fetches the binary
  from**, so pointing it at the GitHub REST API "because that is the proper endpoint" adds a host
  to every proxy allowlist and a rate limit, for nothing. Every failure path — no egress, a proxy,
  a rate limit, a release with no manifest yet — returns "nothing to say", and a *failed* check is
  cached for the day like a successful one, or an offline box pays a DNS timeout per invocation.
  `loopky update` **refuses** on a Homebrew or `.deb` install, in a container and on the jar, with
  that tool's own upgrade command and **exit 11, never 0** — a zero tells an agent it updated. And
  the manifest is generated by the release workflow from the binary it just built, not from a
  source constant, so it cannot announce a schema the artifact does not answer with. See
  Architecture.md §13.12.
- **A binary has no build type, so the network has to be an explicit input.** The apps pin it
  (debug → staging, release → production, #42); `:cli` reads `--env`/`LOOPKY_ENV` and defaults to
  **production**, one `PubkyEnvironment` value and never a `--nexus-url` beside a
  `--homegate-url`. A session that disagrees with the requested environment is a hard error with
  its own exit code, because a Nexus read aimed at the wrong network answers *successfully and
  empty* — an agent that writes a tag, reads it back and sees `[]` concludes the write failed and
  retries. Every `--json` result carries the environment and the indexer for the same reason.
- **`--json` is the CLI's verification channel, not its print format.** An agent cannot screenshot
  its way to checking that the picture it attached is the right picture, so reads echo back what
  was *stored* — image refs and tags, not just text. That makes the envelope an API surface:
  versioned `"schema": 1` from the first release, fields may be added, meanings may not change.
- **The CLI is driven by agents, and three of its rules exist because one drove it (#240).** *A
  usage mistake must never exit 1.* A blank id used to reach a homeserver path and come back
  `internal` — the one code an agent retries — so `Args.requireWord` refuses anything that cannot
  be a path segment as `bad_input`; the same shape as `SupportedHost`, refuse before the failure
  can be misclassified. *Anything that blocks on a human takes a bound.* `login --timeout` exits
  13, and it is inside the process because `timeout -s KILL` skips the sweep that deletes a
  `--qr-out` file holding a live auth URL — and it is **not** `withTimeout { complete() }`, which
  waits for the blocking FFI call anyway (measured; see Architecture.md §13.10). *The binary
  describes itself.* `loopky commands --json` emits `CommandSurface.kt` — verbs, operand arity,
  flags, exit codes — so `CommandSurface.kt` is now read by the completion generators **and** by
  agents, and a command added without a table entry is invisible to both. `loopky batch` runs a
  file of operations through the same `dispatch`, so it can never accept something the CLI does
  not; measured at 2.7× on a six-operation sequence (Architecture.md §13.14).
- **Do not add Compose Multiplatform UI code for iOS screens.** The working assumption (see `docs/Architecture.md §12` open question #1) is native SwiftUI on iOS. `androidApp` is Android-only, and since #273 it is a plain `com.android.application` module rather than a KMP one — there is no `commonMain` in it to put a shared screen in.
- **ViewModels live in `shared/commonMain`, not in platform modules.** Both Compose and SwiftUI screens consume the same VMs. No `@Composable` or `ObservableObject` in shared code.
- **Always import symbols; never reference them fully-qualified inline.** Add an `import` at the top of the file (e.g. `import androidx.compose.ui.graphics.Color`) and use the short name, rather than writing `androidx.compose.ui.graphics.Color` inline in a type or call. Applies to both Kotlin and Swift.
- **Native-first UI.** Prefer native platform components — **Material 3 Expressive** (`ShortNavigationBar`, `Scaffold`, `TopAppBar`, etc.; opt in with `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`) on Android, and native SwiftUI / Liquid Glass on iOS — over bespoke custom Composables/Views, so the app feels platform-native. Apply Loopky brand tokens (accent, type, radii) *to* native components rather than rebuilding chrome from primitives; build fully custom only where Loopky's identity needs it and no native equivalent exists (e.g. the study card flip). The custom `LoopkyTabBar` pill has been replaced by a Material 3 Expressive `ShortNavigationBar` (Android) and a native `TabView`/`UITabBar` (iOS).
- **There are two palettes now, and a token that works in one can be wrong in the other.**
  `LoopkyColors.kt` (Android) and `LoopkyColor.swift` (iOS) each carry a light and a dark set;
  `AppTheme` in `AppPreferences` picks between them — System, Auto, Light, Dark — and it is a
  **device** preference, not a synced one. Four things to know before touching either file.

  A colour used *on the accent* must come from the on-accent tokens (`foregroundOnAccent`,
  `foregroundOnAccentMuted`), never from the app's surface family. A single light palette hid
  this completely: `surfaceCard` and `accentPrimarySoft` happen to be white and pale cream, so
  they read as on-accent colours right up until dark mode turned one into a hole punched in the
  orange hero and the other into dark-on-orange captions.

  The dark neutrals carry the brand plum at ~15% saturation, not the ~29% of `#1A1326`. A ground
  built at the brand's own chroma reads as a purple app rather than Loopky in the dark, and
  Material's dark guidance warns off large areas of saturated colour besides. The *steps* between
  them are what separates a raised surface from the one under it — a shadow is invisible on a dark
  ground — so keep ground→card near 1.37:1, and never paint a `ModalBottomSheet` with
  `surfacePrimary`, which is the screen it just covered.

  The four SRS colours are identical in both modes on purpose. They read 5.3–9.6:1 as ink on the
  dark surfaces against 2.0–3.6:1 on cream, so there is nothing to fix, and lifting them would only
  cost the grade buttons, where they are fills under white ink. A dark theme is not an obligation to
  brighten everything — check the ratio before moving a value.

  On Android the splash window is the one surface Compose cannot reach: it is drawn from
  `Theme.Loopky.Starting` before `MainActivity` exists, so it resolves `values-night/` by what the
  *system* believes. `UiModeManager.setApplicationNightMode` (API 31+) is what tells it, and it is
  handed the **resolved** answer rather than the mode, because Auto has no framework equivalent
  Loopky can configure.

- **Every screen is width-adaptive, and a new one has to be too — a phone layout on a tablet is
  the default failure, not an edge case.** Loopky runs on tablets, and nothing about a stretched
  layout raises an error: it compiles, runs, and looks broken. The pieces live in
  `androidApp/.../ui/layout/`:
  - `WindowWidthClass` (compact <600dp / medium <840dp / expanded) comes from
    `currentWindowAdaptiveInfo()` and is published at the `MainActivity` root by
    `ProvideWindowSize`, so `windowWidthClass()` is readable from any composable, including ones
    reached by deeplink.
  - `Modifier.contentPane(max)` caps a block and centres it. The three `PaneWidth` ceilings are
    named for what the content *is* — `Reading` for a column of rows or prose, `Focused` for a
    single-task screen, `Wide` for a tile grid — so they never bite on a phone.
  - `deckGridColumns()` gives 2/3/4 by width. **Never `chunked(2)`** — that is the bug this
    replaced, and it produced 600dp-wide tiles on a landscape tablet.
  - Navigation switches in `MainScreen`: `LoopkyNavRail` at expanded width, `LoopkyTabBar`
    otherwise. Both carry the **same `tab_*` test tags** — renaming them breaks every android-cli
    journey the moment a device is held in landscape, which is the case nobody runs.
  - Home, deck detail, profile and onboarding have real two-pane layouts at expanded width, not
    just narrower content. Prefer that over centring a phone column when a screen would otherwise
    use a third of the height.

  Three traps. **Never ask "is this a tablet"** (`userInterfaceIdiom`, screen size, a build
  config): split-screen and rotation change the answer while the app is running, which is exactly
  why the width class is read from the *window*. Where a screen paints its own background, the cap
  goes **after** the background and scroll modifiers — before them it constrains the surface too
  and the cream stops reaching the edges. And on iOS the same rules apply with SwiftUI's own tools
  (`horizontalSizeClass`, `NavigationSplitView`) — do **not** move `WindowWidthClass` into
  `commonMain`; it is an Android UI concern. See issue #113 for the iPad findings.
- **A deck you don't own offers Follow and nothing else; a copy is reached from Edit.** Follow and
  Clone used to be two pills of equal weight, which asked a reader who had just found a deck to
  choose between two words whose difference they had no reason to know — and made the cheap,
  reversible action look like half a decision (#254). The **Edit** control now appears on a deck
  you *follow* (`Content.canEdit` is `isOwned || isFollowing`) and raises the copy prompt rather
  than the editor. Four things not to undo. The prompt states the two consequences — no author
  updates, progress starts over — in **one sentence**, because a paragraph there is a paragraph
  nobody reads. The copy's title is **mandatory and may not be the source's**: `clone(source,
  title)` fails on a blank one rather than defaulting, `Content.isSourceName` refuses the source's
  name case- and space-insensitively, and the field starts **empty** with the source title as
  placeholder — prefilled, everyone taps straight past it. `isCloning` is cleared on the clone's
  own success path, never inside `offerShare`, which returns early when announcements are off and
  used to strand the screen on "Copying deck…" forever. And the iOS prompt is a `CopyDeckSheet`,
  not an `.alert`: an alert snapshots its `message`, so the "pick a different name" line could
  never appear as the reader typed. See Architecture.md §8.3.
- **Announcing a deck is opt-in, per action.** `DiscoveryRepository.announceDeck` writes a `pubky.app` post so a create/follow/clone reaches the user's followers, gated by `AppPreferences.shareOnPubky` (default on) *and* a confirm prompt each time. Off means never asked and never posted — the gate is on the write itself, not only in the ViewModels. **"Share" here means announcing, never visibility**: published decks are public either way (spec §11), and copy that blurs the two describes a privacy control Loopky does not have. Announcing is best-effort: a failed post must never roll back the deck, follow or clone. See Architecture.md §7.7 for the two things the post record has to get right.
- **Pubky is the source of truth for published decks.** The app is not offline-first in v1 — repos talk directly to `PubkyClient` and keep only an in-memory cache for the session. A persistent SQLDelight cache may come later. There are no private/local-only decks in v1 (spec §11).
- **Homeserver layout is fixed.** Decks published under `/pub/loopky/decks/{deckId}/{manifest.json, cards/{n}.json, media/{sha256}.{ext}}`. Cards are stored in **chunk records** (~100 per record, `CHUNK_SIZE` in `DeckDtos.kt`), and the manifest carries a chunk table + `card_count` — **not** a per-card index. Study order is the card's own sparse `ord`, not manifest position. Sync diffs `chunks[].updated_at`. Single-card writes go through `DeckRepository.upsertCard`/`deleteCard`, which own the chunk write and the manifest patch together — never write a chunk without patching the manifest. Full schemas in `docs/Architecture.md §8.0`. Binary media is written raw via the FFI's `put_bytes_with_session`; reads come back Base64-encoded from the FFI transport and are decoded in `MediaRepositoryImpl`.
- **Card deletes leave holes; a background pass folds them away, and it leaves gaps in the chunk numbering.** `deleteCard` shrinks a chunk's `count` rather than resequencing every later card, so `DeckRepository.compactDeck` reclaims the density off the critical path — one pair of neighbouring chunks at a time, asked for via `BackgroundTasks.scheduleDeckCompaction`, never performed inline. Two traps: a merge is the one chunk write that goes **landing record → manifest → source record** (every other one is chunk-then-manifest), because emptying the source first would leave the manifest pointing at a 404; and folding a pair drops its higher `n`, so the chunk table is **not contiguous** — anything that walks it must read `chunks[].n`, never `0 until chunks.size`. See Architecture.md §8.4.
- **A deck's manifest is written whole, so per-deck writes are serialized.** The manifest carries the entire chunk table, making every write a read-modify-write of the whole record. `DeckRepositoryImpl` guards this with a per-deck `Mutex` (`withDeckWrite`); anything named `…Locked` assumes the caller holds it, and `kotlinx` `Mutex` is **not reentrant**, so take the lock at exactly one level — the public entry point. Never patch the manifest from a `Deck` a caller captured earlier: go through `patchDeckLocked`, which re-reads it inside the lock. A dropped chunk entry orphans the chunk record, and the cards in it leave the deck with nothing reporting an error.
- **A clone's media un-pins itself opportunistically.** Cloning pins card media to the source author's blobs (`absolutizedTo`) rather than re-uploading hundreds of MB. `MediaRepository.get` emits `pinnedFetches` after serving a still-pinned ref, and `DeckRepository.rehostBlob` copies the blob under the clone and rewrites every ref carrying that sha. Both ends are **ownership-guarded** — a *followed* deck's blobs must never be copied under your pubky at a `deckId` you cannot edit. The write-back is the feature: without it the ref keeps its `uri` and every session re-copies the same blob. Re-host writes pass `touchDeck = false` (no `updated_at` bump, no `changes` emission) because nothing user-visible changed. A dangling origin is left dangling, never written into the card. The blobs nobody opens are swept by `rehostPendingMedia`, resumable via a `media_rehost_cursor` on the manifest — **not** derivable from the refs, since a chunk with nothing pinned is never rewritten. See Architecture.md §8.0.
- **507 Insufficient Storage is terminal, and every caller has to treat it that way.** The homeserver enforces a per-user quota (1GB free tier) and refuses writes over it. `isQuotaExceeded`/`ErrorReason.StorageFull` classify it *ahead* of the transient classifiers, `withWriteRetry` never retries it, and the background workers return `Result.failure()` rather than `Result.retry()` — a WorkManager backoff chain against a full disk never converges. Two traps: re-hosting and compaction both *consume* quota, so they cannot dig you out of one; and there is no client-readable usage endpoint, so nothing can warn before the wall. Read Architecture.md §8.5 before touching write error handling.

- **Background work goes through `platform/BackgroundTasks`.** A plain Koin-bound interface, not `expect`/`actual` — WorkManager on Android (`shared/androidMain`, because `PlatformModule.android.kt` binds it and `:shared` cannot see `:androidApp`), `BGTaskScheduler` on iOS. Two traps: a WorkManager-started process has Koin but **no session** (`loadPersistedSession()` is only called from ViewModels, so a worker must call it first or every write fails on "Not signed in"), and don't add a `Configuration.Provider` without removing `WorkManagerInitializer` from the merged manifest. The iOS side is written but unverified. See Architecture.md §9.6.
- **A never-seen card is not "due", and the daily goal never withholds one.** `isDue` requires a
  review state; `isNew()` is the separate question, and counts come back as `DeckCounts(due, new)`.
  The queue serves due reviews before never-seen cards and is **uncapped** — nothing in the
  queue-building path may consult `newCardsPerDayGoal`, because reaching it is announced, never
  enforced. Copy calling it a limit describes a feature Loopky does not have. Study settings
  (goal + Hard/Good/Easy intervals) are a **synced record** at `/pub/loopky/settings.json`,
  not an `AppPreferences` value, because they decide `dueAt`s and review state already syncs;
  `SettingsRepository.update` refuses unless the record has actually been read this session, at the
  repository rather than only in the UI. Read Architecture.md §8.6 before touching any of it.
- **Grading is fixed-interval, not SM-2 — the button says what the card gets.** A grade schedules
  `now + settings.{hard,good,easy}Days`, every review, whatever the card's history; only `Again` is
  fixed in code (`<10m`). Compounding growth (`interval × ease`) was removed because it made the
  settings screen a liar from the second review onwards — a 3-day Good gave 8 days, then 20, while
  the button read 3. Three traps. `easeFactor`/`repetitions` are still tracked but **never read for
  an interval**; reintroducing a read there brings the whole bug back. The settings record's wire
  keys are still `first_*` — renaming them resets every existing record to the defaults on read.
  And `maturityThresholdDays` is the **longest configured interval**, not 21: under fixed intervals
  no card can sit further out than the largest setting, so any higher threshold pins Mastered %
  below 100% on every deck forever with nothing reporting it. See Architecture.md §8.6.
- **Listen and Speak are inert without a declared language pair, and that is deliberate — but
  the gate covers those two only.** A deck
  carries `frontLang`/`backLang` (BCP-47) beside the `listenEnabled`/`speakEnabled` opt-ins, and
  `Deck.speechReady` gates both features on having them. It does **not** gate the third opt-in,
  `typeEnabled` (see the next bullet). The OS engines fall back to the
  **reader's** device locale when given no language, so an undeclared Spanish deck is read aloud
  in an English accent and the spoken reply is graded by an English model — a wrong answer that
  looks like a working feature. Never reintroduce a locale default anywhere in this path: the
  author declares the pair, or the buttons do not appear. Consequences to know: the opt-ins
  default **off** when authoring, since turning one on obliges the author to pick languages; the
  deck editor carries the whole block, because a deck published before the pair existed has no
  other way to gain one; and picking a language also puts an **ordinary** tag on the deck
  (`"spanish"` for `es-ES`, plus the `"language"` umbrella — `LanguageTags`, base subtag, swapped
  when the pair changes), because a reserved label cannot trend or be browsed — Architecture.md
  §7.7 point 4.
- **A listen that produces no answer is reported in the sheet, never dismissed.** Closing the Speak
  sheet on a recognition error is indistinguishable from the app having missed the tap, and the
  common Android errors are ordinary: nothing matched, the engine busy on a fast retry, no model
  for the deck's declared language. So `SpeechEvent.Error` carries its `SpeechError` all the way to
  `SpeakPhase.Failed`, which says which failure it was and offers Try again — or a Close, for the
  two nothing can retry (a refused permission, a missing language model). Four things not to undo.
  `onSpeechError` drops a *late* error the way `onSpeechResult` drops a late transcript, so nothing
  reopens a sheet the reader has moved on from. A retry `cancelAndJoin`s the previous listen before
  starting one, because the old recognizer is destroyed in the flow's `awaitClose` and a second one
  created before that lands is answered with `ERROR_RECOGNIZER_BUSY` — which is what a fast Try
  again *is*. `AndroidSpeechRecognizer` caps one listen at 15s (plus a `stopListening` grace),
  because some engines accept a listen and then never finish it — no result, no error, not even
  after the speech ends — and nothing else ends the flow. And `SpeechError`'s entries are
  PascalCase, like every enum that crosses to Swift here: Kotlin exports them lowercased with the
  separators dropped, so a SCREAMING_SNAKE entry crosses under a name nothing can predict.
- **Typing is the third study opt-in, and the one that needs no language pair.** `Deck.typeEnabled`
  puts an input on the card *back* — under the prompt label, in the space the answer will occupy —
  instead of handing the answer over. Everything the mode adds (input, miss line, Check, Give up,
  the "Correct!" note) lives **on the card**; the grade row and flip hint below it are untouched. Four things not to undo. It is deliberately outside
  `speechReady`: a string comparison has no engine to substitute the reader's locale into, and
  gating it would withhold the one assisted mode from exactly the decks (every import predating the
  pair) that have no other. It defaults **off** everywhere, legacy manifests included, unlike
  listen/speak — a manifest written before the field says nothing about its author's intent. The
  **flip is never blocked**: tapping turns the card as it always has, which is why `answerHidden`
  and `gradesAvailable` sit beside `revealed` on the study state, and why anything acting on "the
  side facing the user" (Listen, Speak) must ask `answerVisible` rather than `revealed` — or it
  reads out the answer the card is withholding. **Only a correct Check opens the card** — a wrong
  or near-miss answer says so and leaves you answering, with what you typed still in the field,
  because handing the answer over on the first slip turns one typo into a lost card and "check the
  accents" is a hint to fix what you wrote, not a verdict; Give up is the escape, and it is always
  right there under Check. And **nothing in the flow picks a grade**: the outcome is reported,
  Give up reports nothing at all, and all four SRS buttons stay equally available — an escape hatch that pre-selects Again is a punishment wearing an
  escape hatch's label. Matching is `AnswerMatcher` with an `AnswerStrictness`: typing is `Strict`
  (accents count — typing them is the point), `SpeakMatcher` is the `Lenient` view. Cards with no
  back text (an image-only Anki answer) or no prompt fall back to tap-to-reveal.

  Three things about the Android screen that only a device shows. The input is drawn from the card's
  own `CardSnapshot` — phase and typed text included — never from the live state: `AnimatedContent`
  keeps the outgoing card composed through its fade *and* re-runs the content lambda, so reading the
  session's phase there drew the **next** card's input, and its `FocusRequester` raised the keyboard
  over a front with nothing to type into. The field then takes focus **after** the flip, not when it
  composes: showing the IME makes SurfaceFlinger allocate a window surface, and asking for it at the
  90° crossing blocked the card's own frames on `eglSwapBuffers` for ~430 ms — the turn dropped every
  other frame. And the study screen is deliberately **not** `imePadding()`ed: the card is a
  `weight(1f)`, so padding the screen resized it every time the keyboard came or went — a 174 px jump
  landing exactly when a checked answer brought the grades in. The card's height clears the keyboard
  on its own and its lower edge simply passes behind it; the rows under the card stay reserved in
  every state for the same reason. Read the flip section of `journeys/RESULTS.md` before touching any
  of it — seven other explanations were measured and all seven were wrong.
- **The study loop's haptics are decided in the ViewModel, never fired on tap by a screen.**
  `StudySessionEffect.Haptic(StudyHaptic)` rides the ordinary effect flow, and the platforms only
  map it — `StudyHapticPlayer` on Android, `UIImpactFeedbackGenerator`/
  `UINotificationFeedbackGenerator` on iOS. The reason is that whether a tap *did* anything is known
  in the VM and nowhere else: a grade arriving while the previous one is still writing, a Check on
  an untypable card and a second reveal are all ignored, and buzzing for one of those tells the
  reader something happened when nothing did. Four things not to undo. **The four verdicts are
  waveforms on Android, not `HapticFeedbackConstants`** — `CONFIRM` and `REJECT` are *prebaked*, so
  an actuator with no distinct rendering for one of them plays the same generic buzz for both and
  right feels exactly like wrong. Rhythm is the channel that survives any actuator, so `Success`
  rises over two pulses, `Warning` is one blunt pulse, `Failure` stutters three times and fades, and
  `Celebration` — the daily goal, and nothing smaller — draws the rise out over four and lands long;
  only `Tick` still goes through `performHapticFeedback`, being an acknowledgement rather than a
  verdict. That path drives the vibrator directly, which is why the manifest needs `VIBRATE` and why
  `StudyHapticPlayer` checks `HAPTIC_FEEDBACK_ENABLED` itself rather than trusting every OEM to mute
  a `USAGE_TOUCH` vibration. iOS has no waveform to hand a pattern to, so its celebration is three
  rising `UIImpactFeedbackGenerator`s placed in time ahead of `.success` — a generator is one style
  for life, so each beat needs its own. The **last card's grade does not tick**: it and
  the completion's `Success` would land a few milliseconds apart and read as one smeared buzz
  rather than two events. And haptics are `tryEmit`ed, not emitted from a launched coroutine — one
  that has to queue for buffer space is better dropped than fired late against the next card, which
  is also why the shared tests have to `runCurrent()` before the first tap.
- **A parenthesized aside is never part of the answer, in any of the three modes.** `"hello
  (formal)"` is a card asking for `"hello"`: the bracket is an editorial note about which sense is
  meant. `AnswerMatcher.stripParentheticals` drops it inside `matches`/`isTypable`, so typing and
  Speak inherit it, and `StudySessionViewModel.onSpeak` drops it before the TTS effect — an engine
  handed the note reads it out as a word. Punctuation stripping alone does **not** cover this: it
  removes the brackets and leaves `formal` in the target. Two things not to undo — a text that is
  *entirely* parenthesized comes back untouched (`matches` refuses an empty target, so stripping
  would make the card unanswerable), and what is *shown* keeps the aside: `SpeakResult.expected`
  and the speak prompt quote the card as written, because the note is the context worth seeing.
- **Speak also reads spelled-out numbers as digits — in the declared language, and only for
  Speak.** A recognizer picks `"10"` or `"ten"` on its own, so `NumberWords.fold` rewrites number
  words to digits on both sides before `SpeakMatcher` compares (`"twenty-one"`, `"treinta y uno"`,
  `"quatre-vingt-dix"`). Three limits are load-bearing. It is keyed on the **side's** language tag
  and folds nothing without one — one merged table would let English `"once"` and `"elf"` answer
  `"11"`, which is Spanish and German. It never folds for **typing**: a deck teaching `"10" → "ten"`
  would accept `"10"` back and stop testing anything. And a form the word list lacks
  (`"ventotto"`, `"einundzwanzig"`, CJK numerals) is left as written — a miss, never a wrong fold.
  Adding a language means adding a table, not a grammar.
- **A reverse card is a second *presentation*, never a second card record — and the pair is graded
  once.** `Deck.reverseEnabled` is the fourth study opt-in (off by default, ungated by the language
  pair, since swapping two sides has no engine to substitute a locale into). It leaves the card list
  alone: `StudySessionViewModel` expands it into `StudyPresentation`s and asks each card again,
  reversed, about five presentations later. Four things not to undo. There is **no reverse due
  date** — review state is keyed by `card_id` alone, so scheduling the directions apart means a
  direction-aware key across `SrsStateDto`, `PendingReview` and `StateKey`, and a stored "+1 minute"
  is overdue forever the moment the app closes; the pairing is session-local and persisted nowhere.
  Both directions share **one** review state, so the pair lands on its **weaker** direction: the
  forward grade is written as it happens (an abandoned session keeps it), and a worse reverse
  re-schedules through `SrsRepository.reviewFrom` **from where the pair started** — never on top of
  the forward result, which would compound two reviews out of one — while `reviewFrom` skips
  `recordStudied` because one card studied twice is one review. Anything asking "which side is
  this?" must go through `promptSide`/`answerSide`, languages included: reading `card.front`/`.back`
  directly is how a reversed card gets typed-checked against the side it just showed you. And the
  gap **shrinks to the daily goal** (`reverseGapFor`) — that is placement, not the capping §8.6
  forbids, because the queue is still every card. Read Architecture.md §8.7 before touching any of
  it.
- **Paste-to-Import is the v1 primary import flow.** The implemented spine is `PasteImportViewModel` (parse + live preview) → `PublishDeckViewModel` (commit to Pubky). Every other import source (AI, OCR, URL) listed in spec §14 must reuse this same spine. Don't build parallel commit flows.
- **Parser rules are prescriptive.** The paste parser (on `ImportRepository`) must follow the exact rule order in spec §6 and the edge-case table in spec §9. Use them as the test matrix.
- **No use-case layer.** Don't introduce `*UseCase` interfaces or a `domain/usecase/` package. If a piece of logic doesn't fit any existing repo, extend the most relevant repo or add a new one — keep the surface area flat.
- **Pubky bindings are UniFFI-generated and checked in.** JVM family: `shared/src/jvmSharedMain/kotlin/uniffi/pubkycore/pubkycore.kt` (one copy, shared by Android and desktop) + `shared/src/androidMain/jniLibs/` for the Android `.so`s. iOS: `iosApp/iosApp/Frameworks/PubkyCore.xcframework` + `iosApp/iosApp/Pubky/pubkycore.swift`. Regeneration steps live in `docs/Architecture.md §7.4`; do not edit the generated files.
- **Session storage is resolved** via `SecureSessionStore` (Liftric KVault → Android Keystore / iOS Keychain). Persist the signed-in `Session` only through this interface — do not wire multiplatform-settings or ad-hoc storage for secrets.
- **The Android app is real and feature-built; iOS is wired but unproven.** Onboarding → home → decks → paste-import → publish → profile all work on Android (Compose screens in `androidApp/src/main/.../ui/`, nav in `ui/nav/`, DI in `di/`). The leftover `Greeting`/`Platform` template stubs still exist in `shared` but are no longer the running UI. iOS has its SwiftUI screens (`iosApp/iosApp/Views/`), a live Koin bootstrap, and the `IosFlowWatcher`/`FlowObserver` state bridge — but nobody has driven it against a real homeserver, so nothing there is verified (see Build & run).

### Package

Root package is `com.github.jvsena42.loopky`. Android namespace is `com.github.jvsena42.loopky` (app) and `com.github.jvsena42.loopky.shared` (library).

## Coding conventions

Prescriptive rules, adapted from the sibling Bitkit apps' `AGENTS.md` to Loopky's
shared-logic / native-UI split. These are the canonical conventions — `docs/Architecture.md`
points here rather than restating them.

### Comments (all languages)

**A comment earns its place by saying something the code cannot.** Comments had grown to a fifth of
every Kotlin line here, most of it the signature restated in prose or a bug's whole history retold;
the heaviest files have been pruned and the rest are still being worked through. Re-growing this is
the easy direction, so when you touch a file, leave its comments under these rules:

- **The name is the documentation.** If a KDoc's first line is the method's name in a sentence,
  delete it — rename the method instead when the name is not carrying its weight. `/** Point at the
  next card and clear everything the current one accumulated. */` on `advanceIndex()` is noise.
- **No KDoc on private declarations**, unless it records something genuinely non-obvious — a lock
  precondition (`**The caller must hold [Deck.id]'s write lock.**`), an ordering constraint, a
  measured number, or a bug the shape exists to prevent. Those are worth keeping and are the reason
  this is a default rather than a ban. A private helper whose name describes it gets nothing.
- **Comment the "why", never the "what".** The load-bearing content is the constraint a reader
  cannot recover by reading the code: what must *not* happen, what was tried and failed, which
  issue number it came from. Everything else is the code said twice.
- **One tight paragraph, not an essay.** Keep the invariant and the issue number; drop the
  retelling of how the bug was found, the alternative that was rejected, and the measurement
  narrative — `git log`/`git blame` holds that, and CLAUDE.md's "Non-obvious rules" holds the
  cross-cutting ones. If a comment needs three paragraphs to explain a decision, it belongs in
  `docs/Architecture.md` with a one-line pointer here.
- **Public interfaces get real KDoc**, because callers do not read the implementation — but the
  same limits apply: the contract and its gates, not a tour.
- **Never leave a KDoc orphaned.** A `/** … */` separated from its declaration by another comment
  or a blank documents the *wrong* thing, silently. Three of those were found during the prune;
  nothing in the build reports them.

The same rules apply to Swift.

### Strings (all languages)

**Loopky ships in English and Brazilian Portuguese, and a new string is not done until both
catalogs have it.** There are four files and every one of them has to be touched together:
`androidApp/src/main/res/values/strings.xml` and `values-pt-rBR/strings.xml` on Android,
and the `en` **and** `pt-BR` localizations of `iosApp/iosApp/Localizable.xcstrings` on iOS. A
missing `pt-BR` entry does not fail any build, any lint or any test — it falls back to English at
render time, so a half-translated screen looks perfectly healthy from a green CI run and only a
device set to Portuguese ever shows it.

Two things that go with it. Never hardcode a user-facing string in a Composable or a SwiftUI
view; and a string ported between platforms needs its format specifiers converted (`%1$s` →
`%1$@`, `%1$d` → `%1$lld` — see Build & run above, where all three ways that segfaults are
written down).

### Shared (Kotlin · `shared/commonMain`)

- **ViewModels extend `androidx.lifecycle.ViewModel`** (the multiplatform JetBrains build) and
  launch work in `viewModelScope`. Do **not** hand-roll a `CoroutineScope`/`SupervisorJob` or an
  `onDispose()` — `viewModelScope` cancels in `onCleared()`. Never use `GlobalScope`; never
  `runBlocking` in suspend code.
- **State:** expose `val state: StateFlow<UiState> = _state.asStateFlow()`. **ALWAYS mutate with
  `_state.update { … }`; NEVER `_state.value = …`** (atomic read-modify-write). Reading
  `_state.value` is fine.
- **Effects:** one-shot effects (navigation, haptics, toasts, clipboard) go through a
  `MutableSharedFlow(extraBufferCapacity = 4)` exposed as `SharedFlow`, separate from state.
- **UiState shape:** `sealed interface` for screens with distinct modes (Loading/Empty/Content/Error);
  a single `data class` with nullable fields otherwise. Keep `UiState`/`Effect`/small helper data
  classes in the same file, after the ViewModel. (Annotate with `@Immutable` only in the *Android*
  layer — shared `commonMain` has no Compose dependency.)
- **Errors:** prefer `runCatching { … }.onSuccess { }.onFailure { }` / `Result` over try/catch; map
  domain `AppError` into the UI state. Prefer `requireNotNull(x) { "…" }` over `!!`.
- **Cancellation:** any `runCatching` whose block calls **suspending** code must be
  `runSuspendCatching` (`util/Coroutines.kt`) — plain `runCatching` catches `Throwable`, so it
  swallows `CancellationException` and turns "the caller went away" into an ordinary failure.
  Same for `mapCatching`/`recoverCatching` over a suspending lambda: fold them into one
  `runSuspendCatching { … }` instead. Plain `runCatching` stays right for pure, synchronous blocks
  (JSON decoding, an `Intent` launch) — they can't observe cancellation. A ViewModel should never
  need an `if (err is CancellationException) return@onFailure` guard; if one looks necessary, a
  suspending `runCatching` upstream is the actual bug.
- **DI:** bind ViewModels with Koin's `viewModel { }` DSL (`org.koin.core.module.dsl.viewModel`) in
  `SharedModule.kt`; repositories stay `single { }`.
- **Imports:** always import; never inline fully-qualified names (Kotlin and Swift).

### Android (Compose · `androidApp`)

- **Stateful/stateless split:** a `…Route` composable resolves the VM via `koinViewModel()` (NOT
  `koinInject`), collects state with `collectAsStateWithLifecycle()`, and consumes effects in a
  `LaunchedEffect`; it delegates to a stateless `…Screen(state, callbacks)`. Pass `viewModel::method`
  references down — never the ViewModel itself.
- **No manual VM disposal.** With `koinViewModel()` + `viewModelScope`, drop the old
  `DisposableEffect { onDispose { viewModel.onDispose() } }` blocks.
- **`modifier: Modifier = Modifier`** is the first optional parameter and is passed **last** at call sites.
- **Navigation goes through `NavController.navigateTo()`** (`ui/nav/NavExt.kt`), which dedups the
  current destination — never raw `navController.navigate(...)`.
- **Immutable collections (recommended, not yet adopted):** prefer `ImmutableList`/`persistentListOf()`
  for `UiState` list fields and Compose params, and annotate `UiState`/token data classes `@Immutable`.
  `kotlinx.collections.immutable` is not yet a dependency — treat this as the target when touching state.
- No hardcoded user-facing strings — use string resources.

### iOS (SwiftUI · `iosApp`)

- **Consume the shared KMP ViewModels.** Do **not** introduce iOS-side `@Observable` business-logic
  objects (unlike bitkit-ios) — Loopky shares its VMs. Bridge `StateFlow`/`SharedFlow` → SwiftUI per the
  Architecture §9.2 decision; call the VM's generated `clear()` on disappear (there is no `onDispose()`).
- Reuse the project's text/components instead of raw `Text().font().foregroundColor()` chains; use
  `.task` (not `.onAppear`) for async tied to a view's lifetime; mutate state on `@MainActor`; use
  self-documenting names (`isLoadingDecks`, not `loading`). Comments follow the **Comments** rules
  above — the name is the documentation, and only the non-obvious "why" is written down.

## Git

- **Branch off `main` before touching anything.** Never commit onto `main` — start a
  `feat/…` / `fix/…` branch first, even for a one-line change.
- **Finish the work by opening a PR.** Push the branch and `gh pr create` against `main`; the
  change isn't delivered while it only exists locally.
- **A change that spans several dependent steps ships as a stack, via `gh stack`** (the
  `github/gh-stack` extension, already installed). One PR per step, each based on the one below it,
  so a reviewer sees one variable per PR instead of a single unreviewable diff — and so an early
  step can merge while the rest is still in review. `gh stack init <branch>` adopts the branch you
  are already on, `gh stack add <branch>` puts the next step on top, `gh stack submit` pushes the
  whole chain and creates or re-bases every PR. Two things a fresh session will not guess: new PRs
  are **drafts** unless you pass `--open`, and after amending or rebasing anything mid-stack you
  must `gh stack submit` again — the child PRs' bases do not update themselves. `gh stack view`
  shows where you are.
- **Always use atomic commits.** Each commit should capture one logical, self-contained change.
  Don't bundle unrelated changes (e.g. a feature plus a refactor plus a formatting sweep) into a
  single commit — split them so each commit can be reviewed and reverted independently.
- **Don't run lint/build after every small edit.** Builds and lint (`./gradlew detektAll`,
  `assembleDebug`, etc.) are slow — run them at the end of a plan or at strategic checkpoints, not
  continuously. Verify at those points, then commit.
- **Verify UI changes on a device with `android-cli`, not just by building.** `android run --apks
  androidApp/build/outputs/apk/debug/androidApp-debug.apk`, drive the screen with `adb shell input
  tap`, read the result with `android layout` (flat JSON list, fastest way to assert on text) and
  `android screen capture -o <file>` for the look of it. A green `assembleDebug` says nothing about
  what the screen renders.
- **For every UI change, ask whether the tablet layout needs the same change — then check it on
  one.** Home, deck detail, profile and onboarding each have *two* layouts (see the width-adaptive
  rule above), so editing the compact path alone silently leaves the wide one stale: the phone
  looks right, the build is green, and the tablet keeps the old behaviour with nothing reporting
  it. Screens with a single layout still need the look checked, because a new full-width row or
  button is only obviously wrong at 1280dp.

  The `Pixel_Tablet` AVD is set up for this: `android emulator start Pixel_Tablet`, then
  `adb shell settings put system accelerometer_rotation 0` and
  `adb shell settings put system user_rotation 0`/`1` to force landscape/portrait — **check both**,
  since they land in different width classes (expanded vs medium) and take different code paths.
  Confirm the rotation actually took with `dumpsys display | grep rotation=` before trusting a
  screenshot; `user_rotation` sometimes does not apply on the first try. Anything gated on width —
  the nav rail, the two-pane layouts, the QR sign-in panel — simply does not exist on a phone, so a
  phone-only pass cannot regress-test it at all.
- **iOS UI changes get the same treatment, through `xcodebuildmcp`.** `simulator build-and-run` to
  install and launch, `ui-automation snapshot-ui` to read the screen (the iOS `android layout`),
  `ui-automation tap`/`type-text`/`swipe` to drive it, `ui-automation screenshot` for the look. A
  compiling Swift file proves nothing about the screen, and iOS has no equivalent of the tablet AVD
  pass — check a compact and a regular size class (an iPhone and an iPad simulator) when the change
  touches layout.
- **Re-run the affected `journeys/*.xml` before opening the PR, and record the outcome.** Add the
  run's date and result to `journeys/RESULTS.md` in the same PR; a journey whose steps your change
  invalidated gets its XML updated too, not left describing a screen that no longer exists.
- Write focused, descriptive commit messages that explain the change and its rationale.
- **Use commit history as context when investigating why a change was made.** Before changing or
  reverting code, check `git log`/`git blame` (e.g. `git log -p <file>`, `git blame -L`) — the commit
  message often records the rationale and avoids re-introducing a bug a prior commit fixed.

## Where to read before starting work

- `docs/Architecture.md` — always. §4 (shared layering), §7 (Pubky, Nexus tag indexing, Homegate signup), §8 (homeserver layout, chunking, quota, SRS), §12 (what is still open).
- `docs/specs.md` §5–§10 — for any import/triage/commit work; §6 and §9 are the parser test matrix.
- `journeys/RESULTS.md` — before any UI or flow work, for what currently passes on a device and what
  is a known blocker; the matching `journeys/*.xml` is the script to re-run afterwards.
