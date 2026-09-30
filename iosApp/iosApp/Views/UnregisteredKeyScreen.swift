import SwiftUI
import Shared

/// A valid key that no homeserver has an account for.
///
/// The screen exists to avoid the two wrong answers: calling a good key invalid, and offering
/// "create an account", which would mint a *different* pubky and leave this one account-less
/// forever. So the primary action is "check the phrase again" — by likelihood, not by ease.
struct UnregisteredKeyScreen: View {
    let pubky: String
    let loopkyHoldsKey: Bool
    var onBack: () -> Void
    var onNeedsVerification: () -> Void
    var onRegistered: () -> Void
    var onRestoreWithPhrase: () -> Void

    @State private var viewModel: UnregisteredKeyViewModel?
    @State private var uiState: UnregisteredKeyUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var isConfirming = false

    var body: some View {
        SignupScaffold(
            title: "unregistered_title",
            subtitle: NSLocalizedString("unregistered_subtitle", comment: ""),
            errorTitle: SignupErrorCopy.title(for: uiState?.error),
            errorMessage: SignupErrorCopy.message(for: uiState?.error),
            onBack: onBack
        ) {
            VStack(alignment: .leading, spacing: 0) {
                pubkyCard
                Spacer().frame(height: 24)

                // Primary by likelihood: a mistyped word is far more common than a key that
                // genuinely never had an account.
                SignupPrimaryButton(title: "unregistered_check_phrase") {
                    viewModel?.onCheckPhraseAgainClick()
                }
                .accessibilityIdentifier("unregistered_check_phrase")

                Spacer().frame(height: 8)
                Text("unregistered_check_hint")
                    .font(.system(size: 12))
                    .foregroundStyle(LoopkyColor.foregroundSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                Spacer().frame(height: 24)
                if uiState?.loopkyHoldsKey ?? loopkyHoldsKey { registerBlock } else { ringHeldBlock }
            }
        }
        .confirmationDialog(
            Text("unregistered_register_confirm_title"),
            isPresented: $isConfirming,
            titleVisibility: .visible
        ) {
            Button("unregistered_register_confirm_yes") { viewModel?.onRegisterConfirmed() }
            Button("unregistered_register_confirm_cancel", role: .cancel) {}
        } message: {
            Text(verbatim: String(
                format: NSLocalizedString("unregistered_register_confirm_body", comment: ""),
                String(pubky.prefix(pubkyPreviewLength))
            ))
        }
        .onAppear {
            attach()
            // Re-arms the "this key has a future" latch, so coming back from signup does not
            // discard the key on the way out.
            viewModel?.onReturned()
        }
        .onDisappear { detach() }
    }

    private var pubkyCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("unregistered_pubky_label")
                .font(.system(size: 11))
                .foregroundStyle(LoopkyColor.foregroundMuted)
            Text(verbatim: uiState?.pubky ?? pubky)
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 12).fill(LoopkyColor.surfaceCard))
    }

    private var registerBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button("unregistered_register") { isConfirming = true }
                .buttonStyle(.loopkyOutline)
                .disabled(uiState?.isRegistering ?? false)
                .accessibilityIdentifier("unregistered_register")
            Text("unregistered_register_hint")
                .font(.system(size: 12))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// Ring holds the key, so Loopky cannot register it — and deliberately offers no "create an
    /// account" button, which would mint a different pubky than the one on screen.
    private var ringHeldBlock: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("unregistered_ring_title")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Text("unregistered_ring_body")
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer().frame(height: 6)
            Button("unregistered_ring_import", action: onRestoreWithPhrase)
                .buttonStyle(.loopkyOutline)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(LoopkyColor.surfaceCard))
    }

    private func attach() {
        guard viewModel == nil else { return }
        // Rebuilt on this side rather than crossing the route, so no key material travels with
        // navigation. Only the pubky and the boolean do, matching Android.
        //
        // `backedUpBy` is empty and `hasPhrase` true because this key has just been restored from
        // a phrase and has no backup recorded yet — the VM only reads `custody is Loopky` anyway.
        let custody: KeyCustody = loopkyHoldsKey
            ? KeyCustodyLoopky(pubky: pubky, backedUpBy: Set(), hasPhrase: true)
            : KeyCustodyExternal()
        let vm = IosDependencies.shared.unregisteredKeyViewModel(pubky: pubky, custody: custody)
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? UnregisteredKeyUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is UnregisteredKeyEffectNavigateBack: onBack()
            case is UnregisteredKeyEffectNavigateSignup: onNeedsVerification()
            case is UnregisteredKeyEffectNavigateHome: onRegistered()
            default: break
            }
        }
    }

    private func detach() {
        // `release()` runs `onCleared`, which is what drops the orphaned key when the user leaves
        // without registering. See `ViewModelRelease.swift` — that callback does not fire on its
        // own here the way it does on Android.
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}

/// How much of the pubky the confirm dialog quotes, matching Android's `PUBKY_PREVIEW_LEN`.
private let pubkyPreviewLength = 12
