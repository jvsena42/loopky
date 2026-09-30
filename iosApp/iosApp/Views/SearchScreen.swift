import SwiftUI
import Shared

/// VM-driven wrapper around the presentational `SearchView`.
///
/// Note the teardown call: androidx's own `ViewModel.clear()` is internal and is not exported to
/// Objective-C, so releasing a VM goes through `IosDependencies.clear(viewModel:)`.
struct SearchScreen: View {
    var onOpenProfile: (String) -> Void = { _ in }
    /// `(deckId, authorPubky)` — in that order. The two screens used to disagree.
    var onOpenDeck: (_ deckId: String, _ authorPubky: String) -> Void = { _, _ in }
    /// Browsing without an account: following a person from here raises a prompt instead.
    var isGuest: Bool = false
    var onSignIn: () -> Void = {}

    @State private var viewModel: SearchViewModel?
    @State private var uiState: SearchUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var signInReason: SignInReason?
    @State private var isScanning = false
    @State private var followError: String?

    var body: some View {
        SearchView(
            state: viewState,
            onQueryChange: { viewModel?.onQueryChange(raw: $0) },
            onSubmit: { viewModel?.onSubmit() },
            onScan: { isScanning = true },
            onOpenDirectHit: { viewModel?.onSubmit() },
            onPersonTap: { viewModel?.onOpenProfile(pubky: $0) },
            onFollowTap: { viewModel?.onFollowToggle(pubky: $0) },
            onDeckTap: { author, deckId in
                viewModel?.onOpenDeck(authorPubky: author, deckId: deckId)
            }
        )
        .sheet(isPresented: $isScanning) {
            QrScannerSheet(
                onScanned: { scanned in
                    isScanning = false
                    // Straight into the field: search already accepts a pasted pubky, and a
                    // scanned one is the same input arriving by a shorter route.
                    viewModel?.onQueryChange(raw: scanned)
                },
                onClose: { isScanning = false }
            )
        }
        .signInPrompt(
            reason: signInReason,
            onSignIn: { signInReason = nil; onSignIn() },
            onDismiss: { signInReason = nil }
        )
        .overlay(alignment: .bottom) {
            if let followError {
                Text(verbatim: followError)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundOnAccent)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(LoopkyColor.foregroundPrimary.opacity(0.9)))
                    .padding(.bottom, 90)
            }
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var viewState: SearchViewState {
        guard let state = uiState else { return SearchViewState() }
        return SearchViewState(
            query: state.query,
            isSearching: state.isSearching,
            isEmpty: state.isEmpty,
            directHit: state.directLink.map(directHit),
            people: state.people.map { person in
                let identity = IdentityData(person.identity)
                return SearchPersonData(
                    id: person.identity.pubky,
                    label: identity.label,
                    shortPubky: identity.shortPubky,
                    initial: identity.initial,
                    avatarUrl: identity.avatarUrl,
                    isFollowing: person.isFollowing,
                    isFollowPending: person.isFollowPending
                )
            },
            decks: state.decks.map { deck in
                SearchDeckData(
                    id: deck.id,
                    authorPubky: deck.authorPubky,
                    title: deck.title,
                    cardCount: Int(deck.cardCount),
                    coverEmoji: deck.coverEmoji,
                    authorLabel: IdentityData(deck.author).label,
                    coverImage: deck.coverImage
                )
            }
        )
    }

    private func directHit(_ link: PubkyLink) -> SearchDirectHit {
        if let deck = link as? PubkyLinkDeck {
            return SearchDirectHit(isDeck: true, subtitle: deck.deckId)
        }
        return SearchDirectHit(isDeck: false, subtitle: link.pubky)
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.searchViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? SearchUiState }
        guard effectSink == nil else { return }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case let open as SearchEffectOpenProfile:
                onOpenProfile(open.pubky)
            case let open as SearchEffectOpenDeck:
                onOpenDeck(open.deckId, open.authorPubky)
            case let require as SearchEffectRequireSignIn:
                signInReason = require.reason
            case let failed as SearchEffectShowFollowError:
                // The pill reverts itself, but silently — without this the follow just undoes
                // itself and the reason never reaches the user. Discover already says it, and so
                // does Android's search.
                flash(ErrorCopy.message(for: failed.reason))
            default:
                break
            }
        }
    }

    private func flash(_ message: String) {
        withAnimation { followError = message }
        Task {
            try? await Task.sleep(for: .seconds(2))
            withAnimation { followError = nil }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
