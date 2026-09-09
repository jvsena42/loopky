import SwiftUI

/// Your own profile. Pure layout; `ProfileScreen` owns the ViewModel.
///
/// The backup card Android shows above Sign out is deliberately absent: the backup screens have no
/// iOS counterpart yet (#149), so a card offering to back a key up would lead nowhere.
struct ProfileView: View {
    var state: ProfileViewState = ProfileViewState()
    @Binding var editName: String
    @Binding var editBio: String

    var onEditProfile: () -> Void = {}
    var onDismissNameNudge: () -> Void = {}
    var onDismissAvatarNudge: () -> Void = {}
    var onDismissEdit: () -> Void = {}
    var onSaveEdit: () -> Void = {}
    var onEditNameChanged: (String) -> Void = { _ in }
    var onEditBioChanged: (String) -> Void = { _ in }
    var onCopyPubky: () -> Void = {}
    var onShare: () -> Void = {}
    var onOpenOnPubkyApp: () -> Void = {}
    var onSignOut: () -> Void = {}
    var onOpenFollowing: () -> Void = {}
    var onOpenFollowers: () -> Void = {}
    var onOpenSettings: () -> Void = {}
    var onBackUpNow: () -> Void = {}

    @State private var isConfirmingSignOut = false
    @State private var isConfirmingUnbackedSignOut = false
    /// Raised by the camera badge. Loopky cannot set a photo — the profile write it owns is name
    /// and bio — so the badge explains where it is done instead of opening a picker.
    @State private var isExplainingAvatar = false
    /// Enough of the pubky to recognise the account being erased.
    private let pubkyPreviewLength = 12

    @Environment(\.loopkyWidthClass) private var widthClass

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                toolbar
                // Only when there is genuinely nothing to draw. A launch with a persisted session
                // paints the header and the counters from cache and refreshes them underneath,
                // rather than covering a profile that has not changed since last time.
                if state.showLoadingScreen {
                    ProgressView().padding(.top, 60)
                } else if widthClass.isExpanded {
                    // Who you are on the left, what that adds up to on the right. Stacked, this
                    // screen is a column of full-width bands: at iPad width a three-number stat
                    // card puts "Decks" and "Due" a hand's width apart with a strip of empty card
                    // between them.
                    HStack(alignment: .top, spacing: 28) {
                        identityPane.frame(width: profilePaneWidth)
                        detailsPane.frame(maxWidth: .infinity)
                    }
                } else {
                    identityPane
                    detailsPane
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 40)
            // After the background, so the cream still reaches both edges of an iPad.
            .contentPane(widthClass.isExpanded ? PaneWidth.wide : PaneWidth.reading)
        }
        .loopkyScreenBackground()
        .navigationBarHidden(true)
        .sheet(isPresented: Binding(get: { state.showEditSheet }, set: { if !$0 { onDismissEdit() } })) {
            editSheet
        }
        // An `.alert`, not a `confirmationDialog`: a dialog's `.cancel` button is detached from its
        // action list and did not render at all here, leaving the destructive "Sign out" as the only
        // button on screen and tapping outside as the sole, undiscoverable way back. The safe option
        // on a destructive confirm has to be visible.
        .alert(
            Text("profile_sign_out_dialog_title"),
            isPresented: $isConfirmingSignOut
        ) {
            Button("profile_sign_out_confirm", role: .destructive, action: onSignOut)
            Button("profile_sign_out_cancel", role: .cancel) {}
        } message: {
            Text("profile_sign_out_dialog_message")
        }
        // Sign-out lives only here now, so the warning Settings used to raise travels with it:
        // this device holds the only copy of a key nobody has backed up, and signing out deletes
        // it. A sterner prompt than the ordinary confirm, because losing it loses the account.
        .alert(
            Text("settings_signout_unbacked_title"),
            isPresented: $isConfirmingUnbackedSignOut
        ) {
            // Backing up is the safe action and the one almost everyone here wants; the
            // destructive one is deliberately the quiet option beside it.
            Button("settings_signout_unbacked_backup") {
                isConfirmingUnbackedSignOut = false
                onBackUpNow()
            }
            Button("settings_signout_unbacked_confirm", role: .destructive, action: onSignOut)
            Button("profile_sign_out_cancel", role: .cancel) {}
        } message: {
            Text(verbatim: String(
                format: NSLocalizedString("settings_signout_unbacked_body", comment: ""),
                String(state.shortPubky.prefix(pubkyPreviewLength))
            ))
        }
        // Static text on both lines, so the snapshot an `.alert` takes when it is presented costs
        // nothing here — unlike a prompt whose message has to change while the reader types.
        .alert(Text("profile_avatar_dialog_title"), isPresented: $isExplainingAvatar) {
            Button("profile_avatar_open_pubky_app", action: onOpenOnPubkyApp)
            Button("profile_avatar_not_now", role: .cancel) {}
        } message: {
            Text("profile_avatar_hint")
        }
    }

    /// Avatar, name, pubky, bio — and the one action that edits them.
    private var identityPane: some View {
        VStack(spacing: 20) {
            hero
            // Directly under the hero, because that is where the missing name is showing: with
            // none published the hero falls back to the pubky, and this says what to do about it.
            if state.showNameNudge { nameNudge }
            // Never beside the name card — see `ProfileUiState.showAvatarNudge`. Its action opens
            // pubky.app straight away rather than raising the badge's alert: the card is already
            // saying what that alert would.
            if state.showAvatarNudge { avatarNudge }
            Button("profile_edit_profile", action: onEditProfile)
                .buttonStyle(.loopkySoft)
        }
    }

    /// The one-time invitation to name a nameless profile.
    ///
    /// Dismissible, unlike `backupNag`, and the dismissal is remembered on the device: a name is a
    /// courtesy to other people, not a risk to the reader, so someone who would rather stay a
    /// pubky is asked once and never again.
    private var nameNudge: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("profile_name_nudge_title")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Text("profile_name_nudge_body")
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 16) {
                Button("profile_name_nudge_action", action: onEditProfile)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(LoopkyColor.accentSecondary)
                    .accessibilityIdentifier("profile_name_nudge_action")
                Button("profile_name_nudge_dismiss", action: onDismissNameNudge)
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                    .accessibilityIdentifier("profile_name_nudge_dismiss")
            }
            .padding(.top, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(LoopkyColor.accentPrimarySoft))
    }

    /// The same one-time invitation as `nameNudge`, for the other half of an anonymous profile.
    ///
    /// It leaves the app, because Loopky has nowhere to put a picture: the profile write it owns
    /// is name and bio, and the photo is a file record uploaded by pubky.app under the same key.
    private var avatarNudge: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("profile_avatar_nudge_title")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Text("profile_avatar_nudge_body")
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 16) {
                Button("profile_avatar_open_pubky_app", action: onOpenOnPubkyApp)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(LoopkyColor.accentSecondary)
                    .accessibilityIdentifier("profile_avatar_nudge_action")
                Button("profile_avatar_not_now", action: onDismissAvatarNudge)
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundMuted)
                    .accessibilityIdentifier("profile_avatar_nudge_dismiss")
            }
            .padding(.top, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(LoopkyColor.accentPrimarySoft))
    }

    /// What the identity adds up to: counts, the pubky.app link, and the way out.
    private var detailsPane: some View {
        VStack(spacing: 20) {
            stats
            peopleRow
            // Context, not a task, so it sits below the numbers rather than above them.
            PubkyAppProfileCta(action: onOpenOnPubkyApp)
            // Directly above sign-out, because that is the button that can destroy the key it
            // warns about: signing out of an un-backed-up local key ends the account. Under the
            // hero it was a notice; here it is a last chance.
            if state.needsBackup { backupNag }
            signOutButton
        }
    }

    private var toolbar: some View {
        HStack {
            Text("profile_title")
                .font(.system(size: 22, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Spacer()
            Button(action: onShare) {
                Image(systemName: "square.and.arrow.up")
                    .foregroundStyle(LoopkyColor.accentPrimary)
            }
            .accessibilityLabel(Text("profile_share_content_description"))
            Button(action: onOpenSettings) {
                Image(systemName: "gearshape")
                    .foregroundStyle(LoopkyColor.accentPrimary)
            }
            .accessibilityLabel(Text("profile_settings_content_description"))
            .accessibilityIdentifier("profile_settings")
        }
    }

    private var hero: some View {
        VStack(spacing: 10) {
            avatar
            Text(state.label)
                .font(.system(size: 22, weight: .heavy))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Button(action: onCopyPubky) {
                HStack(spacing: 4) {
                    Text(state.shortPubky)
                        .font(.system(size: 13, design: .monospaced))
                    Image(systemName: "doc.on.doc").font(.system(size: 11))
                }
                .foregroundStyle(LoopkyColor.foregroundMuted)
            }
            .accessibilityLabel(Text("profile_copy_pubky"))
            if let bio = state.bio, !bio.isEmpty {
                Text(bio)
                    .font(.system(size: 14))
                    .foregroundStyle(LoopkyColor.foregroundSecondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private var avatar: some View {
        PubkyAvatarView(initial: state.initial, avatarUrl: state.avatarUrl, size: 88)
            // A `Button`, not an `.onTapGesture` on the picture: a bare gesture is invisible to
            // VoiceOver and to `snapshot-ui`, so the control would be reachable by a finger and
            // by nothing else.
            .overlay(alignment: .bottomTrailing) {
                Button { isExplainingAvatar = true } label: {
                    Image(systemName: "camera.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(LoopkyColor.foregroundOnAccent)
                        .frame(width: 30, height: 30)
                        .background(Circle().fill(LoopkyColor.accentPrimary))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("profile_edit_avatar_content_description"))
                .accessibilityIdentifier("profile_edit_avatar")
            }
    }

    private var stats: some View {
        HStack(spacing: 0) {
            stat(state.libraryCountsKnown ? state.deckCount : nil, "profile_stat_decks")
            stat(state.libraryCountsKnown ? state.cardCount : nil, "profile_stat_cards")
            stat(state.dueCountKnown ? state.dueCount : nil, "profile_stat_due", tint: LoopkyColor.accentPrimary)
        }
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 18).fill(LoopkyColor.accentPrimarySoft.opacity(0.5)))
    }

    /// A nil [value] is a count that has not resolved yet — drawn as a dash, never as a zero the
    /// screen would have to correct a round trip later.
    private func stat(_ value: Int?, _ label: LocalizedStringKey, tint: Color = LoopkyColor.foregroundPrimary) -> some View {
        VStack(spacing: 2) {
            Text(value.map { "\($0)" } ?? "—")
                .font(.system(size: 20, weight: .heavy))
                .foregroundStyle(tint)
            Text(label)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(LoopkyColor.foregroundMuted)
        }
        .frame(maxWidth: .infinity)
    }

    /// Both counts stay hidden until they resolve — they land later than the rest of the screen,
    /// and a "0 Followers" that turns into 12 reads as a wrong answer rather than a pending one.
    private var peopleRow: some View {
        HStack(spacing: 10) {
            if let following = state.followingCount {
                peopleButton(following, "profile_stat_following", action: onOpenFollowing)
            }
            if let followers = state.followerCount {
                peopleButton(followers, "profile_stat_followers", action: onOpenFollowers)
            }
        }
    }

    private func peopleButton(_ value: Int, _ label: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text("\(value)").font(.system(size: 15, weight: .heavy))
                Text(label).font(.system(size: 13, weight: .medium))
            }
            .foregroundStyle(LoopkyColor.foregroundSecondary)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Capsule().fill(LoopkyColor.surfaceCard))
            .overlay(Capsule().stroke(LoopkyColor.borderSubtle, lineWidth: 1))
        }
    }

    /// Shown while Loopky holds the only copy of this account's key.
    ///
    /// It goes away as soon as any one method is done — which is why Settings keeps a permanent
    /// row into the same flow: backup methods accumulate, and this card is a prompt, not the door.
    private var backupNag: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("backup_nag_title")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(LoopkyColor.foregroundPrimary)
            Text("backup_nag_body")
                .font(.system(size: 13))
                .foregroundStyle(LoopkyColor.foregroundSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Button("backup_nag_action", action: onBackUpNow)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(LoopkyColor.accentSecondary)
                .padding(.top, 2)
                .accessibilityIdentifier("profile_back_up_now")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(LoopkyColor.danger.opacity(0.12)))
    }

    /// A full-width tonal button in the danger tint, as on Android — not a text link.
    ///
    /// It is the one control on this screen that can end an account, and a quiet line of red text
    /// under a card read as a footnote next to the backup warning it follows.
    private var signOutButton: some View {
        Button {
            // Which prompt depends on what is at stake: an unbacked local key makes this
            // irreversible, and the ordinary confirm does not say so.
            if state.needsBackup {
                isConfirmingUnbackedSignOut = true
            } else {
                isConfirmingSignOut = true
            }
        } label: {
            Label("profile_sign_out", systemImage: "rectangle.portrait.and.arrow.right")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(LoopkyColor.danger)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(RoundedRectangle(cornerRadius: 20).fill(LoopkyColor.dangerSoft))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("profile_signout")
    }

    private var editSheet: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("profile_edit_name_placeholder", text: Binding(
                        get: { editName },
                        set: { editName = $0; onEditNameChanged($0) }
                    ))
                } header: {
                    Text("profile_edit_name_label")
                }
                Section {
                    TextField("profile_edit_bio_placeholder", text: Binding(
                        get: { editBio },
                        set: { editBio = $0; onEditBioChanged($0) }
                    ), axis: .vertical)
                    .lineLimit(3...6)
                } header: {
                    Text("profile_edit_bio_label")
                }
            }
            .navigationTitle(Text("profile_edit_profile_sheet_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("profile_dismiss", action: onDismissEdit)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if state.isSaving {
                        ProgressView().controlSize(.small)
                    } else {
                        Button("profile_save", action: onSaveEdit)
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

/// Wide enough for a display name and a truncated pubky to sit on one line each.
private let profilePaneWidth: CGFloat = 340
