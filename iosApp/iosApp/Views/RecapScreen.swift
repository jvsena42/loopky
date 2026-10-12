import SwiftUI
import Shared
import UIKit

/// VM wiring for the yearly recap. The pages themselves are `RecapView`.
struct RecapScreen: View {
    let year: Int

    @State private var viewModel: RecapViewModel?
    @State private var uiState: RecapUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var sharePayload: RecapSharePayload?
    @State private var notice: LocalizedStringKey?

    @Environment(\.openURL) private var openURL

    var body: some View {
        content
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .sheet(item: $sharePayload) { payload in
                ShareSheet(items: payload.items)
            }
            // An alert, not a confirmationDialog: the safe option has to be visible. Its message
            // is snapshotted when presented, which is right here — the preview never changes.
            .alert(
                Text("recap_post_title"),
                isPresented: Binding(
                    get: { postPrompt != nil },
                    set: { if !$0 { viewModel?.onPostDismiss() } }
                ),
                presenting: postPrompt
            ) { _ in
                Button("share_prompt_confirm") { viewModel?.onPostConfirm() }
                Button("share_prompt_dismiss", role: .cancel) { viewModel?.onPostDismiss() }
            } message: { prompt in
                Text(verbatim: prompt.preview)
            }
            .onAppear { attach() }
            .onDisappear { detach() }
    }

    @ViewBuilder
    private var content: some View {
        switch uiState {
        case let loaded as RecapUiStateContent:
            RecapView(
                recap: loaded.recap,
                notice: notice,
                onShare: { target in share(target, recap: loaded.recap) },
                onOpenWeb: { if let url = URL(string: loaded.webUrl) { openURL(url) } }
            )
        case let empty as RecapUiStateEmpty:
            RecapMessagePage(
                emoji: "🌱",
                title: NSLocalizedString("recap_empty_title", comment: ""),
                message: String.localizedStringWithFormat(
                    NSLocalizedString("recap_empty_body", comment: ""), Int(empty.year)
                )
            )
            .recapGround()
        case let failed as RecapUiStateError:
            VStack(spacing: 12) {
                Text(ErrorCopy.message(for: failed.reason))
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                    .multilineTextAlignment(.center)
                Button("home_retry") { viewModel?.onRetry() }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .loopkyScreenBackground()
        default:
            VStack(spacing: 12) {
                ProgressView()
                Text("recap_loading").foregroundStyle(LoopkyColor.foregroundMuted)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .loopkyScreenBackground()
        }
    }

    private var postPrompt: RecapPostPrompt? {
        (uiState as? RecapUiStateContent)?.pubkyPrompt
    }

    /// The caption is this layer's words; the link and the length limit are the ViewModel's.
    private func share(_ target: RecapShareTarget, recap: YearRecap) {
        notice = nil
        viewModel?.onShare(target: target, text: RecapCopy.caption(for: target, recap: recap))
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.recapViewModel(year: Int32(year))
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? RecapUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case let share as RecapEffectShare:
                deliver(share)
            case is RecapEffectPosted:
                notice = "share_prompt_posted"
            case is RecapEffectPostFailed:
                notice = "recap_post_failed"
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

    /// Instagram is the one network with a way in that carries the picture: a story, through the
    /// pasteboard. Everything else goes through the system share sheet with that network's own
    /// image size and caption, because a URL scheme can carry a caption but never an image, and
    /// the picture is the point.
    private func deliver(_ share: RecapEffectShare) {
        let image = RecapShareImage.render(RecapCopy.cardText(share.recap), format: share.target.imageFormat)
        if share.target == RecapShareTarget.instagram, RecapShareImage.openInstagramStory(image) {
            return
        }
        var items: [Any] = []
        if let image { items.append(image) }
        if share.target.takesText { items.append(share.text) }
        guard !items.isEmpty else { return }
        sharePayload = RecapSharePayload(items: items)
    }
}

private struct RecapSharePayload: Identifiable {
    let id = UUID()
    let items: [Any]
}
