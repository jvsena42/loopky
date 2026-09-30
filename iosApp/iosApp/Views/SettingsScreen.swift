import SwiftUI
import Shared

/// VM-driven wrapper around `SettingsView`.
///
/// `settingsViewModel()` used to resolve with `appVersion` blank: the Koin binding reads it from
/// `params.getOrNull() ?: ""` and the accessor passed none, so the About row would have shown an
/// empty version. The version comes from the bundle instead.
struct SettingsScreen: View {
    var onSignedOut: () -> Void = {}
    var onBackUpNow: () -> Void = {}

    @Environment(\.openURL) private var openURL

    @State private var viewModel: SettingsViewModel?
    @State private var uiState: SettingsUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var toast: String?

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
            ?? NSLocalizedString("settings_app_version_unknown", comment: "")
    }

    var body: some View {
        SettingsView(
            state: viewState,
            onCopyPubky: { viewModel?.onCopyPubkyClick() },
            onCopyHomeserver: { viewModel?.onCopyHomeserverClick() },
            onShareOnPubkyChanged: { viewModel?.onShareOnPubkyChange(enabled: $0) },
            onThemeChanged: { viewModel?.onThemeModeChange(theme: $0) },
            onGoalChanged: { viewModel?.onNewCardsGoalChange(goal: Int32($0)) },
            onIntervalChanged: { grade, days in
                viewModel?.onIntervalChange(grade: grade.shared, days: Int32(days))
            },
            onSaveUnsplashKey: { viewModel?.onSaveUnsplashKey(key: $0) },
            onRemoveUnsplashKey: { viewModel?.onRemoveUnsplashKey() },
            onDismissUnsplashError: { viewModel?.onUnsplashKeyErrorDismissed() },
            onDeleteAccount: { viewModel?.onDeleteAccountClick() },
            onConfirmDeleteAccount: { viewModel?.onConfirmDeleteAccount() },
            onDismissDeleteAccount: { viewModel?.onDeleteAccountDismissed() },
            onOpenUrl: { if let url = URL(string: $0) { openURL(url) } },
            onBackUpNow: onBackUpNow,
            onOpenAppSettings: {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            }
        )
        .overlay(alignment: .bottom) {
            if let toast {
                Text(toast)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundOnAccent)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(LoopkyColor.foregroundPrimary.opacity(0.9)))
                    .padding(.bottom, 40)
            }
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var viewState: SettingsViewState {
        guard let state = uiState else { return SettingsViewState() }
        return SettingsViewState(
            isLoading: state.isLoading,
            pubky: state.pubky,
            displayName: state.displayName,
            homeserver: state.homeserver,
            appVersion: appVersion,
            shareOnPubky: state.shareOnPubky,
            theme: state.themeMode,
            newCardsGoal: Int(state.studySettings.newCardsPerDayGoal),
            hardDays: Int(state.studySettings.hardDays),
            goodDays: Int(state.studySettings.goodDays),
            easyDays: Int(state.studySettings.easyDays),
            canEditStudySettings: state.canEditStudySettings,
            unsplashKeyStatus: Self.unsplashStatus(state.unsplashKeyStatus),
            isVerifyingUnsplashKey: state.isVerifyingUnsplashKey,
            unsplashKeyError: Self.unsplashError(state.unsplashKeyError),
            holdsOwnKey: state.holdsOwnKey,
            isDeleting: state.deletion != nil
        )
    }

    private static func unsplashStatus(_ status: UnsplashKeyStatus) -> UnsplashStatus {
        switch status {
        case is UnsplashKeyStatusUserSet: return .userSet
        case is UnsplashKeyStatusUsingBuiltIn: return .builtIn
        default: return .notSet
        }
    }

    private static func unsplashError(_ error: UnsplashError?) -> String? {
        switch error {
        case is UnsplashErrorInvalidKey:
            return NSLocalizedString("settings_unsplash_key_error_invalid", comment: "")
        case is UnsplashErrorMissingKey:
            return NSLocalizedString("settings_unsplash_key_error_missing", comment: "")
        case is UnsplashErrorRateLimited:
            return NSLocalizedString("settings_unsplash_key_error_rate_limited", comment: "")
        case is UnsplashErrorUnavailable:
            return NSLocalizedString("settings_unsplash_key_error_unavailable", comment: "")
        default:
            return nil
        }
    }

    /// The effect carries a case rather than a sentence — the wording belongs to this layer.
    private static func errorText(_ message: SettingsErrorMessage) -> String {
        message == SettingsErrorMessage.accountnotdeleted
            ? NSLocalizedString("settings_delete_account_failed", comment: "")
            : NSLocalizedString("settings_study_save_failed", comment: "")
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.settingsViewModel(appVersion: appVersion)
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? SettingsUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is SettingsEffectSignedOut:
                onSignedOut()
            case let copy as SettingsEffectCopyToClipboard:
                UIPasteboard.general.string = copy.text
                flash(NSLocalizedString("profile_copied", comment: ""))
            case let error as SettingsEffectShowError:
                flash(Self.errorText(error.message))
            default:
                break
            }
        }
    }

    private func flash(_ message: String) {
        withAnimation { toast = message }
        Task {
            try? await Task.sleep(for: .seconds(2))
            withAnimation { toast = nil }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}

enum UnsplashStatus { case notSet, userSet, builtIn }

struct SettingsViewState {
    var isLoading: Bool = true
    var pubky: String = ""
    var displayName: String?
    var homeserver: String = ""
    var appVersion: String = ""
    var shareOnPubky: Bool = true
    var theme: AppTheme = AppTheme.system
    var newCardsGoal: Int = 0
    var hardDays: Int = 1
    var goodDays: Int = 3
    var easyDays: Int = 7
    var canEditStudySettings: Bool = false
    var unsplashKeyStatus: UnsplashStatus = .notSet
    var isVerifyingUnsplashKey: Bool = false
    var unsplashKeyError: String?
    /// Whether this device holds the key **for the account on screen** — not merely whether a key
    /// is in the vault. An abandoned local signup leaves one behind for a pubky nobody signed in
    /// as, and offering that user a backup door into someone else's identity is the bug this
    /// comparison exists to stop.
    var holdsOwnKey: Bool = false
    var isDeleting: Bool = false
}
