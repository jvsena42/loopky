import SwiftUI
import Shared

/// Which Pubky network this build talks to: the Homegate that mints signup tokens, the homeserver
/// those tokens are valid on, the pubky.app web client, and the Nexus indexer the social half of
/// the app reads. `PubkyEnvironment` on the Kotlin side carries all four, so this is the only
/// value to pick — a release build cannot end up reading one network while publishing to another
/// (#42, #205).
private enum PubkyEnv {
    #if DEBUG
    static let name = "Staging"
    #else
    static let name = "Production"
    #endif
}

@main
struct iOSApp: App {
    init() {
        // Swift hands the dumb [status, payload] FFI pass-through to Kotlin; the shared
        // layer wraps it into the PubkyClient contract (IosPubkyClientAdapter).
        PlatformModule_iosKt.doInitKoin(
            rawPubkyClient: IosPubkyClient(),
            // iOS ships no build-time Unsplash key, so web image search asks the user for one.
            // Their key is kept in the Keychain (IosUnsplashKeyStore), not here.
            unsplashFallbackKey: "",
            pubkyEnvironmentName: PubkyEnv.name
        )
    }

    @StateObject private var theme = ThemePreference()

    var body: some Scene {
        WindowGroup {
            // The one place the window's width is published from. Every adaptive decision below —
            // two-pane layouts, grid columns, the sign-in handoff — reads it from the environment,
            // so all of them re-answer on rotation and on a Split View divider being dragged.
            RootView()
                .provideWindowSize()
                // The single point where the user's choice is expressed, and it has to be at the
                // window root: native chrome — a `List`'s row fills, an alert, a sheet, a menu —
                // takes its colours from the scheme rather than from `LoopkyColor`, and setting
                // this on a screen would leave every presented surface answering to the device
                // instead. `LoopkyColor` is dynamic, so both halves resolve from the same traits.
                // `nil` is System, which declines to override rather than resolving it here.
                .preferredColorScheme(theme.colorScheme)
        }
    }
}
