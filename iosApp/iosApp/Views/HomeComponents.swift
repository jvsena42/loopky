import SwiftUI
import Shared

struct GreetingHeader: View {
    let name: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("home_greeting_hello")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(LoopkyColor.foregroundMuted)
            Text(String(format: NSLocalizedString("home_greeting_name", comment: ""), name))
                .font(.system(size: 24, weight: .heavy))
                .foregroundColor(LoopkyColor.foregroundPrimary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct EmptyStateCard: View {
    var body: some View {
        VStack(spacing: 20) {
            ZStack {
                RoundedRectangle(cornerRadius: 28)
                    .fill(LoopkyColor.accentPrimarySoft)
                    .frame(width: 140, height: 140)
                Text("📚").font(.system(size: 64))
            }
            Text("home_empty_title")
                .font(.system(size: 24, weight: .heavy))
                .foregroundColor(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
            Text("home_empty_subtitle")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
                .lineSpacing(4)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 28)
        .padding(.vertical, 36)
        .background(
            RoundedRectangle(cornerRadius: 28).fill(LoopkyColor.surfaceCard)
        )
        .shadow(color: LoopkyColor.shadowElevationHigh, radius: 24, x: 0, y: 10)
    }
}

struct HomeCtaButtons: View {
    let onCreateDeck: () -> Void
    let onBrowseExamples: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Button("home_create_first_deck", action: onCreateDeck)
                .buttonStyle(.loopkyFilled)
                .shadow(color: LoopkyColor.shadowAccent, radius: 24, x: 0, y: 8)
            Button("home_browse_examples", action: onBrowseExamples)
                .buttonStyle(.loopkySoft)
        }
    }
}

struct DueTodayHeroCard: View {
    let dueToday: Int
    let doneToday: Int
    /// The day's tally against the goal. **Announced, never enforced** — the queue behind this
    /// serves every due card and every unseen one regardless, so this reports, it does not cap.
    var newCardsToday: Int = 0
    var newCardsGoal: Int = 0
    /// See `HomeContentData.countsKnown`. False draws the same card with a dash where the number
    /// goes and an indeterminate bar, so nothing moves when the real count lands.
    var countsKnown: Bool = true
    let onStartStudy: () -> Void

    private var progress: CGFloat? {
        guard countsKnown else { return nil }
        guard dueToday > 0 else { return 0 }
        return min(1, max(0, CGFloat(doneToday) / CGFloat(dueToday)))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("home_due_today")
                .font(.system(size: 11, weight: .bold))
                .kerning(1)
                .foregroundColor(LoopkyColor.foregroundOnAccentMuted)
            HStack(alignment: .bottom) {
                Text(countsKnown ? "\(dueToday)" : "—")
                    .font(.system(size: 72, weight: .heavy))
                    .foregroundColor(.white)
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text("home_cards")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                    Text("home_to_review")
                        .font(.system(size: 13))
                        .foregroundColor(LoopkyColor.foregroundOnAccentMuted)
                }
                .padding(.bottom, 12)
            }
            VStack(alignment: .leading, spacing: 6) {
                if let progress {
                    ProgressView(value: progress)
                        .progressViewStyle(.linear)
                        .tint(.white)
                } else {
                    // A bare track, drawn rather than asked for. `ProgressView()` with no value is
                    // *documented* as indeterminate but renders as a motionless track here
                    // (measured: two frames 0.45s apart are byte-identical), and the style it
                    // falls back to is the OS's choice — a spinner would change the card's height
                    // and undo the "nothing moves" property the dash was picked for. Drawing the
                    // track pins both the look and the height.
                    Capsule()
                        .fill(Color.white.opacity(0.25))
                        .frame(height: 8)
                        .accessibilityLabel(Text("home_checking_due"))
                }
                Text(countsKnown
                     ? String(
                        format: NSLocalizedString("home_progress_done", comment: ""),
                        doneToday, dueToday
                     )
                     : NSLocalizedString("home_checking_due", comment: ""))
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(LoopkyColor.foregroundOnAccentMuted)
                Text(verbatim: newCardsToday >= newCardsGoal
                     ? String(
                        format: NSLocalizedString("home_new_cards_goal_reached", comment: ""),
                        newCardsGoal
                     )
                     : String(
                        format: NSLocalizedString("home_new_cards_goal", comment: ""),
                        newCardsToday, newCardsGoal
                     ))
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(LoopkyColor.foregroundOnAccentMuted)
            }
            Button(action: onStartStudy) {
                HStack(spacing: 8) {
                    Image(systemName: "play.fill")
                    Text("home_start_studying")
                }
            }
            // The pill sits *on the accent*, so it takes on-accent colours, not the app's surface
            // family. `surfaceCard` happens to be white in light mode, which is why the two were
            // indistinguishable until dark mode turned this into a hole in the orange card.
            .buttonStyle(LoopkyFilledButtonStyle(
                fill: LoopkyColor.foregroundOnAccent,
                foreground: LoopkyColor.accentPrimary,
                verticalPadding: 16
            ))
        }
        .padding(24)
        .background(RoundedRectangle(cornerRadius: 28).fill(LoopkyColor.accentPrimary))
        .shadow(color: LoopkyColor.shadowAccent, radius: 32, x: 0, y: 12)
    }
}

struct TodaysDecksSection: View {
    let decks: [HomeDeckSummary]
    /// How many rows stand side by side. One on a phone; two inside the right pane of the wide
    /// layout, where a single column of rows would leave most of the pane empty.
    var columns: Int = 1
    let onOpenDeck: (String) -> Void
    var onSeeAll: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("home_todays_decks")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(LoopkyColor.foregroundPrimary)
                Spacer()
                // A Button, not a styled Text. It was the latter for as long as it existed: it
                // looked like the control Android has, announced nothing to VoiceOver, and did
                // nothing when tapped.
                Button(action: onSeeAll) {
                    Text("home_see_all")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(LoopkyColor.accentSecondary)
                }
                .accessibilityIdentifier("home_see_all_decks")
            }
            if columns > 1 {
                LazyVGrid(
                    columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: columns),
                    alignment: .leading,
                    spacing: 12
                ) {
                    ForEach(decks) { deck in
                        DeckRow(deck: deck, onTap: { onOpenDeck(deck.id) })
                    }
                }
            } else {
                ForEach(decks) { deck in
                    DeckRow(deck: deck, onTap: { onOpenDeck(deck.id) })
                }
            }
        }
    }
}

struct DeckRow: View {
    let deck: HomeDeckSummary
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 14) {
                // Initial first, cover over it: the letter is the fallback, so a deck with a
                // picture must not show both. The image is sized to the tile rather than left to
                // grow, or the row with a cover stands taller than the row without.
                ZStack {
                    RoundedRectangle(cornerRadius: 14)
                        .fill(LoopkyColor.accentPrimarySoft)
                    Text(deck.coverInitial)
                        .font(.system(size: 22, weight: .heavy))
                        .foregroundColor(LoopkyColor.accentPrimary)
                    if deck.coverImage != nil {
                        CardMediaImage(
                            ref: deck.coverImage,
                            authorPubky: deck.authorPubky,
                            deckId: deck.id,
                            contentMode: .fill
                        )
                        .frame(width: 56, height: 56)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                }
                .frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 4) {
                    Text(deck.title)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(LoopkyColor.foregroundPrimary)
                    // The cached first paint knows the deck and not its badge.
                    Text(deck.countsKnown
                         ? String(
                            format: NSLocalizedString("home_deck_due_cards", comment: ""),
                            deck.dueCount, deck.cardCount
                         )
                         // localizedStringWithFormat, not String(format:): `card_count` is a
                         // plural entry, and only this one resolves the variation — the other
                         // renders "1 cards".
                         : String.localizedStringWithFormat(
                            NSLocalizedString("card_count", comment: ""),
                            deck.cardCount
                         ))
                        .font(.system(size: 13))
                        .foregroundColor(LoopkyColor.foregroundMuted)
                }
                Spacer()
                Text(deck.countsKnown ? "\(deck.dueCount)" : "—")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(LoopkyColor.accentPrimary))
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 20).fill(LoopkyColor.surfaceCard))
            .shadow(color: LoopkyColor.shadowElevationMedium, radius: 18, x: 0, y: 6)
        }
        .buttonStyle(.plain)
    }
}

private let sampleHomeDecks = [
    HomeDeckSummary(id: "1", title: "Spanish Basics", cardCount: 42, dueCount: 12, coverInitial: "S"),
    HomeDeckSummary(id: "2", title: "Bio 101: Cells", cardCount: 28, dueCount: 7, coverInitial: "B"),
    HomeDeckSummary(id: "3", title: "Guitar Chords", cardCount: 18, dueCount: 5, coverInitial: "G"),
]

#Preview("Content") {
    ScrollView {
        VStack(spacing: 16) {
            GreetingHeader(name: "Maria")
            DueTodayHeroCard(dueToday: 24, doneToday: 8, onStartStudy: {})
            TodaysDecksSection(decks: sampleHomeDecks, onOpenDeck: { _ in })
        }
        .padding()
    }
    .background(LoopkyColor.surfacePrimary)
}

#Preview("Empty") {
    ScrollView {
        VStack(spacing: 16) {
            GreetingHeader(name: "Maria")
            EmptyStateCard()
            HomeCtaButtons(onCreateDeck: {}, onBrowseExamples: {})
        }
        .padding()
    }
    .background(LoopkyColor.surfacePrimary)
}

/// Nothing due and nothing unseen.
///
/// Says *when* the next review lands, which is what makes an empty queue read as earned rather
/// than as a dead end. The interval comes from `RelativeDateTimeFormatter` rather than a ported
/// helper — the system already words this, in the reader's own language.
struct CaughtUpCard: View {
    let nextDueAtMillis: Int64?

    var body: some View {
        VStack(spacing: 10) {
            Text("🎉").font(.system(size: 40))
            Text("home_caught_up_title")
                .font(.system(size: 22, weight: .heavy))
                .foregroundColor(LoopkyColor.foregroundPrimary)
                .multilineTextAlignment(.center)
            Text(verbatim: subtitle)
                .font(.system(size: 14))
                .foregroundColor(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(28)
        .background(RoundedRectangle(cornerRadius: 28).fill(LoopkyColor.accentPrimarySoft))
        .accessibilityIdentifier("home_caught_up")
    }

    private var subtitle: String {
        guard let millis = nextDueAtMillis else {
            return NSLocalizedString("home_caught_up_no_next_due", comment: "")
        }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        let relative = formatter.localizedString(
            for: Date(timeIntervalSince1970: Double(millis) / 1000),
            relativeTo: Date()
        )
        return String(format: NSLocalizedString("home_caught_up_next_due", comment: ""), relative)
    }
}
