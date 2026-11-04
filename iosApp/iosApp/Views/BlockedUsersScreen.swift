import SwiftUI
import Shared

/// Settings → Blocked accounts: everyone the signed-in user has blocked, each with an Unblock —
/// the only place one is offered (#577).
///
/// The list is pubky.app's mute list, so it also holds accounts muted there; the caption says so,
/// or a name the reader never blocked in Loopky reads as a bug. A row opens nothing: a blocked
/// account's profile is not shown.
struct BlockedUsersScreen: View {
    @State private var viewModel: BlockedUsersViewModel?
    @State private var uiState: BlockedUsersUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var isShowingUnblockError = false

    var body: some View {
        List {
            Section {
                if let state = uiState, !state.isLoading {
                    if let reason = state.errorReason {
                        Text(ErrorCopy.message(for: reason))
                            .font(.system(size: 13))
                            .foregroundStyle(LoopkyColor.danger)
                        Button("decks_retry") { viewModel?.onRetry() }
                    } else if state.people.isEmpty {
                        Text("blocked_users_empty")
                            .font(.system(size: 14))
                            .foregroundStyle(LoopkyColor.foregroundMuted)
                            .accessibilityIdentifier("blocked_users_empty")
                    } else {
                        ForEach(Array(state.people.enumerated()), id: \.element.pubky) { index, person in
                            row(
                                IdentityData(person),
                                pubky: person.pubky,
                                isUnblocking: state.unblocking.contains(person.pubky),
                                index: index
                            )
                        }
                    }
                } else {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("blocked_users_loading").foregroundStyle(LoopkyColor.foregroundMuted)
                    }
                }
            } footer: {
                Text("blocked_users_caption")
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .contentPane()
        .loopkyScreenBackground()
        .navigationTitle(Text("settings_blocked_users"))
        .navigationBarTitleDisplayMode(.inline)
        .alert(Text("unblock_failed"), isPresented: $isShowingUnblockError) {
            Button("share_sheet_close", role: .cancel) {}
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private func row(_ identity: IdentityData, pubky: String, isUnblocking: Bool, index: Int) -> some View {
        HStack(spacing: 12) {
            PubkyAvatarView(initial: identity.initial, avatarUrl: identity.avatarUrl, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(identity.label)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundPrimary)
                Text(identity.shortPubky)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }
            Spacer()
            Button("unblock_action") { viewModel?.onUnblock(pubky: pubky) }
                .buttonStyle(.borderless)
                .tint(LoopkyColor.accentPrimary)
                .disabled(isUnblocking)
                .accessibilityIdentifier("blocked_unblock_\(index)")
        }
        .accessibilityIdentifier("blocked_row_\(index)")
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.blockedUsersViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? BlockedUsersUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            if effect is BlockedUsersEffectShowError { isShowingUnblockError = true }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}
