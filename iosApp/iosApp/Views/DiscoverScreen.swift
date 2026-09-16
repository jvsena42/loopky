import SwiftUI
import Shared

/// VM-driven wrapper around the presentational `DiscoverView`.
///
/// Note the teardown call: androidx's own `ViewModel.clear()` is internal and is not exported to
/// Objective-C, so releasing a VM goes through `IosDependencies.clear(viewModel:)`.
struct DiscoverScreen: View {
    var onOpenProfile: (String) -> Void = { _ in }
    /// `(deckId, authorPubky)` — in that order. The two screens used to disagree.
    var onOpenDeck: (_ deckId: String, _ authorPubky: String) -> Void = { _, _ in }
    var onSearch: () -> Void = {}
    /// Browsing without an account: adds the banner, and every write raises a prompt.
    var isGuest: Bool = false
    var onSignIn: () -> Void = {}

    @State private var viewModel: DiscoverViewModel?
    @State private var uiState: DiscoverUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    /// Held here rather than in state: the shared VM reports the reason as a one-shot effect, so
    /// the prompt's lifetime is the screen's business.
    @State private var signInReason: SignInReason?
    @State private var followError: String?

    var body: some View {
        DiscoverView(
            state: viewState,
            onTagTap: { selectTag(labelled: $0) },
            onSearchTap: { viewModel?.onSearch() },
            onPersonTap: { viewModel?.onOpenAuthor(pubky: $0) },
            onFollowTap: { viewModel?.onFollowToggle(pubky: $0) },
            onDeckTap: { author, deckId in
                viewModel?.onOpenDeck(authorPubky: author, deckId: deckId)
            },
            onRetryFollowing: { viewModel?.onRetryFollowing() },
            onBrowseEndReached: { viewModel?.onBrowseEndReached() },
            onPeopleEndReached: { viewModel?.onPeopleEndReached() },
            onGridColumnsChanged: { viewModel?.onGridColumnsChanged(columns: Int32($0)) },
            onRetryBrowse: { viewModel?.onRetryBrowse() },
            onRetryBrowsePage: { viewModel?.onRetryBrowsePage() },
            isGuest: isGuest,
            onSignIn: onSignIn
        )
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

    private var viewState: DiscoverViewState {
        guard let state = uiState else { return DiscoverViewState() }
        return DiscoverViewState(
            topics: state.topics.items.map { KotlinInterop.tagLabel($0) },
            people: section(state.people) { person in
                let identity = IdentityData(person.identity)
                return DiscoverPersonData(
                    id: person.identity.pubky,
                    label: identity.label,
                    shortPubky: identity.shortPubky,
                    initial: identity.initial,
                    avatarUrl: identity.avatarUrl,
                    isFollowing: person.isFollowing,
                    isFollowPending: person.isFollowPending
                )
            },
            browse: section(state.browse, transform: deckData),
            following: section(state.following, transform: deckData),
            selectedTag: state.selectedTag.map { KotlinInterop.tagLabel($0) }
        )
    }

    private func deckData(_ deck: DiscoverDeck) -> DiscoverDeckData {
        DiscoverDeckData(
            deckId: deck.id,
            authorPubky: deck.authorPubky,
            title: deck.title,
            cardCount: Int(deck.cardCount),
            coverEmoji: deck.coverEmoji,
            authorLabel: IdentityData(deck.author).label,
            coverImage: deck.coverImage
        )
    }

    /// Selects by **label**, never by handing back the `Tag` found in state.
    ///
    /// `Tag` is a Kotlin value class: boxed inside `List<Tag>`, but erased to `String` at a
    /// parameter position. Passing the boxed object to `onTagSelected` therefore gave the bridge a
    /// Kotlin object where it expected an `NSString`, and the reinterpreted pointer read back a
    /// null `value` — crashing in `sanitizeLabel` the moment a topic chip was tapped. The label
    /// crosses as itself, and Kotlin rebuilds the `Tag` on its own side.
    private func selectTag(labelled label: String?) {
        viewModel?.onTagLabelSelected(label: label)
    }

    /// Item types erase to `Any` across the bridge, so each strip is mapped from its erased list.
    private func section<Wire: AnyObject, Item>(
        _ state: SectionState<Wire>,
        transform: (Wire) -> Item
    ) -> DiscoverSection<Item> {
        DiscoverSection(
            items: state.items.compactMap { ($0 as? Wire).map(transform) },
            isLoading: state.isLoading,
            errorMessage: state.error.map { ErrorCopy.message(for: $0) },
            hasMore: state.hasMore,
            isLoadingMore: state.isLoadingMore,
            pageErrorMessage: state.pageError.map { ErrorCopy.message(for: $0) }
        )
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.discoverViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? DiscoverUiState }
        guard effectSink == nil else { return }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case let open as DiscoverEffectOpenProfile:
                onOpenProfile(open.pubky)
            case let open as DiscoverEffectOpenDeck:
                onOpenDeck(open.deckId, open.authorPubky)
            case is DiscoverEffectOpenSearch:
                onSearch()
            case let require as DiscoverEffectRequireSignIn:
                signInReason = require.reason
            case let failed as DiscoverEffectShowFollowError:
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
