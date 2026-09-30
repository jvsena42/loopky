import SwiftUI

/// Keep or discard each imported card. Pure layout.
///
/// A swipeable stack, as on Android: the top card follows the finger, tilts, and flies off in the
/// direction it was thrown, while the card behind it grows in to take its place. The three round
/// buttons underneath are the same three decisions for anyone who would rather tap — and they run
/// the *same* animation, so the two ways of answering do not look like two different screens.
struct TriageView: View {
    var state: TriageViewState = TriageViewState()
    var onKeep: () -> Void = {}
    var onDiscard: () -> Void = {}
    var onUndo: () -> Void = {}
    var onApproveAll: () -> Void = {}
    var onBack: () -> Void = {}
    var onEdit: () -> Void = {}

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// A decision made by tapping, handed to the card stack so the fly-off plays before the
    /// ViewModel advances. Cleared by the stack once it has committed.
    @State private var pendingCommit: TriageSwipeDirection?

    var body: some View {
        VStack(spacing: 0) {
            topBar
            content
        }
        .loopkyScreenBackground(LoopkyColor.surfaceSecondary)
        .navigationBarHidden(true)
    }

    private var topBar: some View {
        ZStack {
            Text("triage_title")
                .font(.system(size: 18, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            HStack(spacing: 4) {
                Button(action: onBack) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(LoopkyColor.foregroundPrimary)
                }
                .accessibilityLabel(Text("publish_back"))

                Spacer()

                // Persistent, not a transient toast: the point is recovering work you spent time
                // on — an image attached in the triage editor — which outlives a snackbar.
                Button(action: onUndo) {
                    Image(systemName: "arrow.uturn.backward")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(
                            state.canUndo ? LoopkyColor.foregroundPrimary : LoopkyColor.borderSubtle
                        )
                }
                .disabled(!state.canUndo)
                .accessibilityLabel(Text("triage_undo"))
                .accessibilityIdentifier("triage_undo")

                Button("triage_approve_all", action: onApproveAll)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(LoopkyColor.accentPrimary)
                    .padding(.leading, 8)
                    .accessibilityIdentifier("triage_approve_all")
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }

    private var content: some View {
        VStack(spacing: 16) {
            progressRow

            if let card = state.card {
                // The *region* takes all the leftover height, so the buttons stay pinned to the
                // bottom of the screen; the card inside it is what gets capped and centred. Capping
                // the region instead left an iPad with the card and its buttons bunched under the
                // title and 300pt of empty cream below them.
                GeometryReader { geo in
                    TriageCardStack(
                        current: card,
                        next: state.next,
                        width: geo.size.width,
                        reduceMotion: reduceMotion,
                        pendingCommit: $pendingCommit,
                        onKeep: onKeep,
                        onDiscard: onDiscard
                    )
                    .frame(width: geo.size.width, height: geo.size.height)
                }
                // A fresh stack per card: the drag offset and the entrance animation start clean
                // for the card coming in, rather than inheriting the fly-off of the one that left.
                .id(state.position)
            } else {
                Spacer()
            }

            if let errorMessage = state.errorMessage {
                Text(errorMessage)
                    .font(.system(size: 13))
                    .foregroundStyle(LoopkyColor.danger)
            }

            if state.hasCard { actionRow }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 12)
        .contentPane()
    }

    private var progressRow: some View {
        HStack {
            Text(String(
                format: NSLocalizedString("triage_progress", comment: ""),
                state.position, state.total
            ))
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(LoopkyColor.foregroundMuted)
            .accessibilityIdentifier("triage_progress")

            Spacer()

            Text(String(
                format: NSLocalizedString("triage_stats", comment: ""),
                state.keptCount, state.discardedCount
            ))
            .font(.system(size: 12))
            .foregroundStyle(LoopkyColor.foregroundMuted)
        }
    }

    /// Mirrors the swipe gestures, in the same left-to-right order: discard, edit, keep.
    private var actionRow: some View {
        HStack(spacing: 16) {
            TriageCircleButton(kind: .discard) { pendingCommit = .discard }
            TriageCircleButton(kind: .edit, action: onEdit)
            TriageCircleButton(kind: .keep) { pendingCommit = .keep }
        }
        .frame(maxWidth: .infinity)
    }

}

enum TriageSwipeDirection { case keep, discard }

/// Android's portrait floor for a triage card, used here as a ceiling: a phone's stack region is
/// already shorter than this, so it never bites there, while an iPad's card would otherwise stretch
/// to a full-height poster.
private let triageCardMaxHeight: CGFloat = 540

/// One of the three round verdict buttons. The look of each is a property of *which* verdict it
/// is, so it lives with the case rather than at the call site.
private struct TriageCircleButton: View {
    enum Kind {
        case discard, edit, keep

        var systemImage: String {
            switch self {
            case .discard: return "xmark"
            case .edit: return "pencil"
            case .keep: return "checkmark"
            }
        }

        var label: LocalizedStringKey {
            switch self {
            case .discard: return "triage_discard"
            case .edit: return "triage_edit"
            case .keep: return "triage_keep"
            }
        }

        var identifier: String {
            switch self {
            case .discard: return "triage_discard"
            case .edit: return "triage_edit"
            case .keep: return "triage_keep"
            }
        }

        var background: Color {
            switch self {
            case .discard: return LoopkyColor.dangerSoft
            case .edit: return LoopkyColor.surfaceCard
            case .keep: return LoopkyColor.srsGood
            }
        }

        var tint: Color {
            switch self {
            case .discard: return LoopkyColor.srsAgain
            case .edit: return LoopkyColor.foregroundSecondary
            case .keep: return LoopkyColor.foregroundOnAccent
            }
        }

        /// Edit is the smaller of the three: it is the way out of a badly split card, not one of
        /// the two verdicts the screen is asking for.
        var diameter: CGFloat { self == .edit ? 48 : 56 }
        var iconSize: CGFloat { self == .edit ? 18 : 24 }
    }

    let kind: Kind
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: kind.systemImage)
                .font(.system(size: kind.iconSize, weight: .bold))
                .foregroundStyle(kind.tint)
                .frame(width: kind.diameter, height: kind.diameter)
                .background(Circle().fill(kind.background))
        }
        .accessibilityLabel(Text(kind.label))
        .accessibilityIdentifier(kind.identifier)
    }
}

/// The two cards on screen and the gesture that moves the top one.
///
/// Recreated per card by the `.id` above, which is what makes `offsetX` and the entrance scale
/// reset for the incoming card — the SwiftUI equivalent of Android's per-card swipe controller.
private struct TriageCardStack: View {
    let current: TriageCardFace
    let next: TriageCardFace?
    let width: CGFloat
    let reduceMotion: Bool
    @Binding var pendingCommit: TriageSwipeDirection?
    let onKeep: () -> Void
    let onDiscard: () -> Void

    @State private var offsetX: CGFloat = 0
    @State private var enterScale: CGFloat = 0.96

    /// Far enough to be a throw rather than a nudge, and far enough off-screen to be gone.
    private var threshold: CGFloat { max(width * 0.25, 1) }
    private var flingDistance: CGFloat { max(width * 1.2, 1) }

    /// Drag progress toward the commit threshold: +1 fully right (keep), -1 fully left (discard).
    private var progress: CGFloat { min(max(offsetX / threshold, -1), 1) }

    var body: some View {
        ZStack {
            if let next { peek(next) }
            topCard
        }
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 0.22)) { enterScale = 1 }
        }
        .onChange(of: pendingCommit) { _, direction in
            if let direction { commit(direction) }
        }
    }

    /// The card behind, growing in as the top one is dragged away so the hand-off is continuous
    /// rather than a cut.
    private func peek(_ face: TriageCardFace) -> some View {
        let reveal = abs(progress)
        return TriageCardFaceView(face: face)
            .frame(maxHeight: triageCardMaxHeight)
            .scaleEffect(reduceMotion ? 0.94 : lerp(0.94, 1, reveal))
            .opacity(reduceMotion ? 1 : Double(lerp(0.55, 1, reveal)))
            .offset(y: reduceMotion ? 0 : lerp(24, 0, reveal))
    }

    private var topCard: some View {
        ZStack {
            TriageCardFaceView(face: current, isInteractive: true)
            // Inside the moving layer, so the verdict rides along with the card.
            feedback(systemImage: "checkmark", background: LoopkyColor.srsGood, amount: max(progress, 0))
            feedback(systemImage: "xmark", background: LoopkyColor.srsAgain, amount: max(-progress, 0))
        }
        .frame(maxHeight: triageCardMaxHeight)
        .scaleEffect(reduceMotion ? 1 : enterScale)
        .rotationEffect(.degrees(Double(progress) * 8))
        .offset(x: offsetX)
        .gesture(
            // Width only: a vertical flick on the card is not a verdict.
            DragGesture(minimumDistance: 8)
                .onChanged { offsetX = $0.translation.width }
                .onEnded { _ in
                    if offsetX > threshold {
                        commit(.keep)
                    } else if offsetX < -threshold {
                        commit(.discard)
                    } else {
                        withAnimation(.spring(response: 0.45, dampingFraction: 0.6)) { offsetX = 0 }
                    }
                }
        )
    }

    private func feedback(systemImage: String, background: Color, amount: CGFloat) -> some View {
        Image(systemName: systemImage)
            .font(.system(size: 44, weight: .bold))
            .foregroundStyle(LoopkyColor.foregroundOnAccent)
            .frame(width: 96, height: 96)
            .background(Circle().fill(background))
            .scaleEffect(lerp(0.6, 1, amount))
            .opacity(Double(amount))
            .allowsHitTesting(false)
    }

    /// Fly the card off, then report the decision. Reduce Motion skips straight to reporting it —
    /// the decision is the feature, the flight is the flourish.
    private func commit(_ direction: TriageSwipeDirection) {
        let report = {
            pendingCommit = nil
            if direction == .keep { onKeep() } else { onDiscard() }
        }
        guard !reduceMotion else {
            report()
            return
        }
        withAnimation(.easeIn(duration: 0.26)) {
            offsetX = direction == .keep ? flingDistance : -flingDistance
        } completion: {
            report()
        }
    }

    private func lerp(_ start: CGFloat, _ end: CGFloat, _ fraction: CGFloat) -> CGFloat {
        start + (end - start) * fraction
    }
}

/// One card, both sides showing — this is a review of what the parser produced, not a quiz.
private struct TriageCardFaceView: View {
    let face: TriageCardFace
    var isInteractive: Bool = false

    var body: some View {
        VStack(spacing: 0) {
            label("triage_front_label")
            Text(face.front.isEmpty ? "—" : face.front)
                .font(.system(size: 28, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
                .accessibilityIdentifier(identifier("triage_front"))

            Rectangle()
                .fill(LoopkyColor.accentPrimary)
                .frame(width: 40, height: 2)
                .padding(.vertical, 16)

            label("triage_back_label")
            Text(face.back.isEmpty ? "—" : face.back)
                .font(.system(size: 20))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
                .accessibilityIdentifier(identifier("triage_back"))
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(RoundedRectangle(cornerRadius: 24).fill(LoopkyColor.surfaceCard))
        .shadow(color: LoopkyColor.shadowElevationHigh, radius: 16, y: 6)
        .accessibilityIdentifier(identifier("triage_card"))
    }

    /// The peek card carries the *next* card's text, so it gets its own identifiers: two views
    /// answering to `triage_front` would make `snapshot-ui` — and every journey driving it —
    /// pick between them by luck.
    private func identifier(_ base: String) -> String {
        isInteractive ? base : "\(base)_peek"
    }

    private func label(_ key: LocalizedStringKey) -> some View {
        Text(key)
            .font(.system(size: 11, weight: .bold))
            .kerning(1)
            .foregroundStyle(LoopkyColor.foregroundMuted)
    }
}
