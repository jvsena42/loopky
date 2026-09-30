import SwiftUI
import Shared

enum DeckRoute: Hashable, Identifiable {
    case detail(String, String?)
    case editor(String)
    case editorNew
    case editCard(String, String)
    case importPaste
    case importBulk(URL?)
    case importTriage
    case importTriageEditCard(Int)
    case importPublish
    /// `nil` means everything due across the library, which is what Home asks for.
    case study(String?)
    /// Flip through a deck nobody has kept, grading nothing — `(deckId, authorPubky)`.
    case studyPreview(String, String?)
    case settings
    case friendProfile(String)
    case followList(String, FollowSource)
    case tagBrowse(String)

    var id: String {
        switch self {
        case .detail(let id, let author): return "detail-\(id)-\(author ?? "self")"
        case .editor(let id): return "editor-\(id)"
        case .editorNew: return "editor-new"
        case .editCard(let deckId, let cardId): return "edit-\(deckId)-\(cardId)"
        case .importPaste: return "import-paste"
        case .importBulk(let url): return "import-bulk-\(url?.lastPathComponent ?? "picker")"
        case .importTriage: return "import-triage"
        case .importTriageEditCard(let rowIndex): return "import-triage-edit-\(rowIndex)"
        case .importPublish: return "import-publish"
        case .study(let id): return "study-\(id ?? "all")"
        case .studyPreview(let id, let author): return "preview-\(id)-\(author ?? "self")"
        case .settings: return "settings"
        case .friendProfile(let pubky): return "friend-\(pubky)"
        case .followList(let pubky, let source): return "follows-\(pubky)-\(source.name)"
        case .tagBrowse(let tag): return "tag-\(tag)"
        }
    }
}

/// Temporary root. Holds the navigation stack and owns the "am I signed in?" state.
///
/// Once the shared `OnboardingViewModel` is accessible through SKIE, this view should
/// observe `vm.state` and drive navigation from emitted effects rather than the local
/// `@State` used here.
struct RootView: View {
    @Environment(\.openURL) private var openURL
    @State private var isSignedIn: Bool = false
    /// Browsing without an account. Discover, deck detail, profiles and a deck's cards are all
    /// public records, so a visitor is shown the app before being asked for the most expensive
    /// thing on the sign-in screen. Every write past that point raises a prompt from the action.
    @State private var isGuest: Bool = false
    /// Whether this launch has already been sent somewhere. False only on the cold start that
    /// finds no session — a later arrival at onboarding follows an explicit "sign me out", and
    /// answering that with the browsing shell would leave no way back to an account.
    @State private var launchRouted: Bool = false
    @State private var pubky: String?
    /// A **path**, not a single destination.
    ///
    /// `navigationDestination(item:)` drives one destination at a time, and assigning a new value
    /// while one is on screen leaves it in place: deck detail → study, → preview and → clone all
    /// did nothing at all, silently. A path also gives back its real meaning — one step, rather
    /// than clearing the whole stack.
    @State private var deckPath: [DeckRoute] = []
    /// The signed-out flows. A path, not a single destination: signup and restore go
    /// several pushes deep.
    @State private var identityPath: [IdentityRoute] = []
    /// Backup, reached from Settings or the Profile nag. A sheet, not a push — see `BackupFlowView`.
    @State private var isBackingUp = false

    var body: some View {
        // Two stacks, not one. The signed-in side pushes a single destination at a time
        // (`navigationDestination(item:)`); the signed-out side needs a **path**, because signup
        // and restore go several pushes deep. A stack cannot do both — a pathless stack ignores
        // appends to `identityPath`, which is what left "Create account" doing nothing.
        Group {
            if isSignedIn || isGuest {
                signedIn
            } else {
                signedOut
            }
        }
        .sheet(isPresented: $isBackingUp) {
            BackupFlowView(onClose: { isBackingUp = false })
        }
        .onOpenURL { url in
            // Two kinds of URL arrive here. A deck file opened from Files, Mail or a chat app —
            // Android's equivalent is ACTION_VIEW / ACTION_SEND — goes straight to the import
            // screen with the file already in hand. Anything else is the auth callback, which
            // completes over the relay poll and only needs to bring Loopky back to the front.
            if url.isFileURL {
                deckPath.append(.importBulk(url))
            } else {
                print("[Loopky] received deeplink: \(url.absoluteString)")
            }
        }
    }

    private var signedIn: some View {
        NavigationStack(path: $deckPath) {
            MainView(
                onDeckTap: { deckId, author in deckPath.append(.detail(deckId, author)) },
                onImportTap: { deckPath.append(.importPaste) },
                onImportFileTap: { deckPath.append(.importBulk(nil)) },
                onCreateDeckTap: { deckPath.append(.editorNew) },
                onSignedOut: { signOut() },
                onStartStudy: { deckPath.append(.study(nil)) },
                onOpenSettings: { deckPath.append(.settings) },
                onOpenProfile: { deckPath.append(.friendProfile($0)) },
                onOpenFollows: { pubky, source in deckPath.append(.followList(pubky, source)) },
                onBackUpNow: { isBackingUp = true },
                isGuest: isGuest,
                onSignIn: { leaveGuestShell() }
            )
            .navigationDestination(for: DeckRoute.self) { route in
                deckDestination(route)
            }
        }
    }

    @ViewBuilder
    private func deckDestination(_ route: DeckRoute) -> some View {
        switch route {
        case .detail(let deckId, let author):
            DeckDetailScreen(
                deckId: deckId,
                authorPubky: author,
                onBack: { popDeck() },
                onEditDeck: { id in deckPath.append(.editor(id)) },
                onStudy: { deckPath.append(.study(deckId)) },
                onDeleted: { popDeck() },
                onOpenTag: { deckPath.append(.tagBrowse($0)) },
                onSignIn: { leaveGuestShell() },
                // The clone is what the user now owns, so it replaces the source rather than
                // stacking a near-identical screen on top of it.
                onOpenClone: { replaceDeck(.detail($0, nil)) },
                onPreview: { deckPath.append(.studyPreview(deckId, author)) }
            )
        case .editor(let deckId):
            DeckEditorScreen(
                deckId: deckId,
                onBack: { popDeck() },
                onEditCard: { d, c in deckPath.append(.editCard(d, c)) },
                // Blank card id: the card editor mints one and appends on save.
                onNewCard: { d in deckPath.append(.editCard(d, "")) },
                onSaved: { id in deckPath = [.detail(id, nil)] },
                onSignedOut: { signOut() }
            )
        case .editorNew:
            DeckEditorScreen(
                deckId: nil,
                onBack: { popDeck() },
                onEditCard: { d, c in deckPath.append(.editCard(d, c)) },
                onNewCard: { d in deckPath.append(.editCard(d, "")) },
                onSaved: { id in deckPath = [.detail(id, nil)] },
                onSignedOut: { signOut() }
            )
        case .editCard(let deckId, let cardId):
            EditCardScreen(deckId: deckId, cardId: cardId, onBack: { popDeck() })
        case .importPaste:
            PasteScreen(
                onCancel: { popDeck() },
                onNext: { deckPath.append(.importTriage) }
            )
        case .importBulk(let incoming):
            BulkImportScreen(
                incomingFile: incoming,
                onCancel: { popDeck() },
                onContinue: { deckPath.append(.importTriage) }
            )
        case .importTriage:
            TriageScreen(
                onBack: { popDeck() },
                onPublish: { deckPath.append(.importPublish) },
                onEditCard: { rowIndex in deckPath.append(.importTriageEditCard(rowIndex)) }
            )
        case .importTriageEditCard(let rowIndex):
            // Triage re-reads the draft in its `onAppear`, so popping back is all the editor has
            // to do for its change to show.
            TriageEditCardScreen(rowIndex: rowIndex, onBack: { popDeck() })
        case .friendProfile(let pubky):
            FriendProfileScreen(
                pubky: pubky,
                onBack: { popDeck() },
                onOpenDeck: { deckId, author in deckPath.append(.detail(deckId, author)) },
                onOpenAuthor: { deckPath.append(.friendProfile($0)) },
                onSignIn: { leaveGuestShell() }
            )
        case .studyPreview(let deckId, let author):
            StudySessionScreen(
                deckId: deckId,
                isPreview: true,
                previewAuthorPubky: author,
                onSignIn: { leaveGuestShell() },
                onClose: { popDeck() }
            )
        case .tagBrowse(let tag):
            TagBrowseScreen(
                tag: tag,
                onBack: { popDeck() },
                onOpenDeck: { id, author in deckPath.append(.detail(id, author)) },
                onOpenProfile: { deckPath.append(.friendProfile($0)) }
            )
        case .followList(let pubky, let source):
            FollowListScreen(
                pubky: pubky,
                source: source,
                onOpenProfile: { deckPath.append(.friendProfile($0)) }
            )
        case .settings:
            SettingsScreen(
                onSignedOut: { signOut() },
                onBackUpNow: { isBackingUp = true }
            )
        case .study(let deckId):
            StudySessionScreen(
                deckId: deckId,
                onClose: {
                    // Back to the deck it came from, or to the tabs when studying
                    // everything due.
                    pushOrPopTo(deckId.map { .detail($0, nil) })
                }
            )
        case .importPublish:
            PublishDeckScreen(
                onBack: { popDeck() },
                // The published deck replaces the whole import flow: there is nothing behind it
                // worth walking back into.
                onPublished: { deckId in deckPath = [.detail(deckId, nil)] },
                onSignedOut: { signOut() }
            )
        }
    }

    private var signedOut: some View {
        NavigationStack(path: $identityPath) {
            OnboardingScreen(
                onSignedIn: { signIn() },
                onCreatePubky: { identityPath.append(.signupStart(adoptHeldKey: false)) },
                onRestore: { identityPath.append(.restoreStart) },
                onUnregistered: { pubky in
                    // Ring holds this key, so Loopky cannot register it.
                    identityPath.append(.unregisteredKey(pubky: pubky, loopkyHoldsKey: false))
                },
                // Only on the launch that arrived here by itself. After a sign-out this stays
                // false, so the visitor lands on the sign-in screen they asked for.
                onExplore: {
                    launchRouted = true
                    isGuest = true
                },
                autoExplore: !launchRouted
            )
            .navigationDestination(for: IdentityRoute.self) { route in
                identityDestination(route)
            }
        }
    }

    @ViewBuilder
    private func identityDestination(_ route: IdentityRoute) -> some View {
        switch route {
        case .restoreStart:
            RestoreStartScreen(
                onBack: pop,
                onRestoreWithPhrase: { identityPath.append(.restorePhrase) },
                onRestoreWithFile: { identityPath.append(.restoreFile) }
            )
        case .restorePhrase:
            RestorePhraseScreen(
                onBack: pop,
                onRestored: signIn,
                onUnregistered: { pubky in
                    // Loopky holds this one — it was just derived from the phrase.
                    identityPath.append(.unregisteredKey(pubky: pubky, loopkyHoldsKey: true))
                }
            )
        case .restoreFile:
            RestoreFileScreen(
                onBack: pop,
                onRestored: signIn,
                onUnregistered: { pubky in
                    identityPath.append(.unregisteredKey(pubky: pubky, loopkyHoldsKey: true))
                }
            )
        case .unregisteredKey(let pubky, let loopkyHoldsKey):
            UnregisteredKeyScreen(
                pubky: pubky,
                loopkyHoldsKey: loopkyHoldsKey,
                onBack: pop,
                onNeedsVerification: {
                    // adopt = true: the terminal step registers *this* key rather than minting a
                    // new one, which would leave the pubky on screen account-less forever.
                    identityPath.append(.signupStart(adoptHeldKey: true))
                },
                // Into the app, not into a backup wall. The key is only on this device, but
                // Profile's card above sign-out and Settings both offer the backup, and the
                // sign-out guard refuses to erase an un-backed-up key without an explicit choice.
                onRegistered: signIn,
                onRestoreWithPhrase: { identityPath.append(.restorePhrase) }
            )
        case .signupStart:
            SignupStartScreen(
                onBack: pop,
                onSms: { identityPath.append(.signupPhone) },
                onLightning: { identityPath.append(.signupLightning) },
                onInviteCode: { identityPath.append(.signupInvite) }
            )
        case .signupPhone:
            PhoneVerificationScreen(onBack: pop, onDone: toLocalSignup)
        case .signupLightning:
            LightningVerificationScreen(onBack: pop, onDone: toLocalSignup)
        case .signupInvite:
            InviteCodeScreen(onBack: pop, onDone: toLocalSignup)
        case .signupLocal(let adoptHeldKey):
            LocalSignupScreen(
                onBack: pop,
                adoptHeldKey: adoptHeldKey,
                // Into the app — see `onRegistered` above for why the un-backed-up key does not
                // earn a wall here. `signIn` drops the whole signup path with it; there is
                // nothing behind this screen worth walking back into.
                onCreated: signIn,
                onStartOver: { identityPath.startSignupOver(adoptHeldKey: adoptHeldKey) }
            )
        }
    }

    /// All three human checks land on the same terminal step, carrying the adopt intent of the
    /// signup attempt the user is actually standing in.
    private func toLocalSignup() {
        identityPath.append(.signupLocal(adoptHeldKey: identityPath.adoptHeldKey))
    }

    private func pop() {
        if !identityPath.isEmpty { identityPath.removeLast() }
    }

    /// Clear the whole flow *and* flip the flag, so the tab screens rebuild against the new
    /// session — Android's `goHomeSignedIn()` pops the entire graph for the same reason.
    private func popDeck() {
        if !deckPath.isEmpty { deckPath.removeLast() }
    }

    /// Swap the top of the stack for another destination.
    private func replaceDeck(_ route: DeckRoute) {
        popDeck()
        deckPath.append(route)
    }

    /// Close a study session: back to the deck it came from, or to the tabs when studying
    /// everything due.
    private func pushOrPopTo(_ route: DeckRoute?) {
        popDeck()
        if let route, deckPath.last != route { deckPath.append(route) }
    }

    private func signIn() {
        identityPath.removeAll()
        deckPath.removeAll()
        launchRouted = true
        isGuest = false
        isSignedIn = true
    }

    /// A guest asked for an account. The browsing shell is left rather than stacked on: everything
    /// that completes a sign-in clears it, so it can never be returned to once there is a session.
    private func leaveGuestShell() {
        deckPath.removeAll()
        launchRouted = true
        isGuest = false
        isSignedIn = false
    }

    /// An explicit sign-out lands on onboarding, never on the browsing shell.
    private func signOut() {
        deckPath.removeAll()
        launchRouted = true
        isGuest = false
        isSignedIn = false
    }
}
