import SwiftUI
import Shared

/// Every deck on Loopky carrying one tag — where a tag chip leads.
///
/// The chips have rendered since the first deck-detail screen and did nothing: `tagBrowseViewModel`
/// was bound and never called, so a tag was decoration.
struct TagBrowseScreen: View {
    let tag: String
    var onBack: () -> Void = {}
    /// `(deckId, authorPubky)` — the order every other screen uses.
    var onOpenDeck: (String, String) -> Void = { _, _ in }
    var onOpenProfile: (String) -> Void = { _ in }

    @State private var viewModel: TagBrowseViewModel?
    @State private var uiState: TagBrowseUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @Environment(\.loopkyWidthClass) private var widthClass

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            content
        }
        .loopkyScreenBackground()
        .navigationBarHidden(true)
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
            }
            .accessibilityLabel(Text("tag_browse_back"))
            .accessibilityIdentifier("tag_browse_back")

            Text(verbatim: String(
                format: NSLocalizedString("tag_browse_title", comment: ""), tag
            ))
            .font(.system(size: 22, weight: .heavy))
            .foregroundStyle(LoopkyColor.foregroundPrimary)
            .lineLimit(1)

            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .contentPane(PaneWidth.wide)
    }

    @ViewBuilder
    private var content: some View {
        switch uiState {
        case is TagBrowseUiStateLoading:
            centred { ProgressView().tint(LoopkyColor.accentPrimary) }
        case is TagBrowseUiStateEmpty:
            centred { empty }
        case let failed as TagBrowseUiStateError:
            // Not the empty block: "No decks tagged X yet" is a claim about the network, and an
            // indexer that never answered has told us nothing about it (#321).
            centred { retry(message: ErrorCopy.message(for: failed.reason), action: { viewModel?.onRetry() }) }
        case let loaded as TagBrowseUiStateContent:
            grid(content: loaded)
        default:
            centred { ProgressView().tint(LoopkyColor.accentPrimary) }
        }
    }

    private var empty: some View {
        VStack(spacing: 8) {
            Text(verbatim: String(
                format: NSLocalizedString("tag_browse_empty_title", comment: ""), tag
            ))
            .font(.system(size: 18, weight: .heavy))
            .foregroundStyle(LoopkyColor.foregroundPrimary)
            .multilineTextAlignment(.center)

            Text("tag_browse_empty_subtitle")
                .font(.system(size: 14))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 32)
    }

    private func grid(content: TagBrowseUiStateContent) -> some View {
        ScrollView {
            LazyVGrid(columns: deckGridItems(widthClass, spacing: 12), spacing: 12) {
                ForEach(content.decks.compactMap { $0 as? DiscoverDeck }, id: \.id) { deck in
                    DeckTileView(
                        title: deck.title,
                        cardCount: Int(deck.cardCount),
                        coverEmoji: deck.coverEmoji,
                        authorLabel: IdentityData(deck.author).label,
                        coverImage: deck.coverImage,
                        authorPubky: deck.authorPubky,
                        deckId: deck.id,
                        onTap: {
                            viewModel?.onOpenDeck(authorPubky: deck.authorPubky, deckId: deck.id)
                        }
                    )
                }
            }
            .padding(.horizontal, 20)
            // A wall of tiles, which is the one thing that genuinely improves with more room — it
            // answers with more columns, not wider tiles.
            .contentPane(PaneWidth.wide)

            if let failed = content.pageError {
                // A failed page keeps the grid above it and offers the retry in its place.
                retry(message: ErrorCopy.message(for: failed), action: { viewModel?.onRetryPage() })
                    .accessibilityIdentifier("tag_browse_page_error")
            } else if content.hasMore {
                // The sentinel: appearing inside the scroll view already means the reader has
                // reached the end. The ViewModel guards the repeats — see `Content.canLoadMore`.
                HStack {
                    Spacer()
                    if content.isLoadingMore { ProgressView().tint(LoopkyColor.accentPrimary) }
                    Spacer()
                }
                .frame(height: 44)
                .onAppear { viewModel?.onEndReached() }
                .accessibilityIdentifier("load_more_footer")
            }
        }
        .padding(.bottom, 32)
    }

    /// What happened, and a way to ask again.
    private func retry(message: String, action: @escaping () -> Void) -> some View {
        VStack(spacing: 8) {
            Text(verbatim: message)
                .font(.system(size: 14))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .multilineTextAlignment(.center)
            Button("home_retry", action: action)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(LoopkyColor.accentPrimary)
        }
        .padding(.horizontal, 32)
        .accessibilityIdentifier("tag_browse_error")
    }

    private func centred<Body: View>(@ViewBuilder _ body: () -> Body) -> some View {
        VStack {
            Spacer()
            body()
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.tagBrowseViewModel(tag: tag)
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? TagBrowseUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case let open as TagBrowseEffectOpenDeck:
                onOpenDeck(open.deckId, open.authorPubky)
            case let open as TagBrowseEffectOpenProfile:
                onOpenProfile(open.pubky)
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
