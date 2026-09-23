import SwiftUI
import Shared

/// VM-driven wrapper around the presentational `DeckDetailView`.
struct DeckDetailScreen: View {
    let deckId: String
    var authorPubky: String?
    var onBack: () -> Void = {}
    var onEditDeck: (String) -> Void = { _ in }
    var onStudy: () -> Void = {}
    var onDeleted: () -> Void = {}
    var onOpenTag: (String) -> Void = { _ in }
    /// A guest reached for Follow or Clone and chose to sign in.
    var onSignIn: () -> Void = {}
    /// The clone is what the user now owns, so the caller replaces this screen with it.
    var onOpenClone: (String) -> Void = { _ in }
    /// Flip through a deck nobody has kept, grading nothing.
    var onPreview: () -> Void = {}

    @State private var viewModel: DeckDetailViewModel?
    @State private var uiState: DeckDetailUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var shareTarget: ShareLinkTarget?
    @State private var toast: String?

    var body: some View {
        DeckDetailView(
            state: viewState,
            onBack: { viewModel?.onBackClick() },
            onEdit: { viewModel?.onEditClick() },
            onDelete: { viewModel?.onDeleteDeck() },
            onShare: { viewModel?.onShareClick() },
            onStudy: { viewModel?.onStudyClick() },
            onOpenTag: onOpenTag,
            onToggleFollow: { viewModel?.onToggleFollow() },
            onRefresh: { viewModel?.onRefresh() }
        )
        .alert("deck_detail_delete_dialog_title", isPresented: deleteConfirmBinding) {
            Button("deck_detail_delete_cancel", role: .cancel) { viewModel?.onDismissDelete() }
            Button("deck_detail_delete_confirm", role: .destructive) { viewModel?.onConfirmDelete() }
        } message: {
            Text(deleteMessage)
        }
        .sheet(item: $shareTarget) { ShareLinkSheet(target: $0) }
        // Raised by Edit on a deck you follow, the only route to a copy (#254). A sheet rather than
        // an alert because an alert snapshots its message: the "pick a different name" line could
        // never appear as the reader typed. See CopyDeckSheet.
        .sheet(isPresented: cloneConfirmBinding) {
            if let content {
                CopyDeckSheet(
                    sourceTitle: content.title,
                    cardCount: Int(content.totalCards),
                    isSourceName: { content.isSourceName(candidate: $0) },
                    onConfirm: { viewModel?.onConfirmClone(title: $0) },
                    onCancel: { viewModel?.onDismissClone() }
                )
            }
        }
        .signInPrompt(
            reason: content?.signInPrompt,
            onSignIn: { viewModel?.onDismissSignInPrompt(); onSignIn() },
            onDismiss: { viewModel?.onDismissSignInPrompt() }
        )
        .sharePrompt(
            prompt: content?.sharePrompt,
            onConfirm: { viewModel?.onShareConfirm() },
            onDismiss: { viewModel?.onShareDismiss() },
            onNeverAsk: { viewModel?.onShareNeverAsk() }
        )
        // A recoverable failure — a follow that did not land, say — shown without tearing down the
        // deck underneath it.
        .alert(
            Text(verbatim: content?.errorReason.map(ErrorCopy.title(for:)) ?? ""),
            isPresented: Binding(
                get: { content?.errorReason != nil },
                set: { if !$0 { viewModel?.onDismissError() } }
            )
        ) {
            Button("deck_detail_dismiss_error", role: .cancel) { viewModel?.onDismissError() }
        } message: {
            Text(verbatim: content?.errorReason.map(ErrorCopy.message(for:)) ?? "")
        }
        .overlay(alignment: .bottom) { toastView }
        .onAppear { attach() }
        // A copy replaces this screen in place rather than stacking a near-identical one on top,
        // so SwiftUI keeps the same view identity and the same @State — and `attach()` bails on a
        // ViewModel that is already there. Without this the copy showed the deck it was copied
        // from, still saying "Following", which looked exactly like the copy having failed.
        .onChange(of: deckId) { _, _ in
            detach()
            attach()
        }
        .onDisappear { detach() }
    }

    private var viewState: DeckDetailViewState {
        switch uiState {
        case let content as DeckDetailUiStateContent:
            return .content(DeckDetailContent(
                title: content.title,
                description: content.description_,
                coverEmoji: content.coverEmoji,
                coverImageUrl: content.coverImageUrl,
                coverImageBase64: content.coverImageBase64,
                author: IdentityData(content.author),
                isOwned: content.isOwned,
                tags: content.tags,
                totalCards: Int(content.totalCards),
                dueLabel: content.dueLabel,
                newCards: Int(content.newCards),
                canStudy: content.canStudy,
                masteredPercent: content.masteredPercent,
                cards: content.cardPreviews.map {
                    CardPreviewData(id: $0.id, front: $0.frontText, back: $0.backText)
                },
                isSignedIn: content.isSignedIn,
                isIncomplete: content.isIncomplete,
                isFollowing: content.isFollowing,
                isFollowPending: content.isFollowPending,
                isCloning: content.isCloning,
                canEdit: content.canEdit,
                clonedFromLabel: content.clonedFrom.map { IdentityData($0).label },
                followerCount: Int(content.followerCount),
                clonedCount: Int(content.clonedCount),
                canPreview: content.canPreview,
                listenEnabled: content.listenEnabled,
                speakEnabled: content.speakEnabled,
                typeEnabled: content.typeEnabled,
                reverseEnabled: content.reverseEnabled
            ))
        case let error as DeckDetailUiStateError:
            return .error(ErrorCopy.message(for: error.reason))
        default:
            return .loading
        }
    }

    private var content: DeckDetailUiStateContent? { uiState as? DeckDetailUiStateContent }

    /// Names the deck being deleted. Built with `String(format:)` rather than passed to
    /// `Text(_:)` as a key, which would render the format specifier verbatim.
    private var deleteMessage: String {
        guard let content else { return "" }
        return String(
            format: NSLocalizedString("deck_detail_delete_dialog_message", comment: ""),
            content.title
        )
    }

    @ViewBuilder
    private var toastView: some View {
        if let toast {
            Text(verbatim: toast)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(LoopkyColor.foregroundOnAccent)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Capsule().fill(LoopkyColor.foregroundPrimary.opacity(0.9)))
                .padding(.bottom, 90)
        }
    }

    private func flash(_ key: String) {
        withAnimation { toast = NSLocalizedString(key, comment: "") }
        Task {
            try? await Task.sleep(for: .seconds(2))
            withAnimation { toast = nil }
        }
    }

    private var cloneConfirmBinding: Binding<Bool> {
        Binding(
            get: { content?.showCloneConfirm == true },
            set: { if !$0 { viewModel?.onDismissClone() } }
        )
    }

    private var deleteConfirmBinding: Binding<Bool> {
        Binding(
            get: { (uiState as? DeckDetailUiStateContent)?.showDeleteConfirm == true },
            set: { if !$0 { viewModel?.onDismissDelete() } }
        )
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.deckDetailViewModel(
            deckId: deckId,
            authorPubky: authorPubky
        )
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? DeckDetailUiState }
        guard effectSink == nil else { return }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is DeckDetailEffectNavigateBack:
                onBack()
            case let edit as DeckDetailEffectNavigateEditDeck:
                onEditDeck(edit.deckId)
            case is DeckDetailEffectNavigateStudy:
                onStudy()
            case let share as DeckDetailEffectShare:
                // Matches Android: "<title> on Loopky" beats a bare pubky:// manifest URL.
                shareTarget = ShareLinkTarget(deckTitle: share.title, uri: share.uri)
            case is DeckDetailEffectDeleted:
                onDeleted()
            case is DeckDetailEffectNavigateStudyPreview:
                onPreview()
            case let cloned as DeckDetailEffectCloned:
                onOpenClone(cloned.deckId)
            case is DeckDetailEffectShared:
                flash("share_prompt_posted")
            case is DeckDetailEffectShareFailed:
                // Cosmetic: the follow or clone stands whether or not the post went out.
                flash("share_prompt_failed")
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
