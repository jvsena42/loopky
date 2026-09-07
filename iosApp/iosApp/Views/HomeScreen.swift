import SwiftUI
import Shared

/// VM-driven wrapper around the presentational `HomeView`.
///
/// Tab-root screens are created/disposed on every appear/disappear (mirrors the Android
/// pager, which recreates the VM when a page leaves composition), so the VM lives in
/// `@State` and is rebuilt in `onAppear` rather than `init`.
struct HomeScreen: View {
    var onOpenDeck: (String) -> Void = { _ in }
    var onCreateDeck: () -> Void = {}
    var onBrowseExamples: () -> Void = {}
    var onStartStudy: () -> Void = {}
    var onSignedOut: () -> Void = {}
    var onSeeAllDecks: () -> Void = {}

    @State private var viewModel: HomeViewModel?
    @State private var uiState: HomeUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    var body: some View {
        HomeView(
            greetingName: greetingName,
            state: viewState,
            onCreateDeck: { viewModel?.onCreateDeckClick() },
            onBrowseExamples: { viewModel?.onBrowseExamplesClick() },
            onStartStudy: { viewModel?.onStartStudyClick() },
            onOpenDeck: { viewModel?.onDeckClick(deckId: $0) },
            onSeeAllDecks: { viewModel?.onSeeAllDecksClick() }
        )
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    /// The state carries who the user is; naming them is this layer's job.
    private var greetingName: String {
        let identity: PubkyIdentity?
        switch uiState {
        case let empty as HomeUiStateEmpty: identity = empty.identity
        case let content as HomeUiStateContent: identity = content.identity
        case let error as HomeUiStateError: identity = error.identity
        default: identity = nil
        }
        guard let identity else { return NSLocalizedString("identity_unknown_person", comment: "") }
        return IdentityData(identity).label
    }

    private var viewState: HomeViewState {
        switch uiState {
        case is HomeUiStateEmpty:
            return .empty
        case let content as HomeUiStateContent:
            return .content(HomeContentData(
                // `studyTarget`, not `dueToday`: today's intent is everything overdue plus
                // whatever room the new-cards goal has left. Showing the bare due count put
                // "0 cards to review" above Start studying while an unseen card was waiting.
                dueToday: Int(content.studyTarget),
                doneToday: Int(content.doneToday),
                newToday: Int(content.newToday),
                newCardsToday: Int(content.newCardsToday),
                newCardsGoal: Int(content.newCardsGoal),
                nextDueAtMillis: content.nextDueAtMillis?.int64Value,
                decks: content.decks.map { deck in
                    HomeDeckSummary(
                        id: deck.id,
                        title: deck.title,
                        cardCount: Int(deck.cardCount),
                        dueCount: Int(deck.dueCount),
                        countsKnown: content.countsKnown,
                        coverInitial: KotlinInterop.charToString(deck.coverInitial),
                        coverImage: deck.coverImage,
                        authorPubky: deck.authorPubky
                    )
                },
                countsKnown: content.countsKnown
            ))
        case let error as HomeUiStateError:
            return .error(ErrorCopy.message(for: error.reason))
        default:
            return .loading
        }
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.homeViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? HomeUiState }
        guard effectSink == nil else { return }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is HomeEffectNavigateCreateDeck:
                onCreateDeck()
            case is HomeEffectNavigateBrowseExamples:
                onBrowseExamples()
            case is HomeEffectNavigateStartStudy:
                onStartStudy()
            case let navigate as HomeEffectNavigateDeck:
                onOpenDeck(navigate.deckId)
            case is HomeEffectNavigateToOnboarding:
                onSignedOut()
            case is HomeEffectNavigateAllDecks:
                onSeeAllDecks()
            default:
                break
            }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
