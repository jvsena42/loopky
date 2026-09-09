import SwiftUI
import Shared

// TODO(iOS Koin + SKIE): wire the shared `HomeViewModel` once the Kotlin framework is
// bootstrapped on iOS. Until then this view renders the empty-state fallback so the
// design can be iterated on without blocking on the DI wiring.

/// SwiftUI Home screen: the daily study state, and the empty state before any deck exists.
struct HomeView: View {
    var greetingName: String = "there"
    var state: HomeViewState = .empty
    var onCreateDeck: () -> Void = {}
    var onBrowseExamples: () -> Void = {}
    var onStartStudy: () -> Void = {}
    var onOpenDeck: (String) -> Void = { _ in }
    /// "See all" over today's decks — the full library, i.e. the Decks tab.
    var onSeeAllDecks: () -> Void = {}

    @Environment(\.loopkyWidthClass) private var widthClass

    var body: some View {
        ZStack {
            LoopkyColor.surfacePrimary.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    GreetingHeader(name: greetingName)
                    switch state {
                    case .loading:
                        ProgressView().frame(maxWidth: .infinity)
                    case .empty:
                        EmptyStateCard()
                        HomeCtaButtons(
                            onCreateDeck: onCreateDeck,
                            onBrowseExamples: onBrowseExamples
                        )
                    case .content(let content):
                        if widthClass.isExpanded {
                            wideContent(content)
                        } else {
                            hero(content)
                            TodaysDecksSection(
                                decks: content.decks,
                                onOpenDeck: onOpenDeck,
                                onSeeAll: onSeeAllDecks
                            )
                        }
                    case .error(let message):
                        Text("home_error_title")
                            .font(.system(size: 20, weight: .heavy))
                            .foregroundColor(LoopkyColor.foregroundPrimary)
                        Text(message)
                            .font(.system(size: 14))
                            .foregroundColor(LoopkyColor.foregroundMuted)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 100)
                // After the background and the scroll, so the cream still reaches both edges of an
                // iPad and only the content inside is bounded. Wide enough for two panes side by
                // side; narrower, the due-today hero is a number and its caption, and unbounded
                // they drift to opposite ends of the card and read as two unrelated things.
                .contentPane(widthClass.isExpanded ? PaneWidth.wide : PaneWidth.reading)
            }
        }
    }

    /// Home on an iPad in landscape: the day's headline beside the decks, rather than stacked above
    /// them.
    ///
    /// Two things are different from the compact layout, both about the same problem. The hero is
    /// pinned to a column of its own instead of running the full width — a "2 / cards to review"
    /// card stretched across 1100pt puts its number and its caption a hand's width apart. And the
    /// deck list runs two across, because the stacked pair used barely a third of the height and
    /// left the rest of the screen empty.
    private func wideContent(_ content: HomeContentData) -> some View {
        HStack(alignment: .top, spacing: 24) {
            hero(content)
                .frame(width: heroPaneWidth)
            TodaysDecksSection(
                decks: content.decks,
                columns: wideDeckColumns,
                onOpenDeck: onOpenDeck,
                onSeeAll: onSeeAllDecks
            )
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// Nothing due *and* nothing unseen is a different card, not a hero reading zero: a primary CTA
    /// whose only outcome is "All done!" is a dead end dressed as an action.
    @ViewBuilder
    private func hero(_ content: HomeContentData) -> some View {
        if content.countsKnown && content.dueToday == 0 && content.newToday == 0 {
            CaughtUpCard(nextDueAtMillis: content.nextDueAtMillis)
        } else {
            DueTodayHeroCard(
                dueToday: content.dueToday,
                doneToday: content.doneToday,
                newCardsToday: content.newCardsToday,
                newCardsGoal: content.newCardsGoal,
                countsKnown: content.countsKnown,
                onStartStudy: onStartStudy
            )
        }
    }
}

/// The hero column's width — the number, its caption and the Start studying button, at the size
/// they are on a phone.
private let heroPaneWidth: CGFloat = 340

/// Two across inside the right pane. The window may be wide, but this pane is only part of it, so
/// the screen-wide count would give rows too narrow to read.
private let wideDeckColumns = 2

enum HomeViewState: Equatable {
    case loading
    case empty
    case content(HomeContentData)
    case error(String)
}

/// Everything the loaded Today screen renders.
///
/// A struct rather than four associated values on the case: the hero grew a goal tally and the
/// screen grew a caught-up state, and a five-tuple stops being readable at the call site.
struct HomeContentData: Equatable {
    var dueToday: Int = 0
    var doneToday: Int = 0
    /// Cards never studied. Separate from due, because nothing about an unseen card is late.
    var newToday: Int = 0
    var newCardsToday: Int = 0
    var newCardsGoal: Int = 0
    var nextDueAtMillis: Int64?
    var decks: [HomeDeckSummary] = []
    /// Whether the counts above are real numbers rather than placeholders. False only on the
    /// cached first paint, where the decks are known and the review state is not — with it
    /// ignored, a launch opened on "You're all caught up" over a deck with cards due.
    var countsKnown: Bool = true
}

struct HomeDeckSummary: Equatable, Identifiable {
    let id: String
    let title: String
    let cardCount: Int
    let dueCount: Int
    /// Cards never studied. Nothing about an unseen card is late, so the row says "N new" rather
    /// than "0 due" when a deck has only these.
    var newCount: Int = 0
    /// See `HomeContentData.countsKnown`: false means [dueCount] is a placeholder, not a claim.
    var countsKnown: Bool = true
    let coverInitial: String
    var coverImage: MediaRef.Image?
    var authorPubky: String = ""

    // `MediaRef.Image` is a Kotlin class, so identity comparison is what `Equatable` can offer
    // here — enough for SwiftUI to notice a deck's cover arriving.
    static func == (lhs: HomeDeckSummary, rhs: HomeDeckSummary) -> Bool {
        lhs.id == rhs.id && lhs.title == rhs.title && lhs.cardCount == rhs.cardCount
            && lhs.dueCount == rhs.dueCount && lhs.newCount == rhs.newCount
            && lhs.coverInitial == rhs.coverInitial
            && lhs.coverImage === rhs.coverImage && lhs.authorPubky == rhs.authorPubky
    }
}

#if DEBUG
struct HomeView_Previews: PreviewProvider {
    static var previews: some View {
        Group {
            HomeView(greetingName: "Maria", state: .empty)
                .previewDisplayName("Empty")
            HomeView(
                greetingName: "Maria",
                state: .content(HomeContentData(
                    dueToday: 24,
                    doneToday: 8,
                    decks: [
                        HomeDeckSummary(id: "1", title: "Spanish Basics", cardCount: 42, dueCount: 12, coverInitial: "S"),
                        HomeDeckSummary(id: "2", title: "Bio 101: Cells", cardCount: 28, dueCount: 7, coverInitial: "B"),
                        HomeDeckSummary(id: "3", title: "Guitar Chords", cardCount: 18, dueCount: 5, coverInitial: "G"),
                    ]
                ))
            )
            .previewDisplayName("Content")
        }
    }
}
#endif
