import SwiftUI
import Shared

/// The people someone follows, or who follow them.
///
/// `FollowListViewModel` had no `IosDependencies` accessor at all — this is the one screen in the
/// audit whose ViewModel was not merely unused but unreachable.
///
/// Both counts are of *Loopky* accounts, matching what the list shows, so they read smaller than
/// whatever pubky.app reports for the same graph. The footer says so rather than leaving the
/// difference to be discovered.
struct FollowListScreen: View {
    let pubky: String
    let source: FollowSource
    var onOpenProfile: (String) -> Void = { _ in }

    @State private var viewModel: FollowListViewModel?
    @State private var uiState: FollowListUiState?
    @State private var stateSink: FlowEffectSink?

    var body: some View {
        List {
            if let state = uiState, !state.isLoading {
                if state.people.isEmpty {
                    Text(emptyKey(isSelf: state.isSelf))
                        .font(.system(size: 14))
                        .foregroundStyle(LoopkyColor.foregroundMuted)
                } else {
                    Section {
                        ForEach(state.people, id: \.pubky) { person in
                            personRow(IdentityData(person), pubky: person.pubky)
                        }
                    } footer: {
                        Text("follow_list_loopky_only")
                    }
                }
                if let reason = state.errorReason {
                    Text(ErrorCopy.message(for: reason))
                        .font(.system(size: 13))
                        .foregroundStyle(LoopkyColor.danger)
                }
            } else {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("follow_list_loading").foregroundStyle(LoopkyColor.foregroundMuted)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        // A column of names. Unbounded on an iPad each row puts its avatar at one edge and its
        // Follow button at the other, with a hand's width of empty card between them.
        .contentPane()
        .loopkyScreenBackground()
        .navigationTitle(Text(isFollowing ? "follow_list_following_title" : "follow_list_followers_title"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private var isFollowing: Bool { source == FollowSource.following }

    private func emptyKey(isSelf: Bool) -> LocalizedStringKey {
        if isFollowing {
            return isSelf ? "follow_list_empty_following" : "follow_list_empty_following_other"
        }
        return isSelf ? "follow_list_empty_followers" : "follow_list_empty_followers_other"
    }

    private func personRow(_ identity: IdentityData, pubky: String) -> some View {
        Button { onOpenProfile(pubky) } label: {
            HStack(spacing: 12) {
                PubkyAvatarView(
                    initial: identity.initial,
                    avatarUrl: identity.avatarUrl,
                    size: 40
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(identity.label)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(LoopkyColor.foregroundPrimary)
                    Text(identity.shortPubky)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(LoopkyColor.foregroundMuted)
                }
                Spacer()
            }
        }
        .tint(LoopkyColor.foregroundPrimary)
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.followListViewModel(pubky: pubky, source: source)
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? FollowListUiState }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
    }
}
