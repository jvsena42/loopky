import SwiftUI

/// The daily goal, met.
///
/// A full-screen moment rather than a banner, and the only modal thing in a study session. It is
/// shown once a day, so the interruption is cheap — and "Keep studying" is right there, because
/// **the goal is announced, never enforced**: the queue behind this is still full, and meeting a
/// target must never be the thing that ends a session.
struct GoalCelebrationView: View {
    let newCardsToday: Int
    /// Met on the session's last card: no "Keep studying", and `onDone` is the single way out.
    var sessionEnded: Bool = false
    var onKeepStudying: () -> Void = {}
    var onDone: () -> Void = {}

    var body: some View {
        ZStack {
            LoopkyColor.surfacePrimary.ignoresSafeArea()
            ConfettiView()
            VStack(spacing: 12) {
                Text("🎉").font(.system(size: 64))
                Text("study_goal_reached_title")
                    .font(.system(size: 26, weight: .heavy))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                    .multilineTextAlignment(.center)
                Text(verbatim: String(
                    format: NSLocalizedString(
                        newCardsToday == 1
                            ? "study_goal_reached_count_one"
                            : "study_goal_reached_count_many",
                        comment: ""
                    ),
                    newCardsToday
                ))
                .font(.system(size: 16))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
                if sessionEnded {
                    Button("study_done", action: onDone)
                        .buttonStyle(.loopkyFilled)
                        .padding(.top, 12)
                        .accessibilityIdentifier("study_goal_dismiss")
                } else {
                    Text("study_goal_reached_body")
                        .font(.system(size: 14))
                        .foregroundStyle(LoopkyColor.foregroundSecondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 4)

                    // Both ways out, equal in weight but not in emphasis: the card behind this is
                    // already loaded, and the goal withholds nothing.
                    Button("study_goal_keep_studying", action: onKeepStudying)
                        .buttonStyle(.loopkyFilled)
                        .padding(.top, 12)
                        .accessibilityIdentifier("study_goal_keep_studying")
                    Button("study_goal_done", action: onDone)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(LoopkyColor.foregroundMuted)
                }
            }
            .padding(.horizontal, 32)
            // The one modal moment in a session, and it covers the whole screen — so on an iPad
            // its two buttons need a ceiling of their own or they run the width of the display.
            .contentPane(PaneWidth.focused)
        }
        // Swallows taps so a stray press does not reach the card underneath.
        .contentShape(Rectangle())
        .onTapGesture {}
        .accessibilityIdentifier("study_goal_reached")
    }
}

/// Falling brand-coloured confetti. One of the few places Loopky builds something custom: there is
/// no native equivalent, and this is the app's own celebration.
///
/// Honours Reduce Motion by rendering nothing rather than a slower animation — the point of the
/// setting is the absence of unnecessary movement, not less of it.
struct ConfettiView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static let pieces = 40
    private let colors: [Color] = [
        LoopkyColor.srsGood, LoopkyColor.accentPrimary, LoopkyColor.srsEasy, LoopkyColor.srsHard,
    ]

    @State private var fallen = false

    var body: some View {
        GeometryReader { geometry in
            if !reduceMotion {
                ForEach(0..<Self.pieces, id: \.self) { index in
                    piece(index: index, in: geometry.size)
                }
            }
        }
        .allowsHitTesting(false)
        .onAppear { fallen = true }
    }

    /// Split out because the whole thing inline defeats the type-checker.
    private func piece(index: Int, in size: CGSize) -> some View {
        let seed = Double(index)
        let column = (seed * 97).truncatingRemainder(dividingBy: max(size.width, 1))
        let duration = 2.2 + seed.truncatingRemainder(dividingBy: 7) * 0.2
        return Rectangle()
            .fill(colors[index % colors.count])
            .frame(width: 8, height: 14)
            .rotationEffect(.degrees(seed * 37))
            .position(x: column, y: fallen ? size.height + 40 : -40)
            .animation(.easeIn(duration: duration).delay(seed * 0.04), value: fallen)
    }
}
