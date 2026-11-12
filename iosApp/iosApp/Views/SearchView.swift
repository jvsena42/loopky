import SwiftUI
import Shared

/// One person in the results.
struct SearchPersonData: Identifiable {
    let id: String
    let label: String
    let shortPubky: String
    let initial: String
    var avatarUrl: String?
    let isFollowing: Bool
    let isFollowPending: Bool
}

/// One deck in the results.
struct SearchDeckData: Identifiable {
    let id: String
    let authorPubky: String
    let title: String
    let cardCount: Int
    let coverEmoji: String
    let authorLabel: String
    var coverImage: MediaRef.Image?
}

/// What a pasted address already names, before anything is asked of the network.
struct SearchDirectHit {
    let isDeck: Bool
    let subtitle: String
}

struct SearchViewState {
    var query: String = ""
    var isSearching = false
    var isEmpty = false
    var directHit: SearchDirectHit?
    var isSignInLink = false
    var canApproveSignIn = false
    var people: [SearchPersonData] = []
    var decks: [SearchDeckData] = []
}

/// Pure layout — state comes from the shared `SearchViewModel` via `SearchScreen`.
///
/// Mirrors Android: people and decks are two lists rather than one ranked feed, because a person
/// and a deck are not alternatives and merging them would bury whichever kind was meant.
struct SearchView: View {
    var state = SearchViewState()
    var onQueryChange: (String) -> Void = { _ in }
    var onSubmit: () -> Void = {}
    /// Read a pubky off a QR code rather than typing 52 characters of base32.
    var onScan: () -> Void = {}
    var onOpenDirectHit: () -> Void = {}
    var onGetPubkyRing: () -> Void = {}
    var onApproveSignIn: () -> Void = {}
    var onPersonTap: (String) -> Void = { _ in }
    var onFollowTap: (String) -> Void = { _ in }
    var onDeckTap: (String, String) -> Void = { _, _ in }

    @FocusState private var isFocused: Bool

    @Environment(\.loopkyWidthClass) private var widthClass

    var body: some View {
        VStack(spacing: 0) {
            searchField
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    // The address the text already names, above anything the indexer has to be
                    // asked for: certain, instant, and the only result that reaches an account no
                    // index has seen yet.
                    if let hit = state.directHit { directHitRow(hit) }
                    if state.isSignInLink { signInLinkRow }
                    if state.isSearching { ProgressView().frame(maxWidth: .infinity) }
                    if !state.people.isEmpty { peopleSection }
                    if !state.decks.isEmpty { decksSection }
                    if state.isEmpty { emptyBlock }
                    if state.query.isEmpty {
                        Text("search_hint")
                            .font(.system(size: 13))
                            .foregroundColor(LoopkyColor.foregroundMuted)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 8)
                .contentPane(PaneWidth.wide)
            }
        }
        .background(LoopkyColor.surfacePrimary)
        // The screen exists to be typed into; landing on it with the keyboard down costs a tap
        // that has no other purpose.
        .task { isFocused = true }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundColor(LoopkyColor.foregroundMuted)
            TextField(
                "search_placeholder",
                text: Binding(get: { state.query }, set: onQueryChange)
            )
            .focused($isFocused)
            .submitLabel(.search)
            .onSubmit(onSubmit)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .foregroundColor(LoopkyColor.foregroundPrimary)
            if !state.query.isEmpty {
                Button(action: { onQueryChange("") }) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(LoopkyColor.foregroundMuted)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("search_clear"))
            }
            Button(action: onScan) {
                Image(systemName: "qrcode.viewfinder")
                    .foregroundColor(LoopkyColor.accentSecondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("search_scan_qr"))
            .accessibilityIdentifier("search_scan")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 14).fill(LoopkyColor.surfaceCard))
        .padding(.horizontal, 20)
        .padding(.bottom, 8)
        // The same ceiling the results below get: a field three times the width of the rows under
        // it reads as belonging to a different screen.
        .contentPane(PaneWidth.wide)
    }

    private func directHitRow(_ hit: SearchDirectHit) -> some View {
        Button(action: onOpenDirectHit) {
            VStack(alignment: .leading, spacing: 4) {
                Text(hit.isDeck ? "search_open_deck" : "search_open_profile")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(LoopkyColor.foregroundPrimary)
                Text(hit.subtitle)
                    .font(.system(size: 12))
                    .foregroundColor(LoopkyColor.foregroundMuted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 20).fill(LoopkyColor.surfaceCard))
        }
        .buttonStyle(.plain)
    }

    /// A pasted sign-in request: says where it goes instead of searching for it. With the key held
    /// here, Loopky approves it itself; otherwise the way on is Pubky Ring.
    private var signInLinkRow: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("search_sign_in_link_title")
                .font(.system(size: 15, weight: .bold))
                .foregroundColor(LoopkyColor.foregroundPrimary)
            Text("search_sign_in_link_body")
                .font(.system(size: 13))
                .foregroundColor(LoopkyColor.foregroundMuted)
            if state.canApproveSignIn {
                Button("search_sign_in_link_approve", action: onApproveSignIn)
                    .buttonStyle(.loopkySoft)
                    .padding(.top, 8)
                    .accessibilityIdentifier("search_approve_sign_in")
            } else {
                Button("backup_ring_install", action: onGetPubkyRing)
                    .buttonStyle(.loopkySoft)
                    .padding(.top, 8)
                    .accessibilityIdentifier("search_get_ring")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 20).fill(LoopkyColor.surfaceCard))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("search_sign_in_link")
    }

    private var peopleSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("search_people_title")
            ForEach(state.people) { person in
                personRow(person)
            }
        }
    }

    private func personRow(_ person: SearchPersonData) -> some View {
        HStack(spacing: 10) {
            PubkyAvatarView(initial: person.initial, avatarUrl: person.avatarUrl)
            VStack(alignment: .leading, spacing: 2) {
                Text(person.label)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(LoopkyColor.foregroundPrimary)
                    .lineLimit(1)
                Text(person.shortPubky)
                    .font(.system(size: 11))
                    .foregroundColor(LoopkyColor.foregroundMuted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { onPersonTap(person.id) }
            Button(action: { onFollowTap(person.id) }) {
                Text(person.isFollowing ? "component_author_row_following" : "component_author_row_follow")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(person.isFollowing ? LoopkyColor.accentSecondary : .white)
                    .padding(.horizontal, 14)
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
    }

    private var decksSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionHeader("search_decks_title")
            LazyVGrid(columns: deckGridItems(widthClass), spacing: 14) {
                ForEach(state.decks) { deck in
                    DeckTileView(
                        title: deck.title,
                        cardCount: deck.cardCount,
                        coverEmoji: deck.coverEmoji,
                        authorLabel: deck.authorLabel,
                        coverImage: deck.coverImage,
                        authorPubky: deck.authorPubky,
                        deckId: deck.id,
                        onTap: { onDeckTap(deck.authorPubky, deck.id) }
                    )
                }
            }
        }
    }

    private var emptyBlock: some View {
        VStack(spacing: 8) {
            Text("🔍").font(.system(size: 36))
            Text(String(format: NSLocalizedString("search_empty_title", comment: ""), state.query))
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(LoopkyColor.foregroundPrimary)
            Text("search_empty_subtitle")
                .font(.system(size: 13))
                .foregroundColor(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 24)
    }

    private func sectionHeader(_ key: LocalizedStringKey) -> some View {
        Text(key)
            .font(.system(size: 13, weight: .bold))
            .foregroundColor(LoopkyColor.foregroundSecondary)
    }
}
