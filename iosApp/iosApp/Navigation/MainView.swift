import SwiftUI
import Shared

struct MainView: View {
    @State private var selectedTab: LoopkyTab = .study

    /// `(deckId, authorPubky)`; the author is `nil` for a deck you own.
    var onDeckTap: (String, String?) -> Void = { _, _ in }
    var onImportTap: () -> Void = {}
    var onImportFileTap: () -> Void = {}
    var onCreateDeckTap: () -> Void = {}
    var onSignedOut: () -> Void = {}
    var onStartStudy: () -> Void = {}
    var onOpenSettings: () -> Void = {}
    var onOpenProfile: (String) -> Void = { _ in }
    var onOpenFollows: (String, FollowSource) -> Void = { _, _ in }
    var onBackUpNow: () -> Void = {}
    /// Browsing without an account.
    ///
    /// The guest shell is **Discover alone, with no tab bar** — Today, Decks and Profile are each
    /// a view onto a library, a review queue and an identity that do not exist yet, so a guest tab
    /// set of four would be one working destination and three apologies. What replaces them is the
    /// banner at the top of Discover, which is a way *in* rather than a wall.
    var isGuest: Bool = false
    /// Leave the guest shell for the sign-in flow.
    var onSignIn: () -> Void = {}

    /// Search is presented over the tabs rather than pushed: it is a way to reach a screen, not a
    /// place in the tab hierarchy, and dismissing it must return to whatever tab asked for it.
    @State private var isSearching = false

    var body: some View {
        Group {
            if isGuest { guestShell } else { tabs }
        }
        .sheet(isPresented: $isSearching) {
            SearchScreen(
                onOpenProfile: { pubky in isSearching = false; onOpenProfile(pubky) },
                onOpenDeck: { deckId, author in
                    isSearching = false
                    onDeckTap(deckId, author)
                },
                isGuest: isGuest,
                onSignIn: { isSearching = false; onSignIn() }
            )
        }
    }

    /// Discover alone — see [isGuest].
    private var guestShell: some View {
        DiscoverScreen(
            onOpenProfile: onOpenProfile,
            onOpenDeck: { deckId, author in onDeckTap(deckId, author) },
            onSearch: { isSearching = true },
            isGuest: true,
            onSignIn: onSignIn
        )
        .navigationBarHidden(true)
    }

    private var tabs: some View {
        // A native `TabView`, and on the iOS 26 SDK that is already Liquid Glass — the tab bar
        // floats over the content and samples it, so Loopky only tints it and never repaints it.
        //
        // `.sidebarAdaptable` is the iPad half of #173, and the counterpart of the navigation rail
        // #140 gave Android on expanded windows: at a regular width the same four destinations
        // become a sidebar, with the tab-bar morph and the drag-to-reorder iPad users expect, and
        // at a compact width — an iPhone, or this app in Slide Over — it is an ordinary tab bar.
        // The destinations, their order and their tags are unchanged, so every deeplink and every
        // journey step still lands where it did.
        TabView(selection: $selectedTab) {
            Tab(LoopkyTab.study.title, systemImage: LoopkyTab.study.iconName, value: LoopkyTab.study) {
                HomeScreen(
                    onOpenDeck: { onDeckTap($0, nil) },
                    onCreateDeck: onCreateDeckTap,
                    onBrowseExamples: onImportTap,
                    onStartStudy: onStartStudy,
                    onSignedOut: onSignedOut,
                    // The library is a tab, not a push: "See all" over today's decks selects it,
                    // the way Android's does.
                    onSeeAllDecks: { selectedTab = .decks }
                )
            }

            Tab(LoopkyTab.decks.title, systemImage: LoopkyTab.decks.iconName, value: LoopkyTab.decks) {
                DecksScreen(
                    onDeckTap: { onDeckTap($0, nil) },
                    onImportTap: onImportTap,
                    onImportFileTap: onImportFileTap,
                    onCreateDeckTap: onCreateDeckTap
                )
            }

            Tab(LoopkyTab.discover.title, systemImage: LoopkyTab.discover.iconName, value: LoopkyTab.discover) {
                DiscoverScreen(
                    onOpenProfile: onOpenProfile,
                    onOpenDeck: { deckId, author in onDeckTap(deckId, author) },
                    onSearch: { isSearching = true }
                )
            }

            Tab(LoopkyTab.profile.title, systemImage: LoopkyTab.profile.iconName, value: LoopkyTab.profile) {
                ProfileScreen(
                    onSignedOut: onSignedOut,
                    onOpenFollows: onOpenFollows,
                    onOpenSettings: onOpenSettings,
                    onBackUpNow: onBackUpNow
                )
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .tint(LoopkyColor.accentPrimary)
        // Tab screens render their own in-content titles, so hide the NavigationStack's empty
        // navigation bar — otherwise it reserves space above each page title.
        .navigationBarHidden(true)
    }
}
