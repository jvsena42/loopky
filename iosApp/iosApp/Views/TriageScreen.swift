import SwiftUI
import Shared

/// Review each imported card before it is published — spec §5.5.
///
/// One card at a time, keep or discard, with an undo for the decision just made. The same screen
/// sits between both import sources and the publish step.
struct TriageScreen: View {
    var onBack: () -> Void = {}
    var onPublish: () -> Void = {}
    /// The draft row to edit, by its index in the parse — not its position in the queue, which
    /// shifts as cards are discarded.
    var onEditCard: (Int) -> Void = { _ in }

    @State private var viewModel: TriageViewModel?
    @State private var uiState: TriageUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    var body: some View {
        TriageView(
            state: viewState,
            onKeep: { viewModel?.onKeep() },
            onDiscard: { viewModel?.onDiscard() },
            onUndo: { viewModel?.onUndo() },
            onApproveAll: { viewModel?.onApproveAll() },
            onBack: { viewModel?.onBackClick() },
            onEdit: { viewModel?.onEditClick() }
        )
        .onAppear {
            attach()
            // The draft can change while this screen is backgrounded — a card edited and saved
            // comes back here, so the queue is re-read rather than trusted.
            viewModel?.refresh()
        }
        .onDisappear { detach() }
    }

    private var viewState: TriageViewState {
        guard let state = uiState else { return TriageViewState() }
        // The card *behind* the one being decided, so the stack can reveal it as the top card is
        // dragged away. Nil on the last card, which simply has nothing behind it.
        let following = state.cards.indices.contains(Int(state.currentIndex) + 1)
            ? state.cards[Int(state.currentIndex) + 1]
            : nil
        return TriageViewState(
            card: state.currentCard.map { TriageCardFace(front: $0.front, back: $0.back) },
            next: following.map { TriageCardFace(front: $0.front, back: $0.back) },
            position: Int(state.currentIndex) + 1,
            total: Int(state.total),
            keptCount: Int(state.keptCount),
            discardedCount: Int(state.discardedCount),
            canUndo: state.canUndo,
            errorMessage: state.error
        )
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.triageViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? TriageUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is TriageEffectNavigatePublish: onPublish()
            case is TriageEffectNavigateBack: onBack()
            case let edit as TriageEffectNavigateEditCard: onEditCard(Int(edit.rowIndex))
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

struct TriageViewState {
    /// The card being decided. Nil once the queue is exhausted.
    var card: TriageCardFace?
    /// The one behind it, drawn in the stack and revealed by the swipe.
    var next: TriageCardFace?
    var position: Int = 0
    var total: Int = 0
    var keptCount: Int = 0
    var discardedCount: Int = 0
    var canUndo: Bool = false
    var errorMessage: String?

    var hasCard: Bool { card != nil }
}

/// Both sides of one card, which is all the stack draws — triage is a review of what the parser
/// produced, so nothing here is hidden behind a flip.
struct TriageCardFace: Equatable {
    var front: String = ""
    var back: String = ""
}
