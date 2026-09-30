import Shared
import SwiftUI

/// Settings, as a native grouped `List` rather than a hand-built stack of cards — so it inherits
/// the platform's section styling, row insets and Liquid Glass chrome on the iOS 26 SDK.
struct SettingsView: View {
    var state: SettingsViewState = SettingsViewState()
    var onCopyPubky: () -> Void = {}
    var onCopyHomeserver: () -> Void = {}
    var onShareOnPubkyChanged: (Bool) -> Void = { _ in }
    var onThemeChanged: (AppTheme) -> Void = { _ in }
    var onGoalChanged: (Int) -> Void = { _ in }
    var onIntervalChanged: (StudyGrade, Int) -> Void = { _, _ in }
    var onSaveUnsplashKey: (String) -> Void = { _ in }
    var onRemoveUnsplashKey: () -> Void = {}
    var onDismissUnsplashError: () -> Void = {}
    var onDeleteAccount: () -> Void = {}
    var onConfirmDeleteAccount: () -> Void = {}
    var onDismissDeleteAccount: () -> Void = {}
    var onOpenUrl: (String) -> Void = { _ in }
    var onBackUpNow: () -> Void = {}
    var onOpenAppSettings: () -> Void = {}

    @State private var unsplashKey = ""

    private static let privacyPolicyUrl = "https://loopky.app/privacy"

    var body: some View {
        List {
            identitySection
            appearanceSection
            languageSection
            studyingSection
            sharingSection
            imageSearchSection
            aboutSection
            dangerSection
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        // A column of settings rows — the case `PaneWidth.reading` exists for. Unbounded, a
        // 1366pt-wide row puts its label at one edge and its toggle at the other.
        .contentPane()
        .loopkyScreenBackground()
        .navigationTitle(Text("settings_title"))
        .navigationBarTitleDisplayMode(.inline)
        .alert(
            Text("settings_delete_account_dialog_title"),
            isPresented: Binding(get: { state.isDeleting }, set: { if !$0 { onDismissDeleteAccount() } })
        ) {
            Button("settings_delete_account_confirm", role: .destructive, action: onConfirmDeleteAccount)
            Button("settings_cancel", role: .cancel, action: onDismissDeleteAccount)
        } message: {
            Text("settings_delete_account_dialog_irreversible")
        }
    }

    private var identitySection: some View {
        Section {
            Button(action: onCopyPubky) {
                LabeledContent {
                    Text(state.pubky).font(.system(size: 13, design: .monospaced)).lineLimit(1).truncationMode(.middle)
                } label: {
                    Text("settings_pubky_label")
                }
            }
            .tint(LoopkyColor.foregroundPrimary)
            Button(action: onCopyHomeserver) {
                LabeledContent {
                    Text(state.homeserver.isEmpty
                         ? NSLocalizedString("settings_homeserver_unknown", comment: "")
                         : state.homeserver)
                    .font(.system(size: 13)).lineLimit(1).truncationMode(.middle)
                } label: {
                    Text("settings_homeserver_label")
                }
            }
            .tint(LoopkyColor.foregroundPrimary)

            // A permanent way back into backup, for as long as Loopky holds this account's key.
            //
            // The Profile card is not enough on its own: it disappears the moment one method is
            // done, which left the flow unreachable afterwards — no second method, no encrypted
            // file to add later, and no route at all for a restored account, which counts as
            // backed up the moment it signs in. Methods accumulate, so the door stays open.
            if state.holdsOwnKey {
                Button("settings_back_up_account", action: onBackUpNow)
                    .tint(LoopkyColor.accentSecondary)
                    .accessibilityIdentifier("settings_back_up_account")
            }
        } header: {
            Text("settings_section_identity")
        }
    }

    /// The palette, which Loopky owns rather than iOS — unlike the language row below it.
    ///
    /// A segmented `Picker` rather than a row that pushes: three mutually exclusive options that
    /// repaint the screen behind the control, so the result of the tap is visible without leaving.
    private var appearanceSection: some View {
        Section {
            Picker(
                selection: Binding(get: { state.theme }, set: onThemeChanged),
                label: Text("settings_theme_label")
            ) {
                Text("settings_theme_system").tag(AppTheme.system)
                Text("settings_theme_auto").tag(AppTheme.scheduled)
                Text("settings_theme_light").tag(AppTheme.light)
                Text("settings_theme_dark").tag(AppTheme.dark)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("settings_theme")
        } header: {
            Text("settings_section_appearance")
        } footer: {
            // The hours are named rather than left to be discovered: without them "Auto" is a
            // control whose behaviour you can only learn by waiting until evening.
            Text(String(
                format: NSLocalizedString("settings_theme_description", comment: ""),
                Self.hourLabel(Int(DayNightSchedule.shared.darkFromHour)),
                Self.hourLabel(Int(DayNightSchedule.shared.darkUntilHour))
            ))
        }
    }

    /// The app language, which iOS owns rather than Loopky.
    ///
    /// The picker itself is the system's own per-app Language screen, so this row shows the
    /// current choice and hands the user to Settings. Writing `AppleLanguages` from here would
    /// reach the same preference, but iOS resolves it at launch — the app would keep rendering
    /// the old language until it was relaunched, which reads as the setting not working.
    private var languageSection: some View {
        Section {
            Button(action: onOpenAppSettings) {
                LabeledContent {
                    Text(Self.currentLanguageName)
                } label: {
                    Text("settings_language_label")
                }
            }
            .tint(LoopkyColor.foregroundPrimary)
            .accessibilityIdentifier("settings_language")
        } header: {
            Text("settings_section_language")
        } footer: {
            Text("settings_language_description")
        }
    }

    /// An o'clock hour as the reader's own device writes it — "8 PM" or "20:00".
    ///
    /// `.short` follows the device's 24-hour switch, so this matches a setting the user has already
    /// made elsewhere. Interpolating the raw number gave "20:00" to someone whose phone has never
    /// shown them a 24-hour clock.
    private static func hourLabel(_ hour: Int) -> String {
        let date = Calendar.current.date(from: DateComponents(hour: hour, minute: 0)) ?? Date()
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    /// The language's own name for itself — "English", "Português (Brasil)".
    private static var currentLanguageName: String {
        let tag = Bundle.main.preferredLocalizations.first ?? "en"
        let locale = Locale(identifier: tag)
        let name = locale.localizedString(forIdentifier: tag) ?? tag
        return name.prefix(1).uppercased() + name.dropFirst()
    }

    /// The daily goal is **announced, never enforced** — the queue serves every due card and every
    /// new one regardless. The description says so; wording it as a limit would describe a feature
    /// Loopky does not have.
    private var studyingSection: some View {
        Section {
            Stepper(value: Binding(get: { state.newCardsGoal }, set: onGoalChanged), in: 1...100) {
                settingRow(
                    "settings_new_cards_goal_label",
                    caption: "settings_new_cards_goal_description",
                    value: "\(state.newCardsGoal)"
                )
            }
            intervalRow(
                "settings_interval_hard_label",
                caption: "settings_interval_description",
                grade: .hard,
                days: state.hardDays
            )
            intervalRow("settings_interval_good_label", grade: .good, days: state.goodDays)
            intervalRow(
                "settings_interval_easy_label",
                caption: "settings_interval_mastery_note",
                grade: .easy,
                days: state.easyDays
            )
        } header: {
            Text("settings_section_studying")
        } footer: {
            if !state.canEditStudySettings {
                // The repository refuses a write before the record has been read, so that a
                // save cannot put defaults over what the user really had.
                Text("settings_study_unavailable").foregroundStyle(LoopkyColor.danger)
            }
        }
        .disabled(!state.canEditStudySettings)
    }

    private func intervalRow(
        _ label: LocalizedStringKey,
        caption: LocalizedStringKey? = nil,
        grade: StudyGrade,
        days: Int
    ) -> some View {
        Stepper(
            value: Binding(get: { days }, set: { onIntervalChanged(grade, $0) }),
            in: 1...365
        ) {
            settingRow(label, caption: caption, value: "\(days)d")
        }
    }

    /// A stepper row whose caption sits under the label it explains, rather than in the section
    /// footer: three captions stacked below four rows leave the reader to work out which line
    /// belongs to which setting, and the mastery note in particular only makes sense beside the
    /// Easy interval it is measured against. Mirrors Android's `StudySettingsSection`.
    private func settingRow(
        _ label: LocalizedStringKey,
        caption: LocalizedStringKey?,
        value: String
    ) -> some View {
        LabeledContent {
            Text(value)
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                if let caption {
                    Text(caption)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private var sharingSection: some View {
        Section {
            Toggle(isOn: Binding(get: { state.shareOnPubky }, set: onShareOnPubkyChanged)) {
                Text("settings_share_on_pubky_label")
            }
            .tint(LoopkyColor.accentPrimary)
        } header: {
            Text("settings_section_sharing")
        } footer: {
            Text("settings_share_on_pubky_description")
        }
    }

    private var imageSearchSection: some View {
        Section {
            LabeledContent("settings_unsplash_key_label", value: unsplashStatusText)
            if state.unsplashKeyStatus == .userSet {
                Button("settings_unsplash_key_remove", role: .destructive, action: onRemoveUnsplashKey)
            } else {
                SecureField("settings_unsplash_key_placeholder", text: $unsplashKey)
                Button("settings_unsplash_key_save") {
                    onSaveUnsplashKey(unsplashKey)
                    unsplashKey = ""
                }
                .disabled(unsplashKey.isEmpty || state.isVerifyingUnsplashKey)
            }
            Button("settings_unsplash_key_get") { onOpenUrl("https://unsplash.com/developers") }
        } header: {
            Text("settings_section_image_search")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                Text("settings_unsplash_key_hint")
                if let error = state.unsplashKeyError {
                    Text(error).foregroundStyle(LoopkyColor.danger)
                }
            }
        }
    }

    private var unsplashStatusText: String {
        switch state.unsplashKeyStatus {
        case .userSet: return NSLocalizedString("settings_unsplash_key_label", comment: "")
        case .builtIn: return NSLocalizedString("settings_unsplash_key_built_in", comment: "")
        case .notSet: return NSLocalizedString("settings_unsplash_key_not_set", comment: "")
        }
    }

    private var aboutSection: some View {
        Section {
            LabeledContent("settings_app_version_label", value: state.appVersion)
            Button("settings_privacy_policy") { onOpenUrl(Self.privacyPolicyUrl) }
        } header: {
            Text("settings_section_about")
        }
    }

    /// Deleting an account, and nothing else.
    ///
    /// Sign-out and the backup door both live on Profile now: they belong beside the key they act
    /// on, and here they were four screens down in the one section without a header — which is
    /// how sign-out came to be reported missing.
    private var dangerSection: some View {
        Section {
            Button("settings_delete_account", role: .destructive, action: onDeleteAccount)
        } header: {
            Text("settings_section_account")
        }
    }
}
