import SwiftUI
import Shared

/// VM-driven wrapper around the presentational `DeckEditorView`.
/// `deckId == nil` creates a new deck.
struct DeckEditorScreen: View {
    var deckId: String?
    var onBack: () -> Void = {}
    var onEditCard: (String, String) -> Void = { _, _ in }
    /// A card that does not exist yet — the editor mints its id and appends it on save.
    var onNewCard: (String) -> Void = { _ in }
    var onSaved: (String) -> Void = { _ in }
    /// The session was ended from the error row's "Sign in again" — go collect a new one.
    var onSignedOut: () -> Void = {}

    @State private var viewModel: DeckEditorViewModel?
    @State private var uiState: DeckEditorUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    var body: some View {
        DeckEditorView(
            isNew: uiState?.isNew ?? (deckId == nil),
            coverEmoji: uiState?.coverEmoji ?? "",
            coverImageUrl: uiState?.coverImageUrl,
            coverImageBase64: uiState?.coverImageBase64,
            coverPendingBytes: uiState?.coverPendingBytes?.toData(),
            title: uiState?.title ?? "",
            description: uiState?.description_ ?? "",
            tags: uiState?.tags ?? [],
            cards: (uiState?.cards ?? []).map {
                EditorCardData(
                    id: $0.id,
                    front: $0.frontText,
                    back: $0.backText,
                    hasImage: $0.hasImage,
                    hasAudio: $0.hasAudio
                )
            },
            totalCards: Int(uiState?.totalCards ?? 0),
            isLoadingCards: uiState?.isLoadingCards ?? false,
            hasMoreCards: uiState?.hasMoreCards ?? false,
            isSaving: uiState?.isSaving ?? false,
            titleError: FormErrorCopy.message(for: uiState?.titleError),
            descriptionError: FormErrorCopy.message(for: uiState?.descriptionError),
            error: DeckEditorErrorCopy.message(for: uiState?.error),
            onSignInAgain: (uiState?.error?.reason.offersSignIn ?? false)
                ? { viewModel?.onSignInAgainClick() }
                : nil,
            onTitleChanged: { viewModel?.onTitleChanged(text: $0) },
            onDescriptionChanged: { viewModel?.onDescriptionChanged(text: $0) },
            onAddTag: { viewModel?.onAddTag(tag: $0) },
            onRemoveTag: { viewModel?.onRemoveTag(tag: $0) },
            onAddCard: { viewModel?.onAddCard() },
            onCardTap: { viewModel?.onCardClick(cardId: $0) },
            onMoveCard: { from, to in viewModel?.onMoveCard(from: Int32(from), to: Int32(to)) },
            onLoadMoreCards: { viewModel?.onLoadMoreCards() },
            onClose: { viewModel?.onCloseClick() },
            onSave: { viewModel?.onSaveClick() },
            studyOptions: DeckStudyOptions(
                listenEnabled: uiState?.listenEnabled ?? false,
                speakEnabled: uiState?.speakEnabled ?? false,
                typeEnabled: uiState?.typeEnabled ?? false,
                reverseEnabled: uiState?.reverseEnabled ?? false,
                frontLang: uiState?.frontLang,
                backLang: uiState?.backLang,
                languagesRequired: uiState?.speechLanguagesMissing ?? false,
                onToggleListen: { viewModel?.onToggleListen() },
                onToggleSpeak: { viewModel?.onToggleSpeak() },
                onToggleType: { viewModel?.onToggleType() },
                onToggleReverse: { viewModel?.onToggleReverse() },
                onFrontLangSelected: { viewModel?.onFrontLangSelected(tag: $0) },
                onBackLangSelected: { viewModel?.onBackLangSelected(tag: $0) }
            ),
            onCoverSelected: { selection in
                switch selection {
                case .web(let url):
                    viewModel?.onCoverWebSelected(url: url)
                case .gallery(let bytes, let mime):
                    viewModel?.onCoverGallerySelected(bytes: bytes.toKotlinByteArray(), mime: mime)
                }
            }
        )
        // Without this the editor is *stuck* after creating a deck, not merely quiet: a create
        // with announcing on parks the flow on the prompt and withholds `SaveSuccess` until it is
        // answered, so the deck is written and the screen never leaves.
        .sharePrompt(
            prompt: uiState?.sharePrompt,
            onConfirm: { viewModel?.onShareConfirm() },
            onDismiss: { viewModel?.onShareDismiss() },
            onNeverAsk: { viewModel?.onShareNeverAsk() }
        )
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.deckEditorViewModel(deckId: deckId)
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? DeckEditorUiState }
        guard effectSink == nil else { return }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is DeckEditorEffectNavigateBack:
                onBack()
            case is DeckEditorEffectNavigateToOnboarding:
                onSignedOut()
            case let editCard as DeckEditorEffectNavigateEditCard:
                onEditCard(editCard.deckId, editCard.cardId)
            case let newCard as DeckEditorEffectNavigateNewCard:
                onNewCard(newCard.deckId)
            case let saved as DeckEditorEffectSaveSuccess:
                onSaved(saved.deckId)
            case is DeckEditorEffectShared, is DeckEditorEffectShareFailed:
                // Deliberately not shown. Announcing is best-effort, and the `SaveSuccess` that
                // follows it immediately pops this screen — a toast raised here would be torn
                // down with the editor before anyone read it.
                break
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
