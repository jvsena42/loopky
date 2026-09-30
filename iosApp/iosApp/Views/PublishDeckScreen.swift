import SwiftUI
import Shared

/// VM-driven wrapper around `PublishDeckView` — the commit half of Paste-to-Import (spec §5–§10).
///
/// This is the screen that actually writes to the homeserver, so nothing here is local state:
/// the title, the tags, the study opt-ins, the publish progress and the undo countdown all come
/// from `PublishDeckViewModel`. The view it replaced navigated with a literal `"preview-deck-id"`.
struct PublishDeckScreen: View {
    var onBack: () -> Void = {}
    var onPublished: (String) -> Void = { _ in }
    /// The session was ended from the error row's "Sign in again" — go collect a new one.
    var onSignedOut: () -> Void = {}

    @State private var viewModel: PublishDeckViewModel?
    @State private var uiState: PublishDeckUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?

    /// Owned locally for the same reason the paste editor's text is: a field bound to a value that
    /// round-trips through Kotlin loses characters as you type.
    @State private var title = ""
    @State private var description = ""
    @State private var didSeedFields = false

    var body: some View {
        PublishDeckView(
            state: viewState,
            title: Binding(
                get: { title },
                set: { title = $0; viewModel?.onTitleChanged(text: $0) }
            ),
            description: Binding(
                get: { description },
                set: { description = $0; viewModel?.onDescriptionChanged(text: $0) }
            ),
            onBack: { viewModel?.onBackClick() },
            onPublish: { viewModel?.onPublishClick() },
            onCancelPublish: { viewModel?.onCancelPublish() },
            onUndo: { viewModel?.onUndoPublish() },
            onDone: { viewModel?.onDonePublish() },
            onAddTag: { viewModel?.onAddTag(tag: $0) },
            onRemoveTag: { viewModel?.onRemoveTag(tag: $0) },
            options: DeckStudyOptions(
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
            },
            onShareConfirm: { viewModel?.onShareConfirm() },
            onShareDismiss: { viewModel?.onShareDismiss() },
            onShareNeverAsk: { viewModel?.onShareNeverAsk() }
        )
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var viewState: PublishViewState {
        guard let state = uiState else { return PublishViewState() }
        return PublishViewState(
            cardCount: Int(state.cardCount),
            discardedCount: Int(state.discardedCount),
            tags: state.tags,
            coverEmoji: state.coverEmoji,
            coverImageUrl: state.coverImageUrl,
            coverPendingBytes: state.coverPendingBytes?.toData(),
            isPublishing: state.isPublishing,
            isCancelling: state.isCancelling,
            publishProgress: state.publishProgress.map { Double(truncating: $0) },
            publishedCardCount: Int(state.publishedCardCount),
            publishedDeckId: state.publishedDeckId,
            undoSecondsRemaining: Int(state.undoSecondsRemaining),
            canPublish: state.canPublish,
            titleError: FormErrorCopy.message(for: state.titleError),
            descriptionError: FormErrorCopy.message(for: state.descriptionError),
            errorMessage: Self.publishErrorText(state.error),
            onSignInAgain: (Self.publishErrorReason(state.error)?.offersSignIn ?? false)
                ? { viewModel?.onSignInAgainClick() }
                : nil,
            sharePromptPreview: state.sharePrompt?.preview,
            isSharing: state.sharePrompt?.isPosting ?? false
        )
    }

    private static func publishErrorText(_ error: PublishError?) -> String? {
        switch error {
        case is PublishErrorNoDraft:
            return NSLocalizedString("publish_error_no_draft", comment: "")
        case let publish as PublishErrorPublish:
            return ErrorCopy.message(for: publish.reason)
        case let cancel as PublishErrorCancel:
            return "\(NSLocalizedString("publish_error_cancel", comment: "")) \(ErrorCopy.message(for: cancel.reason))"
        case let undo as PublishErrorUndo:
            return "\(NSLocalizedString("publish_error_undo", comment: "")) \(ErrorCopy.message(for: undo.reason))"
        default:
            return nil
        }
    }

    /// The classified cause, matched on the concrete classes rather than read off the sealed
    /// interface: a Kotlin sealed interface crosses as an ObjC protocol, and a property read
    /// through the erased value is not something to rely on here.
    private static func publishErrorReason(_ error: PublishError?) -> ErrorReason? {
        switch error {
        case let publish as PublishErrorPublish: return publish.reason
        case let cancel as PublishErrorCancel: return cancel.reason
        case let undo as PublishErrorUndo: return undo.reason
        default: return nil
        }
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.publishDeckViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { value in
            guard let state = value as? PublishDeckUiState else { return }
            uiState = state
            // The draft arrives with a suggested title; seed the fields once so the user sees it,
            // then leave them alone or typing would fight the state coming back.
            //
            // Gated on the draft having loaded, for the same reason as `EditCardScreen`: the
            // ViewModel emits its defaults first, and seeding from those would blank the
            // suggestion the import produced.
            if !didSeedFields && state.cardCount > 0 {
                didSeedFields = true
                title = state.title
                description = state.description_
            }
        }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is PublishDeckEffectNavigateBack: onBack()
            case is PublishDeckEffectNavigateToOnboarding: onSignedOut()
            case let published as PublishDeckEffectPublished: onPublished(published.deckId)
            case is PublishDeckEffectShared, is PublishDeckEffectShareFailed:
                // Deliberately not shown. Announcing is best-effort, and the `Published` that
                // follows it immediately pops this screen — a toast raised here would be torn
                // down with it before anyone read it.
                break
            default: break
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

/// What `PublishDeckView` draws, mapped off `PublishDeckUiState`.
struct PublishViewState {
    var cardCount: Int = 0
    var discardedCount: Int = 0
    var tags: [String] = []
    var coverEmoji: String = ""
    var coverImageUrl: String?
    var coverPendingBytes: Data?
    var isPublishing: Bool = false
    var isCancelling: Bool = false
    var publishProgress: Double?
    var publishedCardCount: Int = 0
    var publishedDeckId: String?
    var undoSecondsRemaining: Int = 0
    var canPublish: Bool = false
    var titleError: String?
    var descriptionError: String?
    var errorMessage: String?
    /// Offered beside `errorMessage` only when signing in again could clear it — see
    /// `ErrorReason.offersSignIn`.
    var onSignInAgain: (() -> Void)?
    var sharePromptPreview: String?
    var isSharing: Bool = false

    /// The deck is on the homeserver and the undo window is open or just closed.
    var isPublished: Bool { publishedDeckId != nil }
}
