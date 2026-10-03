import SwiftUI
import Shared

/// The flashcard. One of the few places Loopky builds something custom rather than reaching for a
/// native control — there is no system equivalent of a card that turns over.
struct StudyCardView: View {
    var state: StudyViewState
    @Binding var typed: String
    var answerFocused: FocusState<Bool>.Binding
    var onFlip: () -> Void = {}
    var onListen: () -> Void = {}
    var onSpeak: () -> Void = {}
    var onCheckAnswer: () -> Void = {}
    var onGiveUp: () -> Void = {}

    @Environment(\.loopkyWidthClass) private var widthClass

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 24)
                .fill(LoopkyColor.surfaceCard)
                .shadow(color: LoopkyColor.shadowElevationMedium, radius: 18, y: 8)
            face
                .padding(20)
                // Counter-rotate the content, or the back face renders mirrored.
                .rotation3DEffect(.degrees(state.revealed ? 180 : 0), axis: (x: 0, y: 1, z: 0))
        }
        .rotation3DEffect(.degrees(state.revealed ? 180 : 0), axis: (x: 0, y: 1, z: 0))
        .animation(.easeInOut(duration: 0.35), value: state.revealed)
        .frame(maxWidth: .infinity)
        // Capped rather than free to grow — a flashcard stretched to a full screen is a wall of
        // white around one word, and it pushes the grade row off the thumb's reach. Keyed on the
        // width class because a phone's ceiling on an iPad leaves a third of the screen as empty
        // cream above and below the card, which is the same mistake in the other direction.
        .frame(minHeight: 320, maxHeight: maxCardHeight)
        // The whole card is the flip target, and it stays live while answering: what a typing card
        // withholds is the answer, never the gesture.
        .contentShape(RoundedRectangle(cornerRadius: 24))
        .onTapGesture(perform: onFlip)
        // Without this the card is invisible to VoiceOver and to UI automation: a tap gesture on a
        // shape is not a control, so nothing announces it and nothing can drive it.
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel(Text(state.revealed ? state.backText : state.frontText))
        .accessibilityHint(Text("study_flip_hint"))
        .accessibilityIdentifier("study_card")
    }

    /// Unchanged from before iPads existed: on a phone this ceiling is never the binding one.
    /// A portrait iPad has the height to spend; a landscape one has ~620pt between the progress bar
    /// and the flip hint, so its ceiling only binds on a desktop-tall window.
    private var maxCardHeight: CGFloat {
        switch widthClass {
        case .compact: return 560
        case .medium: return 860
        case .expanded: return 720
        }
    }

    @ViewBuilder
    private var face: some View {
        VStack(spacing: 14) {
            if state.revealed { backFace } else { frontFace }
        }
    }

    private var frontFace: some View {
        VStack(spacing: 14) {
            picture(state.frontImageRef)
            Text(state.frontText)
                .font(.system(size: 26, weight: .bold))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
            // Listen practises the side that is showing, so it belongs on both faces — but see
            // `backFace`, where it comes off a masked answer.
            practiceRow
        }
    }

    private var backFace: some View {
        VStack(spacing: 12) {
            if let label = state.backLabel, !label.isEmpty {
                Text(label)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(LoopkyColor.accentPrimary)
            }

            if state.answerHidden {
                answerInput
            } else {
                // The back's picture is withheld with its text while a typing card is answering —
                // an image answer handed over early is the same giveaway as the words.
                picture(state.backImageRef)
                Text(state.backText)
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                    .multilineTextAlignment(.center)
                if state.typePhase == .correct {
                    Text("study_type_correct")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(LoopkyColor.srsGood)
                }
                // Only once the answer is actually on the card — reading a masked back aloud, or
                // asking it to be pronounced, would hand over the thing the card is withholding.
                practiceRow
            }
        }
    }

    /// The input sits *on the card back*, under the prompt label, in the space the answer will
    /// occupy — not in a row beneath the card.
    private var answerInput: some View {
        VStack(spacing: 10) {
            TextField("study_type_placeholder", text: $typed)
                .textFieldStyle(.plain)
                .font(.system(size: 22, weight: .bold))
                .multilineTextAlignment(.center)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .focused(answerFocused)
                .onSubmit(onCheckAnswer)
                .padding(.vertical, 10)
                .overlay(alignment: .bottom) {
                    Rectangle().fill(LoopkyColor.borderSubtle).frame(height: 1)
                }
                .accessibilityIdentifier("study_type_input")

            // Names what kind of miss it was, and does not echo what you typed — that is still in
            // the field, which is the point: you are correcting it, not being told the answer.
            if let miss = state.typeMissMessage {
                Text(miss)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(LoopkyColor.srsHard)
            }

            Button("study_type_check", action: onCheckAnswer)
                .buttonStyle(.loopkyFilled)
                .disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
                .accessibilityIdentifier("study_type_check")

            // Always right there under Check, and it reports nothing to the scheduler.
            Button("study_type_give_up", action: onGiveUp)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .accessibilityIdentifier("study_type_give_up")
        }
    }

    @ViewBuilder
    private func picture(_ ref: MediaRef.Image?) -> some View {
        if ref != nil {
            CardMediaImage(
                ref: ref,
                authorPubky: state.authorPubky,
                deckId: state.deckId
            )
            .frame(maxHeight: 240)
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
    }

    /// Listen and Speak, both practising the side that is showing.
    ///
    /// Neither appears unless the deck declared its language pair: given no language the engines
    /// fall back to the *reader's* locale, so an undeclared Spanish deck would be read in an
    /// English accent and graded by an English model — a wrong answer that looks like a feature.
    @ViewBuilder
    private var practiceRow: some View {
        if state.listenEnabled || state.speakEnabled {
            HStack(spacing: 8) {
                if state.listenEnabled { listenButton }
                if state.speakEnabled { speakButton }
            }
        }
    }

    private var speakButton: some View {
        Button(action: onSpeak) {
            HStack(spacing: 6) {
                Image(systemName: "mic.fill").font(.system(size: 12))
                Text("study_speak").font(.system(size: 13, weight: .semibold))
            }
            .foregroundStyle(LoopkyColor.accentSecondary)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Capsule().fill(LoopkyColor.accentSecondarySoft))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("study_speak")
    }

    private var listenButton: some View {
        Button(action: onListen) {
            HStack(spacing: 6) {
                Image(systemName: "speaker.wave.2.fill").font(.system(size: 12))
                Text("study_listen").font(.system(size: 13, weight: .semibold))
            }
            .foregroundStyle(LoopkyColor.accentPrimary)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Capsule().fill(LoopkyColor.accentPrimarySoft))
            .opacity(state.isListening ? 0.5 : 1)
        }
        // The card's tap-to-flip must not swallow the button's own tap.
        .buttonStyle(.plain)
        .disabled(state.isListening)
    }
}
