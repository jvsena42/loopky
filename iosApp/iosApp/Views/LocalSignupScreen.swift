import SwiftUI
import Shared

/// The terminal step of signup: the token the user earned is spent, here, on a key minted locally.
///
/// It starts itself — there is no button — so the screen is a progress report with two escape
/// hatches: retry, and (only for a refused token) start over.
struct LocalSignupScreen: View {
    var onBack: () -> Void
    var adoptHeldKey: Bool
    var onCreated: () -> Void
    var onStartOver: () -> Void

    @State private var viewModel: LocalSignupViewModel?
    @State private var uiState: LocalSignupUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    var body: some View {
        SignupScaffold(
            title: "signup_local_title",
            subtitle: NSLocalizedString("signup_local_subtitle", comment: ""),
            errorTitle: SignupErrorCopy.title(for: uiState?.error),
            errorMessage: SignupErrorCopy.message(for: uiState?.error),
            onBack: onBack
        ) {
            VStack(alignment: .leading, spacing: 16) {
                if uiState?.isWorking ?? true {
                    HStack(spacing: 10) {
                        ProgressView().controlSize(.regular).tint(LoopkyColor.accentPrimary)
                        Text("signup_local_subtitle")
                            .font(.system(size: 13))
                            .foregroundStyle(LoopkyColor.foregroundMuted)
                    }
                }
                if uiState?.canRetry ?? false {
                    SignupPrimaryButton(title: "signup_local_retry") { viewModel?.onRetryClick() }
                        .accessibilityIdentifier("signup_local_retry")
                }
                // Offered only for a token the homeserver definitively refused: retrying re-sends
                // the same dead value, so the way out is a new one.
                if uiState?.canStartOver ?? false {
                    SignupPrimaryButton(title: "signup_start_over") { viewModel?.onStartOverClick() }
                        .accessibilityIdentifier("signup_start_over")
                }
            }
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.localSignupViewModel(registerHeldKey: adoptHeldKey)
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? LocalSignupUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is LocalSignupEffectNavigateHome: onCreated()
            case is LocalSignupEffectNavigateStartOver: onStartOver()
            default: break
            }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
