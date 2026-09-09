import SwiftUI
import Shared

struct DiscoverPersonData: Identifiable {
    let id: String
    let label: String
    let shortPubky: String
    let initial: String
    /// The person's picture. A plain URL, unlike a deck cover — profile avatars are not blobs.
    var avatarUrl: String?
    var isFollowing: Bool = false
    var isFollowPending: Bool = false
}

struct DiscoverDeckData: Identifiable {
    /// Author-scoped: two authors can publish decks sharing an id.
    var id: String { "\(authorPubky)/\(deckId)" }
    let deckId: String
    let authorPubky: String
    let title: String
    let cardCount: Int
    let coverEmoji: String
    let authorLabel: String
    var coverImage: MediaRef.Image?
}

/// One independently-loading strip, mirroring the shared `SectionState`.
struct DiscoverSection<Item> {
    var items: [Item] = []
    var isLoading: Bool = false
    var errorMessage: String?

    var isEmpty: Bool { items.isEmpty && !isLoading && errorMessage == nil }
}

struct DiscoverViewState {
    var topics: [String] = []
    var people = DiscoverSection<DiscoverPersonData>()
    var browse = DiscoverSection<DiscoverDeckData>()
    var following = DiscoverSection<DiscoverDeckData>()
    var selectedTag: String?
}

/// Pure layout — state comes from the shared `DiscoverViewModel` via `DiscoverScreen`.
///
/// Mirrors Android: following nobody is a normal state, not an empty one, so the global browse
/// strip carries the screen and the followed strip only appears once it has something to say.
struct DiscoverView: View {
    var state = DiscoverViewState()
    var onTagTap: (String?) -> Void = { _ in }
    var onSearchTap: () -> Void = {}
    var onPersonTap: (String) -> Void = { _ in }
    var onFollowTap: (String) -> Void = { _ in }
    var onDeckTap: (String, String) -> Void = { _, _ in }
    var onRetryFollowing: () -> Void = {}
    var isGuest: Bool = false
    var onSignIn: () -> Void = {}

    @Environment(\.loopkyWidthClass) private var widthClass

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                if isGuest { guestBanner }
                if !state.topics.isEmpty { topicRow }
                // Picking a topic is an explicit question, so its answer leads. Unfiltered, browse
                // is the fallback firehose and sits under the people and decks you chose — which
                // costs a new account nothing, because the followed strip hides itself when empty.
                if state.selectedTag != nil { browseStrip }
                if !state.people.isEmpty { peopleStrip }
                if !state.following.items.isEmpty || state.following.errorMessage != nil {
                    followingStrip
                }
                if state.selectedTag == nil { browseStrip }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            // The tab bar floats over the content, so the grid reserves room for it the way Home
            // and the library do — without it the last row's title and caption end the scroll
            // behind the bar and cannot be read at all. A guest has no tab bar (`MainView`'s
            // guest shell is Discover alone), so there is nothing there to clear.
            .padding(.bottom, isGuest ? 24 : 100)
            // Mostly tile grids and horizontal strips, so it gets the widest ceiling — but a
            // ceiling all the same, or the guest banner's two lines of copy run the full 1366pt.
            .contentPane(PaneWidth.wide)
        }
        .background(LoopkyColor.surfacePrimary)
    }

    /// What replaces the three tabs a guest does not have: a way *in*, not a wall.
    private var guestBanner: some View {
        // The action sits beside the copy rather than under it: the banner is one line of pitch and
        // one button, and stacking them made a short card twice as tall while leaving the whole
        // right half of it empty at every width above a phone.
        HStack(alignment: .center, spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                Text("guest_banner_title")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundColor(LoopkyColor.foregroundPrimary)
                Text("guest_banner_body")
                    .font(.system(size: 13))
                    .foregroundColor(LoopkyColor.foregroundSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button("guest_banner_cta", action: onSignIn)
                .buttonStyle(.loopkyCompactFilled)
                // The copy wraps; the button does not. Without this a narrow window squeezes
                // "Get started" onto two lines before it takes a second line of body text.
                .fixedSize()
                .accessibilityIdentifier("guest_banner_cta")
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18).fill(LoopkyColor.accentPrimarySoft))
    }

    private var header: some View {
        HStack {
            Text("discover_title")
                .font(.system(size: 28, weight: .heavy))
                .foregroundColor(LoopkyColor.foregroundPrimary)
            Spacer()
            // A magnifier alone: what it means needs no label, and search reaches everything the
            // old "Add friend" pill did — pasting a pubky is one of the things it accepts now,
            // rather than the only thing it could do.
            Button(action: onSearchTap) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(LoopkyColor.accentSecondary)
                    .padding(10)
                    .background(Circle().fill(LoopkyColor.accentSecondarySoft))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("discover_search"))
        }
    }

    private var topicRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(state.topics, id: \.self) { topic in
                    TagChipView(
                        tag: topic,
                        onTap: { onTagTap(topic) },
                        isSelected: state.selectedTag == topic
                    )
                }
            }
        }
    }

    private var peopleStrip: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("discover_people_title")
            if state.people.isLoading {
                ProgressView().frame(maxWidth: .infinity)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(state.people.items) { person in
                            personTile(person)
                        }
                    }
                }
            }
        }
    }

    private func personTile(_ person: DiscoverPersonData) -> some View {
        VStack(spacing: 6) {
            PubkyAvatarView(initial: person.initial, avatarUrl: person.avatarUrl, size: 56)
            Text(person.label)
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(LoopkyColor.foregroundPrimary)
                .lineLimit(1)
            Text(person.shortPubky)
                .font(.system(size: 11))
                .foregroundColor(LoopkyColor.foregroundMuted)
                .lineLimit(1)
            Button(action: { onFollowTap(person.id) }) {
                Text(person.isFollowing ? "component_author_row_following" : "component_author_row_follow")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(person.isFollowing ? LoopkyColor.accentSecondary : .white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 6)
                    .background(
                        Capsule().fill(
                            person.isFollowing ? LoopkyColor.accentSecondarySoft : LoopkyColor.accentSecondary
                        )
                    )
            }
            .buttonStyle(.plain)
            .disabled(person.isFollowPending)
            .opacity(person.isFollowPending ? 0.5 : 1)
        }
        .frame(width: 120)
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 20).fill(LoopkyColor.surfaceCard))
        .contentShape(RoundedRectangle(cornerRadius: 20))
        .onTapGesture { onPersonTap(person.id) }
        // A tap gesture on a stack is not a control: without this the tile is invisible to
        // VoiceOver and unreachable by UI automation, and opening a person's profile is the
        // whole point of the strip. The Follow button inside keeps its own action.
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { onPersonTap(person.id) }
        .accessibilityIdentifier("discover_person")
    }

    private var browseStrip: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                if let tag = state.selectedTag {
                    Text(String(format: NSLocalizedString("discover_browse_tag_title", comment: ""), tag))
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(LoopkyColor.foregroundSecondary)
                    Spacer()
                    Button("discover_clear_tag") { onTagTap(nil) }
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(LoopkyColor.accentPrimary)
                } else {
                    sectionHeader("discover_browse_title")
                }
            }
            if state.browse.isLoading {
                ProgressView().frame(maxWidth: .infinity)
            } else if state.browse.isEmpty {
                browseEmpty
            } else {
                deckGrid(state.browse.items)
            }
        }
    }

    private var browseEmpty: some View {
        VStack(spacing: 8) {
            Text(state.selectedTag == nil ? "🌱" : "🔍").font(.system(size: 36))
            Text(state.selectedTag == nil ? "discover_browse_empty_title" : "discover_empty_tag_subtitle")
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(LoopkyColor.foregroundPrimary)
            Text("discover_browse_empty_subtitle")
                .font(.system(size: 13))
                .foregroundColor(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
            Button(action: onSearchTap) {
                Text("discover_search_cta")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .background(Capsule().fill(LoopkyColor.accentSecondary))
            }
            .buttonStyle(.plain)
            .padding(.top, 4)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 24)
    }

    private var followingStrip: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("discover_following_title")
            if let message = state.following.errorMessage {
                VStack(spacing: 8) {
                    Text(message)
                        .font(.system(size: 13))
                        .foregroundColor(LoopkyColor.foregroundMuted)
                    Button("home_retry", action: onRetryFollowing)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(LoopkyColor.accentPrimary)
                }
                .frame(maxWidth: .infinity)
            } else {
                deckGrid(state.following.items)
            }
        }
    }

    private func deckGrid(_ decks: [DiscoverDeckData]) -> some View {
        LazyVGrid(columns: deckGridItems(widthClass), spacing: 14) {
            ForEach(decks) { deck in
                DeckTileView(
                    title: deck.title,
                    cardCount: deck.cardCount,
                    coverEmoji: deck.coverEmoji,
                    authorLabel: deck.authorLabel,
                    coverImage: deck.coverImage,
                    authorPubky: deck.authorPubky,
                    deckId: deck.deckId,
                    onTap: { onDeckTap(deck.authorPubky, deck.deckId) }
                )
            }
        }
    }

    private func sectionHeader(_ key: LocalizedStringKey) -> some View {
        Text(key)
            .font(.system(size: 13, weight: .bold))
            .foregroundColor(LoopkyColor.foregroundSecondary)
    }
}

#Preview {
    DiscoverView(
        state: DiscoverViewState(
            topics: ["spanish", "biology"],
            people: DiscoverSection(items: [
                DiscoverPersonData(id: "ada", label: "Ada Lovelace", shortPubky: "ada…xyz", initial: "A"),
            ]),
            browse: DiscoverSection(items: [
                DiscoverDeckData(
                    deckId: "1",
                    authorPubky: "abc",
                    title: "Spanish basics",
                    cardCount: 24,
                    coverEmoji: "📚",
                    authorLabel: "Ada"
                ),
            ])
        )
    )
}
