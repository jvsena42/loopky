import SwiftUI
import Shared

/// VM-driven wrapper around the presentational `OnboardingView`. Owns the shared
/// `OnboardingViewModel`, observes its `StateFlow`, and reacts to one-shot effects
/// (open the Pubky Ring deeplink, open the install page, navigate home).
///
/// This is the wiring exemplar for all iOS screens: `*Screen` owns the VM via
/// `IosDependencies`, `*View` stays a pure layout.
struct OnboardingScreen: View {
    @Environment(\.openURL) private var openURL

    /// Resolved once, in `attach()`, and held in `@State`.
    ///
    /// Koin binds the ViewModels as factories, so `onboardingViewModel()` returns a *new* instance
    /// every call. Resolving it in `init` therefore minted a fresh VM on each SwiftUI re-render
    /// while the flow subscriptions stayed bound to the first one: the button drove one instance
    /// and the screen observed another, so sign-in ran to the relay and the UI never moved.
    @State private var viewModel: OnboardingViewModel?

    /// Held erased, and matched against the *concrete* state classes below.
    ///
    /// `OnboardingUiState` is a sealed interface, so it crosses the bridge as an Objective-C
    /// **protocol**. Casting an erased value to a generic parameter bound to a protocol — which is
    /// what `FlowObserver<OnboardingUiState>` did — yields `nil` every time, with no crash and no
    /// diagnostic: the screen simply never left its initial state and the sign-in appeared to do
    /// nothing. The concrete `OnboardingUiState*` types are classes and cast correctly.
    @State private var uiState: Any?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    var onSignedIn: () -> Void
    /// The three doors Android's onboarding offers beside Ring sign-in. iOS had none of them.
    var onCreatePubky: () -> Void = {}
    var onRestore: () -> Void = {}
    /// A Ring sign-in whose key the homeserver has no account for.
    var onUnregistered: (String) -> Void = { _ in }
    /// This launch found no session: hand off to browsing rather than holding the visitor on a
    /// sign-in wall. Only fires when [autoExplore] is set, which is the launch that was routed
    /// here automatically rather than by an explicit "sign me out".
    var onExplore: () -> Void = {}
    var autoExplore: Bool = false

    @Environment(\.loopkyWidthClass) private var widthClass

    var body: some View {
        Group {
            if holdSplash {
                SplashView()
            } else {
                OnboardingView(
                    isWorking: isWorking,
                    errorMessage: errorMessage,
                    stillWaiting: awaiting?.stillWaiting ?? false,
                    onSignInTapped: { viewModel?.onSignInClick(handoff: handoff) },
                    onRestoreTapped: onRestore,
                    onCreatePubkyTapped: onCreatePubky,
                    onCancelTapped: { viewModel?.onCancelSignIn() },
                    // Inline only where there is a column to put it in; narrower windows get the
                    // same panel as a sheet, below.
                    scan: widthClass.isExpanded ? scanPrompt : nil
                )
            }
        }
        .onAppear { attach() }
        .onDisappear { detach() }
        // Driven off the state, not an effect, for the same reason Android does it this way:
        // `effects` has zero replay, so anything emitted from the ViewModel's init can be dropped
        // if the collector has not attached yet — and "no persisted session" is decided in exactly
        // that window. Idle is the ViewModel's word for it, and a StateFlow cannot lose it.
        .onChange(of: hasNoSession) { _, noSession in
            if autoExplore && noSession { onExplore() }
        }
        .sheet(isPresented: scanSheetBinding) {
            if let scanPrompt {
                RingScanSheet(
                    authUrl: scanPrompt.authUrl,
                    ringInstalledHere: scanPrompt.ringInstalledHere,
                    stillWaiting: scanPrompt.stillWaiting,
                    onOpenRingHere: scanPrompt.onOpenRingHere,
                    onGetRing: scanPrompt.onGetRing,
                    onCancel: scanPrompt.onCancel
                )
            }
        }
    }

    private var awaiting: OnboardingUiStateAwaitingApproval? {
        uiState as? OnboardingUiStateAwaitingApproval
    }

    /// Where the user is expected to approve this sign-in.
    ///
    /// A phone's key is in Ring on that same phone, so the deeplink is the shortest path. An iPad's
    /// owner keeps their key on their phone, where the deeplink cannot reach, so the way in is a
    /// code that phone can scan — and Ring being installed *here* does not change it, because an
    /// iPad that happens to have Ring may still not have this user's key.
    ///
    /// Computed from the window on every layout, never captured at launch: an iPad in Slide Over is
    /// a phone-shaped column, and rotation and a Split View divider both move the answer while the
    /// app is running.
    private var handoff: RingHandoff {
        widthClass.isAtLeastMedium ? RingHandoff.anotherdevice : RingHandoff.thisdevice
    }

    /// The pending authorisation, when it is waiting on a device this one cannot deeplink to.
    ///
    /// The shared VM fires `OpenDeeplink` only when the handoff is `ThisDevice` *and* Ring is
    /// actually installed here, so anything else leaves the authorisation live with nothing driving
    /// it — the code is then the user's only way to approve it. Both halves of that condition
    /// matter: reading `ringInstalledHere` alone would leave an iPad **with** Ring installed
    /// waiting forever on a deeplink the VM deliberately never fired.
    private var scanPrompt: RingScanPrompt? {
        guard let awaiting else { return nil }
        let deeplinkFired = awaiting.handoff == RingHandoff.thisdevice && awaiting.ringInstalledHere
        guard !deeplinkFired else { return nil }
        return RingScanPrompt(
            authUrl: awaiting.authUrl,
            ringInstalledHere: awaiting.ringInstalledHere,
            stillWaiting: awaiting.stillWaiting,
            onOpenRingHere: { viewModel?.onOpenRingOnThisDevice() },
            onGetRing: { viewModel?.onGetRingClick() },
            onCancel: { viewModel?.onCancelSignIn() }
        )
    }

    /// The sheet is the narrow-window presentation only — at expanded width the same panel is
    /// rendered inline in the sign-in column, and raising both would stack a modal over it.
    private var scanSheetBinding: Binding<Bool> {
        Binding(
            get: { !widthClass.isExpanded && scanPrompt != nil },
            // Dismissing by drag is the same intent as Cancel: back out without an error.
            set: { if !$0 { viewModel?.onCancelSignIn() } }
        )
    }

    /// Every state in which this screen is on its way somewhere else, and so must show the
    /// branded splash rather than a sign-in wall the user is never given the chance to act on.
    ///
    /// Three of the four are not `Restoring`. `uiState` is `nil` until the first `StateFlow` value
    /// crosses the bridge, which is a frame or more *before* the cold start's `Restoring` arrives;
    /// `Success` is always followed by `NavigateHome`, so the CTA would otherwise be drawn for the
    /// whole navigation; and a launch with no session is handed to browsing by `onExplore`, which
    /// Android holds the splash for in the same way.
    private var holdSplash: Bool {
        guard let uiState else { return true }
        if uiState is OnboardingUiStateRestoring { return true }
        if uiState is OnboardingUiStateSuccess { return true }
        return autoExplore && hasNoSession
    }

    /// The ViewModel has finished looking and found nothing to restore.
    private var hasNoSession: Bool { uiState is OnboardingUiStateIdle }

    private var isWorking: Bool {
        switch uiState {
        case is OnboardingUiStateStarting, is OnboardingUiStateAwaitingApproval,
             is OnboardingUiStateVerifying:
            return true
        default:
            return false
        }
    }

    private var errorMessage: String? {
        guard let error = uiState as? OnboardingUiStateError else { return nil }
        return ErrorCopy.message(for: error.reason)
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.onboardingViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 }
        let signedIn = onSignedIn
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case let open as OnboardingEffectOpenDeeplink:
                guard let url = URL(string: open.url) else {
                    vm.onDeeplinkUnavailable()
                    return
                }
                openURL(url) { accepted in
                    if !accepted { vm.onDeeplinkUnavailable() }
                }
            case let install as OnboardingEffectOpenInstallPage:
                if let url = URL(string: install.url) { openURL(url) }
            case is OnboardingEffectNavigateHome:
                signedIn()
            case let unregistered as OnboardingEffectNavigateUnregistered:
                onUnregistered(unregistered.pubky)
            default:
                break
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
