import Combine
import Shared
import SwiftUI

/// The user's theme choice, resolved to what `preferredColorScheme` wants.
///
/// Read straight from `AppPreferences` rather than through a ViewModel, because this is the app's
/// root: `themeMode` is a `StateFlow` precisely so the first frame can be painted in the user's own
/// palette instead of a default that corrects itself a frame later. The Settings picker writes the
/// same preference through `SettingsViewModel`, so the two stay in step without either knowing
/// about the other.
@MainActor
final class ThemePreference: ObservableObject {
    /// `nil` means System — the one value that stays right when the device flips at sunset, so it
    /// is expressed by declining to override rather than by resolving the device's answer here.
    @Published private(set) var colorScheme: ColorScheme?

    private var theme: AppTheme = AppTheme.system
    private var sink: FlowEffectSink?
    private var clock: AnyCancellable?

    init() {
        let preferences = IosDependencies.shared.appPreferences()
        apply(preferences.themeMode.value as? AppTheme)
        sink = FlowEffectSink(preferences.themeMode) { [weak self] value in
            MainActor.assumeIsolated { self?.apply(value as? AppTheme) }
        }
    }

    private func apply(_ theme: AppTheme?) {
        self.theme = theme ?? AppTheme.system
        resolve()
        // A ticking clock only for Auto — the other three answer the same thing all day.
        clock = self.theme == AppTheme.scheduled ? Self.everyMinute { [weak self] in self?.resolve() } : nil
    }

    private func resolve() {
        switch theme {
        case AppTheme.light: colorScheme = .light
        case AppTheme.dark: colorScheme = .dark
        case AppTheme.scheduled: colorScheme = DayNightSchedule.shared.isNightNow() ? .dark : .light
        default: colorScheme = nil
        }
    }

    /// Polls rather than scheduling the exact crossing: a timer set hours out does not fire while
    /// the app is suspended, so the evening would arrive whenever iOS next woke it. A minute of
    /// staleness in a foreground app is the cheaper mistake.
    private static func everyMinute(_ tick: @escaping () -> Void) -> AnyCancellable {
        Timer.publish(every: 60, tolerance: 10, on: .main, in: .common)
            .autoconnect()
            .sink { _ in tick() }
    }
}
