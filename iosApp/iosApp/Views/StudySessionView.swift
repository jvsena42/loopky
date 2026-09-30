import SwiftUI
import Shared

/// The SRS review loop. Pure layout; `StudySessionScreen` owns the ViewModel.
///
/// Four rules here cannot be enforced from `commonMain`, and all four are deliberate:
///
/// 1. **The flip is never blocked while answering.** Tapping turns the card as it always has; what
///    is withheld is the answer text, not the gesture.
/// 2. **Listen comes off a masked back face** — it would read out the answer the card is
///    withholding. `answerVisible`, not `revealed`, is the question to ask.
/// 3. **Nothing pre-selects a grade** after a Check or a Give up. All four stay equally available;
///    an escape hatch that pre-picks "Again" is a punishment wearing an escape hatch's label.
/// 4. **Only a correct Check opens the card.** A wrong or near-miss answer says so and leaves you
///    answering with what you typed still in the field — handing the answer over on the first slip
///    turns one typo into a lost card. Give up is the escape, and it sits right under Check.
struct StudySessionView: View {
    var state: StudyViewState = StudyViewState()
    @Binding var typed: String
    var onClose: () -> Void = {}
    var onReveal: () -> Void = {}
    var onGrade: (SrsGrade) -> Void = { _ in }
    var onCheckAnswer: () -> Void = {}
    var onGiveUp: () -> Void = {}
    var onListen: () -> Void = {}
    var onSpeak: () -> Void = {}
    var onNextCard: () -> Void = {}
    /// The end of a guest's preview offers an account. Nothing else on this screen does.
    var onSignIn: () -> Void = {}
    var onDismissSyncError: () -> Void = {}
    var onContinueAfterGoal: () -> Void = {}

    @FocusState private var answerFocused: Bool
    @Environment(\.loopkyWidthClass) private var widthClass

    var body: some View {
        ZStack(alignment: .top) {
            LoopkyColor.surfacePrimary.ignoresSafeArea()
            content
            if let syncErrorMessage = state.syncErrorMessage { syncBanner(syncErrorMessage) }
            // Over everything, including the sync banner: it is the one modal moment in a session.
            if state.goalReached {
                // Met on the last card, there is nothing to keep studying: the one way out
                // uncovers the summary underneath.
                GoalCelebrationView(
                    newCardsToday: state.newCardsToday,
                    sessionEnded: state.phase == .complete,
                    onKeepStudying: onContinueAfterGoal,
                    onDone: state.phase == .complete ? onContinueAfterGoal : onClose
                )
            }
        }
        .navigationBarHidden(true)
    }

    @ViewBuilder
    private var content: some View {
        switch state.phase {
        case .loading:
            centered(title: "study_loading", emoji: "🂠")
        case .empty:
            centered(title: "study_empty_title", subtitle: "study_empty_subtitle", emoji: "🎉", showsClose: true)
        case .failed:
            centered(title: "study_error_title", message: state.errorMessage, emoji: "😕", showsClose: true)
        case .complete:
            if state.isPreview { previewCompleteView } else { completeView }
        case .reviewing:
            reviewingView
        }
    }

    /// Whether the four grades stand in a column beside the card instead of a row beneath it.
    ///
    /// A preview has no grade column to stand anywhere: what replaces the four buttons is a single
    /// "Next", which is the wrong shape for a 200pt column and belongs under the card at every
    /// width. So this is about the *grades*, not only about the window.
    private var gradesBeside: Bool { widthClass.isExpanded && !state.isPreview }

    private var reviewingView: some View {
        VStack(spacing: 14) {
            header
            ProgressView(value: state.progress)
                .tint(LoopkyColor.accentPrimary)
                .padding(.horizontal, 20)
            if gradesBeside { wideCardArea } else { stackedCardArea }
        }
        .padding(.top, 8)
        .padding(.bottom, 16)
        // Studying is a single-focus task, so it gets a narrow ceiling: a flashcard blown up to a
        // landscape iPad is 1200pt of white around one word, and four grade buttons stretched to
        // match are a 300pt-wide "Again". The wide ceiling is the same card plus the grade column.
        .contentPane(gradesBeside ? studyWidePaneWidth : studyPaneWidth)
    }

    private var stackedCardArea: some View {
        VStack(spacing: 14) {
            card.padding(.horizontal, 20)
            Spacer(minLength: 0)
            gradeArea
        }
    }

    /// Landscape stands the grades in a column beside the card instead of a row beneath it. The
    /// card is the tall thing on this screen and the window is the wide one, so the four buttons
    /// are the only content that can spend width without being stretched by it — and moving them
    /// out gives the card back the height they were taking off it.
    ///
    /// Shown, not reserved: on the front face the column is absent and the card is centred on its
    /// own, rather than sitting half a gutter off-centre for most of a session.
    private var wideCardArea: some View {
        HStack(alignment: .top, spacing: gradeColumnGap) {
            VStack(spacing: 14) {
                card
                Spacer(minLength: 0)
                // Everything the grade area still owes at this width — the flip hint, and a typing
                // card's suppressed hint. The four grades themselves are in the column to the right.
                if !state.gradesAvailable { gradeArea }
            }
            .frame(maxWidth: studyCardWidth)
            if state.gradesAvailable {
                gradeColumn.frame(width: gradeColumnWidth)
            }
        }
        .padding(.horizontal, 20)
        .animation(.easeOut(duration: 0.25), value: state.gradesAvailable)
    }

    private var card: some View {
        StudyCardView(
            state: state,
            typed: $typed,
            answerFocused: $answerFocused,
            onFlip: onReveal,
            onListen: onListen,
            onSpeak: onSpeak,
            onCheckAnswer: onCheckAnswer,
            onGiveUp: onGiveUp
        )
    }

    private var header: some View {
        HStack {
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(LoopkyColor.foregroundSecondary)
                    .frame(width: 34, height: 34)
                    .loopkyGlass(in: .circle)
            }
            .accessibilityLabel(Text("study_close"))
            Spacer()
            VStack(spacing: 2) {
                Text(state.deckTitle.uppercased())
                    .font(.system(size: 11, weight: .bold))
                    .kerning(0.6)
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                Text(String(
                    format: NSLocalizedString("study_position_of_total", comment: ""),
                    state.position, state.total
                ))
                .font(.system(size: 16, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            }
            Spacer()
            // Fixed width both ways so the title stays centred whether or not the badge is there.
            ZStack {
                if state.reversed { reversedBadge }
            }
            .frame(width: 34, height: 34)
        }
        .padding(.horizontal, 20)
    }

    private var reversedBadge: some View {
        Image(systemName: "arrow.left.arrow.right")
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(LoopkyColor.accentSecondary)
            .frame(width: 34, height: 34)
            .background(Circle().fill(LoopkyColor.accentSecondarySoft))
            .accessibilityLabel(Text("study_reversed_badge"))
    }

    /// Reserved height, so the card does not resize when the grades appear on reveal — except
    /// while a typing card is open, where the keyboard needs the room more than the hint does.
    @ViewBuilder
    private var gradeArea: some View {
        if state.gradesAvailable {
            gradeRow
        } else if state.previewAdvanceAvailable {
            // What stands in for the four grade buttons in a preview: move on, decide nothing.
            Button("study_preview_next", action: onNextCard)
                .buttonStyle(.loopkyFilled)
                .padding(.horizontal, 20)
                .accessibilityIdentifier("study_preview_next")
        } else if state.answerHidden && state.revealed {
            // A flipped typing card is answering with the keyboard up: the card needs the height
            // more than the hint does. On the *front* face the hint still belongs — the card is
            // waiting to be turned, exactly as it would be without typing enabled.
            EmptyView()
        } else {
            // A real control, not a caption. The card itself is tappable, but a tap gesture on a
            // shape is invisible to VoiceOver and to UI automation, so the hint carries the action
            // as well — same behaviour, reachable two ways.
            Button("study_flip_hint", action: onReveal)
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .frame(height: 20)
                .accessibilityIdentifier("study_reveal")
        }
    }

    private var gradeRow: some View {
        HStack(spacing: 8) {
            ForEach(StudyGrade.allCases, id: \.self) { grade in
                gradeButton(grade)
            }
        }
        .padding(.horizontal, 20)
    }

    /// The same four buttons, the same identifiers, standing up instead of lying down.
    private var gradeColumn: some View {
        VStack(spacing: 8) {
            ForEach(StudyGrade.allCases, id: \.self) { grade in
                gradeButton(grade)
            }
        }
    }

    private func gradeButton(_ grade: StudyGrade) -> some View {
        Button { onGrade(grade.shared) } label: {
            VStack(spacing: 2) {
                Text(grade.label)
                    .font(.system(size: 14, weight: .heavy))
                if let interval = state.intervals[grade], !interval.isEmpty {
                    Text(interval).font(.system(size: 11, weight: .medium)).opacity(0.9)
                }
            }
            .foregroundStyle(LoopkyColor.foregroundOnAccent)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 14).fill(grade.color))
        }
        .accessibilityIdentifier("study_\(grade.id)")
    }

    /// The end of a preview has nothing to report — no reviews were stored, so there is no
    /// next-due date and no tally. What it owes the reader instead is how to keep the progress it
    /// did not: an account for a visitor, or the deck itself for someone who already has one.
    private var previewCompleteView: some View {
        VStack(spacing: 12) {
            Spacer()
            Text("👀").font(.system(size: 56))
            Text("study_preview_complete_title")
                .font(.system(size: 22, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Text(String(
                format: NSLocalizedString(
                    state.reviewed == 1 ? "study_preview_subtitle_one" : "study_preview_subtitle_many",
                    comment: ""
                ),
                state.reviewed
            ))
            .font(.system(size: 14))
            .foregroundStyle(LoopkyColor.foregroundMuted)
            Text(state.isSignedIn ? "study_preview_member_detail" : "study_preview_guest_detail")
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 28)
            Spacer()
            if state.isSignedIn {
                Button("study_preview_back", action: onClose)
                    .buttonStyle(.loopkyFilled)
                    .padding(.horizontal, 20)
            } else {
                Button("study_preview_action_guest", action: onSignIn)
                    .buttonStyle(.loopkyFilled)
                    .padding(.horizontal, 20)
                // Simply leaving has to stay available beside the offer.
                Button("study_preview_back", action: onClose)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }
        }
        .padding(.bottom, 20)
        .contentPane(PaneWidth.focused)
    }

    private var completeView: some View {
        VStack(spacing: 12) {
            Spacer()
            Text("🎉").font(.system(size: 56))
            Text("study_complete_title")
                .font(.system(size: 22, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Text(String(
                format: NSLocalizedString(
                    state.reviewed == 1 ? "study_complete_subtitle_one" : "study_complete_subtitle_many",
                    comment: ""
                ),
                state.reviewed
            ))
            .font(.system(size: 14))
            .foregroundStyle(LoopkyColor.foregroundMuted)
            Spacer()
            Button("study_done", action: onClose).buttonStyle(.loopkyFilled).padding(.horizontal, 20)
        }
        .padding(.bottom, 20)
        .contentPane(PaneWidth.focused)
    }

    private func centered(
        title: LocalizedStringKey,
        subtitle: LocalizedStringKey? = nil,
        message: String? = nil,
        emoji: String,
        showsClose: Bool = false
    ) -> some View {
        VStack(spacing: 12) {
            Spacer()
            Text(emoji).font(.system(size: 48))
            Text(title)
                .font(.system(size: 20, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            if let subtitle {
                Text(subtitle).font(.system(size: 14)).foregroundStyle(LoopkyColor.foregroundMuted)
            }
            if let message {
                Text(message)
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                    .multilineTextAlignment(.center)
            }
            Spacer()
            if showsClose {
                Button("study_back", action: onClose).buttonStyle(.loopkySoft).padding(.horizontal, 20)
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 20)
        .contentPane(PaneWidth.focused)
    }

    private func syncBanner(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("study_sync_error_title").font(.system(size: 13, weight: .heavy))
            Text(message).font(.system(size: 12))
            Button("study_sync_error_dismiss", action: onDismissSyncError)
                .font(.system(size: 12, weight: .semibold))
        }
        .foregroundStyle(LoopkyColor.foregroundOnAccent)
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(LoopkyColor.danger))
        .padding(.horizontal, 20)
        .contentPane(PaneWidth.reading)
    }
}

/// How wide the study column is allowed to get.
///
/// Between `PaneWidth.focused` and `.reading`: a card wants more room than a sign-in form, because
/// a wider box lets a long sentence stay large — but less than a settings list, because the grade
/// row underneath is four buttons that should stay thumb-sized rather than growing into banners.
private let studyPaneWidth: CGFloat = 640

/// The card at its usual width plus the grade column and the gap between them.
private let studyWidePaneWidth: CGFloat = 880

/// The card keeps the width it has on a phone; only its position changes.
private let studyCardWidth: CGFloat = 640

/// Wide enough for "Again" and its interval on one line each.
private let gradeColumnWidth: CGFloat = 200

/// Between the card and the grades.
private let gradeColumnGap: CGFloat = 24
