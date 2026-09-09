import SwiftUI
import Shared

/// VM-driven wrapper around `ProfileView`.
///
/// The Profile tab was a 23-line "coming soon" placard, which also meant iOS had **no way to sign
/// out** — the plumbing existed all the way from `ProfileEffect.NavigateToOnboarding` down to
/// `RootView`, with nothing able to start it.
struct ProfileScreen: View {
    var onSignedOut: () -> Void = {}
    /// `(pubky, source)` — the screen supplies its own pubky so callers need not know it.
    var onOpenFollows: (String, FollowSource) -> Void = { _, _ in }
    var onOpenSettings: () -> Void = {}
    var onBackUpNow: () -> Void = {}

    @Environment(\.openURL) private var openURL

    @State private var viewModel: ProfileViewModel?
    @State private var uiState: ProfileUiState?
    @State private var stateSink: FlowEffectSink?
    @State private var effectSink: FlowEffectSink?
    @State private var shareItem: ShareItem?
    @State private var toast: String?

    /// Edit-sheet fields are owned here while typing, like every other text input in the app.
    @State private var editName = ""
    @State private var editBio = ""

    var body: some View {
        ProfileView(
            state: viewState,
            editName: $editName,
            editBio: $editBio,
            onEditProfile: {
                // Seeded from the identity on screen, not from `uiState.editName`: that field is
                // filled by the `onEditProfileClick()` below, so reading it here hands back the
                // *previous* open's values — empty on the first. Someone who opened the sheet and
                // saved published a blank name over their own.
                editName = uiState?.identity?.displayName ?? ""
                editBio = uiState?.identity?.bio ?? ""
                viewModel?.onEditProfileClick()
            },
            onDismissNameNudge: { viewModel?.onDismissNameNudge() },
            onDismissAvatarNudge: { viewModel?.onDismissAvatarNudge() },
            onDismissEdit: { viewModel?.onDismissEditSheet() },
            onSaveEdit: { viewModel?.onSaveClick() },
            onEditNameChanged: { viewModel?.onEditNameChanged(text: $0) },
            onEditBioChanged: { viewModel?.onEditBioChanged(text: $0) },
            onCopyPubky: { viewModel?.onCopyPubky() },
            onShare: { viewModel?.onShareClick() },
            onOpenOnPubkyApp: { viewModel?.onOpenOnPubkyApp() },
            onSignOut: { viewModel?.onSignOutClick() },
            onOpenFollowing: { openFollows(FollowSource.following) },
            onOpenFollowers: { openFollows(FollowSource.followers) },
            onOpenSettings: onOpenSettings,
            onBackUpNow: onBackUpNow
        )
        .sheet(item: $shareItem) { ShareSheet(items: [$0.text]) }
        .overlay(alignment: .bottom) {
            if let toast {
                Text(toast)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(LoopkyColor.foregroundOnAccent)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(LoopkyColor.foregroundPrimary.opacity(0.9)))
                    .padding(.bottom, 90)
                    .transition(.opacity)
            }
        }
        .onAppear { attach() }
        .onDisappear { detach() }
    }

    private func openFollows(_ source: FollowSource) {
        guard let pubky = uiState?.identity?.pubky else { return }
        onOpenFollows(pubky, source)
    }

    private var viewState: ProfileViewState {
        guard let state = uiState else { return ProfileViewState() }
        let identity = state.identity.map { IdentityData($0) }
        return ProfileViewState(
            showLoadingScreen: state.showLoadingScreen,
            label: identity?.label ?? "",
            shortPubky: identity?.shortPubky ?? "",
            initial: identity?.initial ?? "?",
            avatarUrl: identity?.avatarUrl,
            needsBackup: state.needsBackup,
            showNameNudge: state.showNameNudge,
            showAvatarNudge: state.showAvatarNudge,
            bio: state.identity?.bio,
            deckCount: Int(state.deckCount),
            cardCount: Int(state.cardCount),
            dueCount: Int(state.dueCount),
            libraryCountsKnown: state.libraryCountsKnown,
            dueCountKnown: state.dueCountKnown,
            followingCount: state.followingCount.map { Int(truncating: $0) },
            followerCount: state.followerCount.map { Int(truncating: $0) },
            showEditSheet: state.showEditSheet,
            isSaving: state.isSaving
        )
    }

    private func attach() {
        guard viewModel == nil else { return }
        let vm = IosDependencies.shared.profileViewModel()
        viewModel = vm
        stateSink = FlowEffectSink(vm.state) { uiState = $0 as? ProfileUiState }
        effectSink = FlowEffectSink(vm.effects) { effect in
            switch effect {
            case is ProfileEffectNavigateToOnboarding:
                onSignedOut()
            case let share as ProfileEffectShareProfile:
                shareItem = ShareItem(text: "\(IdentityData(share.identity).label) on Loopky\n\(share.uri)")
            case let copy as ProfileEffectCopyToClipboard:
                UIPasteboard.general.string = copy.text
                flash(NSLocalizedString("profile_copied", comment: ""))
            case let open as ProfileEffectOpenUrl:
                if let url = URL(string: open.url) { openURL(url) }
            case let error as ProfileEffectShowError:
                flash(error.message)
            default:
                break
            }
        }
    }

    private func flash(_ message: String) {
        withAnimation { toast = message }
        Task {
            try? await Task.sleep(for: .seconds(2))
            withAnimation { toast = nil }
        }
    }

    private func detach() {
        viewModel?.release()
        viewModel = nil
        stateSink = nil
        effectSink = nil
    }
}

struct ProfileViewState {
    /// Nothing cached to draw yet, so the screen is a spinner. See `ProfileUiState.showLoadingScreen`:
    /// an ordinary launch paints from the persisted session instead and refreshes underneath.
    var showLoadingScreen: Bool = true
    var label: String = ""
    var shortPubky: String = ""
    var initial: String = "?"
    var avatarUrl: String?
    /// Loopky holds the only copy of this account's key and no method has been completed yet.
    /// Goes away as soon as one has — Settings keeps the permanent door.
    var needsBackup: Bool = false
    /// This account has published no display name and has not waved the prompt away.
    var showNameNudge: Bool = false
    /// This account has published no photo, has not waved the prompt away, and is not already
    /// being asked for a name — the two cards never stack.
    var showAvatarNudge: Bool = false
    var bio: String?
    var deckCount: Int = 0
    var cardCount: Int = 0
    var dueCount: Int = 0
    /// False while `deckCount`/`cardCount` are placeholders — the stat card draws a dash.
    var libraryCountsKnown: Bool = true
    /// The same for `dueCount`, which resolves later: review state is not cached across launches.
    var dueCountKnown: Bool = true
    var followingCount: Int?
    var followerCount: Int?
    var showEditSheet: Bool = false
    var isSaving: Bool = false
}
