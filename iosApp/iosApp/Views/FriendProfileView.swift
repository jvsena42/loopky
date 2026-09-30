import SwiftUI

/// Someone else's profile. Pure layout; `FriendProfileScreen` owns the ViewModel.
struct FriendProfileView: View {
    var state: FriendProfileViewState = FriendProfileViewState()
    var onBack: () -> Void = {}
    var onToggleFollow: () -> Void = {}
    var onCopyPubky: () -> Void = {}
    var onShare: () -> Void = {}
    var onOpenOnPubkyApp: () -> Void = {}
    var onRefresh: () -> Void = {}
    var onOpenDeck: (String, String) -> Void = { _, _ in }
    var onDismissSignInPrompt: () -> Void = {}
    var onSignIn: () -> Void = {}

    @Environment(\.loopkyWidthClass) private var widthClass

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                toolbar
                if state.isLoading {
                    ProgressView().padding(.top, 60)
                } else {
                    hero
                    stats
                    actionRow
                    if let errorMessage = state.errorMessage {
                        Text(errorMessage)
                            .font(.system(size: 13))
                            .foregroundStyle(LoopkyColor.danger)
                    }
                    decksSection
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
            // Reading rather than Wide, as on Android: this is a person, not a catalogue — the bio
            // and the stat band are the screen, and the deck grid answers extra width with extra
            // columns inside the same measure.
            .contentPane()
        }
        .loopkyScreenBackground()
        .navigationBarHidden(true)
        .refreshable { onRefresh() }
        // A guest can read the whole profile; only Follow needs an account. The prompt used to
        // name no reason and offer only Dismiss — a wall with no door in it.
        .signInPrompt(
            reason: state.signInReason,
            onSignIn: { onDismissSignInPrompt(); onSignIn() },
            onDismiss: onDismissSignInPrompt
        )
    }

    private var toolbar: some View {
        HStack {
            Button(action: onBack) {
                Image(systemName: "chevron.left").foregroundStyle(LoopkyColor.accentPrimary)
            }
            .accessibilityLabel(Text("friend_profile_back_content_description"))
            Spacer()
            Button(action: onShare) {
                Image(systemName: "square.and.arrow.up").foregroundStyle(LoopkyColor.accentPrimary)
            }
            .accessibilityLabel(Text("friend_profile_share"))
        }
    }

    private var hero: some View {
        VStack(spacing: 10) {
            PubkyAvatarView(initial: state.initial, avatarUrl: state.avatarUrl, size: 88)

            Text(state.label)
                .font(.system(size: 22, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Button(action: onCopyPubky) {
                Text(state.shortPubky)
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
            }
            .accessibilityLabel(Text("friend_profile_copy_pubky"))
            if let bio = state.bio, !bio.isEmpty {
                Text(bio)
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundSecondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private var stats: some View {
        HStack(spacing: 0) {
            stat(state.deckCount, "profile_stat_decks")
            stat(state.cardCount, "profile_stat_cards")
            if let following = state.followingCount { stat(following, "profile_stat_following") }
            if let followers = state.followerCount { stat(followers, "profile_stat_followers") }
        }
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 18).fill(LoopkyColor.accentPrimarySoft.opacity(0.5)))
    }

    private func stat(_ value: Int, _ label: LocalizedStringKey) -> some View {
        VStack(spacing: 2) {
            Text("\(value)")
                .font(.system(size: 18, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Text(label)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(LoopkyColor.foregroundMuted)
        }
        .frame(maxWidth: .infinity)
    }

    /// Follow, with the way out to pubky.app beside it.
    ///
    /// The mark sits next to the action rather than up in the toolbar: this is the row about
    /// *this person*, and "see them on pubky.app" belongs with "follow them" rather than with
    /// back and share. It stays for your own profile too, where there is no Follow to sit beside.
    private var actionRow: some View {
        HStack(spacing: 10) {
            if !state.isSelf { followButton }
            // The mark, not a generic globe: this leads to *their* pubky.app profile, and the
            // shape is what says so.
            PubkyAppIconButton(action: onOpenOnPubkyApp)
        }
    }

    private var followButton: some View {
        Button(action: onToggleFollow) {
            if state.isProcessingFollow {
                ProgressView().controlSize(.small)
            } else {
                Text(state.isFollowing ? "discover_following" : "discover_follow")
            }
        }
        .buttonStyle(state.isFollowing ? AnyButtonStyle(.loopkyOutline) : AnyButtonStyle(.loopkyFilled))
        .disabled(state.isProcessingFollow)
        .accessibilityIdentifier("friend_profile_follow")
    }

    @ViewBuilder
    private var decksSection: some View {
        if state.decks.isEmpty {
            Text("friend_profile_no_public_decks")
                .font(.system(size: 14))
                .foregroundStyle(LoopkyColor.foregroundMuted)
                .padding(.top, 20)
        } else {
            LazyVGrid(columns: deckGridItems(widthClass, spacing: 12), spacing: 12) {
                ForEach(state.decks) { deck in
                    DeckTileView(
                        title: deck.title,
                        cardCount: deck.cardCount,
                        coverEmoji: deck.coverEmoji,
                        authorLabel: deck.authorLabel,
                        coverImage: deck.coverImage,
                        authorPubky: deck.authorPubky,
                        deckId: deck.id,
                        onTap: { onOpenDeck(deck.id, deck.authorPubky) }
                    )
                }
            }
        }
    }
}

/// Lets one property hold either of two concrete `ButtonStyle`s.
struct AnyButtonStyle: ButtonStyle {
    private let makeBody: (Configuration) -> AnyView

    init<S: ButtonStyle>(_ style: S) {
        makeBody = { configuration in AnyView(style.makeBody(configuration: configuration)) }
    }

    func makeBody(configuration: Configuration) -> some View { makeBody(configuration) }
}
