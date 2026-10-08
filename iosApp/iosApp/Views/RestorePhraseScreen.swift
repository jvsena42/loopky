import SwiftUI
import Shared

/// Sign in by typing a recovery phrase.
struct RestorePhraseScreen: View {
    var onBack: () -> Void
    var onRestored: () -> Void
    var onUnregistered: (String) -> Void
    /// Entered from the "Password manager" card: the saved-password sheet is raised on arrival.
    var startFromPasswordManager = false

    @State private var passwordManager = PasswordManagerSheet()
    /// Survives the screen being covered, so coming back from the unregistered-key screen does
    /// not raise the sheet a second time.
    @State private var didRaisePicker = false
    @State private var viewModel: RestorePhraseViewModel?
    @State private var uiState: RestorePhraseUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var phrase = ""

    var body: some View {
        SignupScaffold(
            title: "restore_phrase_title",
            subtitle: NSLocalizedString("restore_phrase_subtitle", comment: ""),
            onBack: onBack
        ) {
            VStack(alignment: .leading, spacing: 0) {
                // The don't-type-your-wallet-phrase direction. Permanent, never a toast.
                SeedPhraseWarning(text: "restore_seed_warning")
                Spacer().frame(height: 20)

                FieldLabel(text: "restore_phrase_label")
                Spacer().frame(height: 8)

                // Disabled while checking, and that is load-bearing: an editable field during the
                // round trip lets the checked words and the on-screen words diverge, and every
                // outcome below is about the submitted ones.
                TextField("restore_phrase_placeholder", text: $phrase, axis: .vertical)
                    .lineLimit(3...6)
                    .font(.system(size: 15))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    // Deliberately no `.textContentType` — see `PassphraseField`. `.password`
                    // would offer the recovery phrase to iCloud Keychain (#148).
                    .disabled(isChecking || isReadingPasswordManager)
                    .onChange(of: phrase) { _, value in viewModel?.onPhraseChange(phrase: value) }
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.surfaceCard))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(isInvalid ? LoopkyColor.danger : LoopkyColor.borderSubtle, lineWidth: 1)
                    )
                    .accessibilityIdentifier("restore_phrase_input")

                Spacer().frame(height: 24)
                SignupPrimaryButton(
                    title: isChecking ? "restore_phrase_checking" : "restore_phrase_submit",
                    isLoading: isChecking,
                    isEnabled: uiState?.canSubmit ?? false,
                    action: submit
                )
                .accessibilityIdentifier("restore_phrase_submit")

                if uiState?.canUsePasswordManager ?? false {
                    Spacer().frame(height: 14)
                    Button("restore_phrase_from_manager") {
                        guard let viewModel, viewModel.beginPasswordManagerRead() else { return }
                        readFromPasswordManager(viewModel)
                    }
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(LoopkyColor.accentSecondary)
                    .frame(maxWidth: .infinity)
                    .disabled(isChecking || isReadingPasswordManager)
                    .accessibilityIdentifier("restore_phrase_from_manager")
                }
                if uiState?.passwordManagerEmpty ?? false {
                    Spacer().frame(height: 8)
                    Text("restore_phrase_manager_empty")
                        .font(.system(size: 12))
                        .foregroundStyle(LoopkyColor.foregroundSecondary)
                        .accessibilityIdentifier("restore_phrase_manager_empty")
                }

                if let outcome = uiState?.outcome {
                    Spacer().frame(height: 20)
                    RestoreOutcomeBlock(outcome: outcome)
                }
            }
        }
        // The phrase is on screen, so cover it while recording. Not FLAG_SECURE — see SecureContent.
        .modifier(SecureScreenModifier())
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    /// Hand the ViewModel the words that are **on screen**, then check those.
    ///
    /// The field owns its own `@State` (see `PassphraseField`), and the ViewModel only learns of
    /// edits through `.onChange`. When the text arrives in one shot rather than keystroke by
    /// keystroke — a paste of twelve words, which is how most people do this — the field can hold
    /// the phrase while the ViewModel still holds what it had before, and the phrase that gets
    /// checked is not the phrase the user is looking at. That surfaces as "That's not a valid
    /// recovery phrase" over a perfectly good one, which is the single worst thing this screen can
    /// say: it is the message that sends someone hunting for a mistyped word they never made.
    ///
    /// Re-sending the visible text costs nothing when the two already agree, and the screen's own
    /// contract — every outcome below is about the submitted words — only holds with it.
    private func submit() {
        viewModel?.onPhraseChange(phrase: phrase)
        viewModel?.onSubmit()
    }

    private var isChecking: Bool { uiState?.isChecking ?? false }

    private var isReadingPasswordManager: Bool { uiState?.isReadingPasswordManager ?? false }

    /// The read is claimed by the caller (`beginPasswordManagerRead`) and answered here, without
    /// the effect flow — the same reason as on Android, where an effect emitted as the screen
    /// opens is dropped before anything collects it.
    ///
    /// The field owns its text, so the phrase is put there first: the ViewModel submits what it
    /// was handed, and the screen must show the same words.
    private func readFromPasswordManager(_ viewModel: RestorePhraseViewModel) {
        Task {
            let secret = await passwordManager.read()?.trimmingCharacters(in: .whitespacesAndNewlines)
            if let secret, !secret.isEmpty { phrase = secret }
            viewModel.onPasswordManagerResult(secret: secret)
        }
    }

    private var isInvalid: Bool { uiState?.outcome is RestoreOutcomeInvalidPhrase }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.restorePhraseViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? RestorePhraseUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is RestoreEffectNavigateHome:
                onRestored()
            case let unregistered as RestoreEffectNavigateUnregistered:
                onUnregistered(unregistered.pubky)
            default:
                break
            }
        }
        if startFromPasswordManager, !didRaisePicker {
            didRaisePicker = true
            if vm.beginPasswordManagerRead() { readFromPasswordManager(vm) }
        }
    }

    private func detach() {
        // `onLeaveUnlessCorrecting`, not `onLeave`: someone whose phrase resolved to an account
        // that does not exist is being sent to the unregistered-key screen and may come straight
        // back to correct a word. Wiping the field under them would make them retype twelve.
        viewModel?.onLeaveUnlessCorrecting()
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}

/// Applies `SecureContent` as a modifier, so a screen can opt in with one line.
struct SecureScreenModifier: ViewModifier {
    func body(content: Content) -> some View {
        SecureContent { content }
    }
}
