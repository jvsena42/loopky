import SwiftUI
import Shared

/// Pronunciation practice, as a sheet over the card.
///
/// Four phases, one sheet: listening, right, wrong, and a listen that produced no attempt at all.
/// `Idle` presents nothing — the shared ViewModel's phase *is* the presentation state, so there is
/// no second copy of it here.
struct SpeakSheet: View {
    /// Held erased and matched against the concrete classes: `SpeakPhase` is a sealed interface,
    /// so it crosses as an Objective-C protocol and a generic cast to it silently yields nil.
    let phase: Any?
    var onRetry: () -> Void = {}
    var onContinue: () -> Void = {}
    var onDismiss: () -> Void = {}

    var body: some View {
        VStack(spacing: 16) {
            switch phase {
            case let listening as SpeakPhaseListening:
                listeningBody(listening.expected)
            case let correct as SpeakPhaseCorrect:
                correctBody(correct.heard)
            case let wrong as SpeakPhaseWrong:
                wrongBody(heard: wrong.heard, expected: wrong.expected)
            case let failed as SpeakPhaseFailed:
                failedBody(failed)
            default:
                EmptyView()
            }
        }
        .padding(24)
        .contentPane(PaneWidth.focused)
        .background(LoopkyColor.surfacePrimary)
        .presentationDetents([.height(340)])
        .presentationDragIndicator(.visible)
    }

    private func listeningBody(_ target: String) -> some View {
        VStack(spacing: 14) {
            Text("speak_listening_prompt")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(LoopkyColor.foregroundMuted)
            // What is shown is the card as written, asides included: the note is the context worth
            // seeing, even though the matcher drops it before comparing.
            Text(verbatim: target)
                .font(.system(size: 26, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
            Image(systemName: "waveform")
                .font(.system(size: 40))
                .foregroundStyle(LoopkyColor.accentPrimary)
                .symbolEffect(.variableColor.iterative, options: .repeating)
                .padding(.vertical, 8)
            Button("speak_listening_hint", action: onDismiss)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .accessibilityIdentifier("speak_cancel")
        }
    }

    private func correctBody(_ heard: String) -> some View {
        VStack(spacing: 12) {
            Text("🎉").font(.system(size: 44))
            Text("speak_correct_title")
                .font(.system(size: 22, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Text("speak_you_said_label")
                .font(.system(size: 10, weight: .bold))
                .kerning(0.6)
                .foregroundStyle(LoopkyColor.foregroundMuted)
            Text(verbatim: heard)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Button("speak_continue", action: onContinue)
                .buttonStyle(.loopkyFilled)
                .accessibilityIdentifier("speak_continue")
        }
    }

    /// A listen that never became an attempt, said out loud rather than closed away — a sheet that
    /// vanishes on "nothing matched" is indistinguishable from the app having dropped the tap.
    ///
    /// It grades nothing either: no answer was heard, so there is nothing to be right or wrong.
    private func failedBody(_ failed: SpeakPhaseFailed) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "mic.slash")
                .font(.system(size: 34))
                .foregroundStyle(LoopkyColor.srsAgain)
                .padding(.bottom, 2)
            Text(failed.reason.titleKey)
                .font(.system(size: 20, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
                .accessibilityIdentifier("speak_failed_title")
            Text(failed.reason.messageKey)
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
            if failed.retryable {
                Button("speak_try_again", action: onRetry)
                    .buttonStyle(.loopkyFilled)
                    .padding(.top, 4)
                    .accessibilityIdentifier("speak_retry")
            } else {
                Button("speak_close", action: onDismiss)
                    .buttonStyle(.loopkyFilled)
                    .padding(.top, 4)
                    .accessibilityIdentifier("speak_failed_close")
            }
        }
    }

    /// A miss says what was heard *and* what was wanted, and offers another go.
    ///
    /// It grades nothing: the four SRS buttons stay exactly as available as they were, because a
    /// pronunciation attempt is practice, not a verdict on whether the card was known.
    private func wrongBody(heard: String, expected: String) -> some View {
        VStack(spacing: 10) {
            Text("speak_wrong_title")
                .font(.system(size: 20, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            VStack(spacing: 2) {
                Text("speak_you_said")
                    .font(.system(size: 11))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                Text(verbatim: heard)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(LoopkyColor.srsHard)
            }
            VStack(spacing: 2) {
                Text("speak_correct_label")
                    .font(.system(size: 11))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                Text(verbatim: expected)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(LoopkyColor.srsGood)
            }
            Button("speak_try_again", action: onRetry)
                .buttonStyle(.loopkyFilled)
                .padding(.top, 4)
                .accessibilityIdentifier("speak_retry")
        }
    }
}

private extension SpeechError {
    /// Compared with `==` rather than pattern-matched: a Kotlin enum crosses as a class with one
    /// property per entry, so a `switch` over it has no cases to be exhaustive about.
    var titleKey: LocalizedStringKey {
        if self == SpeechError.permission { return "speak_failed_permission_title" }
        if self == SpeechError.network { return "speak_failed_network_title" }
        if self == SpeechError.busy { return "speak_failed_busy_title" }
        if self == SpeechError.unavailable { return "speak_failed_unavailable_title" }
        if self == SpeechError.languageunavailable { return "speak_failed_language_title" }
        if self == SpeechError.nomatch { return "speak_failed_no_match_title" }
        return "speak_failed_unknown_title"
    }

    var messageKey: LocalizedStringKey {
        if self == SpeechError.permission { return "speak_permission_denied" }
        if self == SpeechError.network { return "speak_failed_network_message" }
        if self == SpeechError.busy { return "speak_failed_busy_message" }
        if self == SpeechError.unavailable { return "speak_unavailable" }
        if self == SpeechError.languageunavailable { return "speak_failed_language_message" }
        if self == SpeechError.nomatch { return "speak_failed_no_match_message" }
        return "speak_failed_unknown_message"
    }
}
