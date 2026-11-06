import SwiftUI
import Shared

/// Someone else's profile: who they are, what they wrote, what they follow, and a Follow button.
///
/// `friendProfileViewModel(pubky:)` had existed unused, so tapping a person in Discover or Search
/// dismissed the sheet and went nowhere.
struct FriendProfileScreen: View {
    let pubky: String
    var onBack: () -> Void = {}
    var onOpenDeck: (String, String) -> Void = { _, _ in }
    var onOpenAuthor: (String) -> Void = { _ in }
    var onSignIn: () -> Void = {}

    @Environment(\.openURL) private var openURL

    @State private var viewModel: FriendProfileViewModel?
    @State private var uiState: FriendProfileUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var shareTarget: ShareLinkTarget?
    @State private var reportBlockFailed = false

    var body: some View {
        FriendProfileView(
            state: viewState,
            onBack: onBack,
            onToggleFollow: { viewModel?.onToggleFollow() },
            onBlockClick: { viewModel?.onBlockClick() },
            onBlockConfirmation: { viewModel?.onBlockConfirmation(confirmed: $0) },
            onReportClick: { viewModel?.onReportClick() },
            onShowAnyway: { viewModel?.onShowAnyway() },
            onCopyPubky: { viewModel?.onCopyPubky() },
            onShare: { viewModel?.onShareClick() },
            onOpenOnPubkyApp: { viewModel?.onOpenOnPubkyApp() },
            onRefresh: { viewModel?.onRefresh() },
            onOpenDeck: { deckId, author in viewModel?.onOpenDeck(authorPubky: author, deckId: deckId) },
            onDismissSignInPrompt: { viewModel?.onDismissSignInPrompt() },
            onSignIn: onSignIn
        )
        .sheet(item: $shareTarget) { ShareLinkSheet(target: $0) }
        .sheet(isPresented: Binding(
            get: { uiState?.isReporting == true },
            set: { if !$0 { viewModel?.onReportDismiss() } }
        )) {
            ReportSheet(
                accountPubky: pubky,
                deck: nil,
                onSent: { viewModel?.onReportSent(blocked: $0) },
                onDismiss: { viewModel?.onReportDismiss() }
            )
        }
        .alert(Text("report_done_block_failed"), isPresented: $reportBlockFailed) {
            Button("share_sheet_close", role: .cancel) {}
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var viewState: FriendProfileViewState {
        guard let state = uiState else { return FriendProfileViewState() }
        let identity = IdentityData(state.identity)
        return FriendProfileViewState(
            isLoading: state.isLoading,
            label: identity.label,
            shortPubky: identity.shortPubky,
            initial: identity.initial,
            avatarUrl: identity.avatarUrl,
            bio: state.identity.bio,
            isFollowing: state.isFollowing,
            isProcessingFollow: state.isProcessingFollow,
            isSelf: state.isSelf,
            isBlocked: state.isBlocked,
            isConfirmingBlock: state.isConfirmingBlock,
            isProcessingBlock: state.isProcessingBlock,
            isHiddenByReport: state.isHiddenByReport,
            isHiddenByModerators: state.hiddenReason == .flaggedbymoderators,
            canReport: state.canReport,
            canBlock: state.canBlock,
            isSignedIn: state.isSignedIn,
            signInReason: state.signInPrompt,
            deckCount: Int(state.deckCount),
            cardCount: Int(state.cardCount),
            followingCount: state.followingCount.map { Int(truncating: $0) },
            followerCount: state.followerCount.map { Int(truncating: $0) },
            errorMessage: state.errorReason.map { ErrorCopy.message(for: $0) },
            decks: state.decks.map {
                FriendDeckData(
                    id: $0.id,
                    coverImage: $0.coverImage,
                    authorPubky: $0.authorPubky,
                    title: $0.title,
                    cardCount: Int($0.cardCount),
                    coverEmoji: $0.coverEmoji,
                    authorLabel: IdentityData($0.author).label
                )
            }
        )
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.friendProfileViewModel(pubky: pubky)
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? FriendProfileUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case let open as FriendProfileEffectOpenDeck:
                onOpenDeck(open.deckId, open.authorPubky)
            case let author as FriendProfileEffectOpenProfile:
                onOpenAuthor(author.pubky)
            case let share as FriendProfileEffectShareProfile:
                shareTarget = ShareLinkTarget(profile: share.identity, uri: share.uri)
            case let copy as FriendProfileEffectCopyToClipboard:
                UIPasteboard.general.string = copy.text
            case let url as FriendProfileEffectOpenUrl:
                if let link = URL(string: url.url) { openURL(link) }
            case is FriendProfileEffectBlocked:
                // The profile is not to be seen once blocked, so it closes (#577).
                onBack()
            case let reported as FriendProfileEffectReported:
                // A report that blocked closes the profile, as a block does; one whose block failed
                // stays, and the view says so.
                if reported.blocked { onBack() } else { reportBlockFailed = true }
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

struct FriendDeckData: Identifiable {
    let id: String
    var coverImage: MediaRef.Image?
    let authorPubky: String
    let title: String
    let cardCount: Int
    let coverEmoji: String
    let authorLabel: String
}

struct FriendProfileViewState {
    var isLoading: Bool = true
    var label: String = ""
    var shortPubky: String = ""
    var initial: String = "?"
    var avatarUrl: String?
    var bio: String?
    var isFollowing: Bool = false
    var isProcessingFollow: Bool = false
    var isSelf: Bool = false
    /// The reader blocked this person: nothing of theirs is drawn, and there is no Unblock here (#577).
    var isBlocked: Bool = false
    var isConfirmingBlock: Bool = false
    var isProcessingBlock: Bool = false
    /// Someone the reader follows reported this account: nothing is drawn until Show anyway.
    var isHiddenByReport: Bool = false
    var isHiddenByModerators: Bool = false
    /// Offered to guests too, who are asked to sign in.
    var canReport: Bool = false
    var canBlock: Bool = false
    var isSignedIn: Bool = true
    /// Which action ran into the wall, so the prompt can say what signing in unlocks.
    var signInReason: SignInReason?
    var deckCount: Int = 0
    var cardCount: Int = 0
    var followingCount: Int?
    var followerCount: Int?
    var errorMessage: String?
    var decks: [FriendDeckData] = []
}
