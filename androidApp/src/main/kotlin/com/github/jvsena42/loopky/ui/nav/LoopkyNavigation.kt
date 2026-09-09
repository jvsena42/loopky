package com.github.jvsena42.loopky.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.github.jvsena42.loopky.data.pubky.PubkyLink
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.presentation.profile.FollowSource
import com.github.jvsena42.loopky.ui.backup.BackupFileRoute
import com.github.jvsena42.loopky.ui.backup.BackupPhraseRoute
import com.github.jvsena42.loopky.ui.backup.BackupQuizRoute
import com.github.jvsena42.loopky.ui.backup.BackupRingRoute
import com.github.jvsena42.loopky.ui.backup.BackupStartRoute
import com.github.jvsena42.loopky.ui.decks.DeckDetailRoute
import com.github.jvsena42.loopky.ui.decks.DeckEditorRoute
import com.github.jvsena42.loopky.ui.decks.EditCardRoute
import com.github.jvsena42.loopky.ui.identity.UnregisteredKeyRoute
import com.github.jvsena42.loopky.ui.importflow.BulkImportRoute
import com.github.jvsena42.loopky.ui.importflow.PasteRoute
import com.github.jvsena42.loopky.ui.importflow.PublishDeckRoute
import com.github.jvsena42.loopky.ui.importflow.TriageEditCardRoute
import com.github.jvsena42.loopky.ui.importflow.TriageRoute
import com.github.jvsena42.loopky.ui.onboarding.OnboardingRoute
import com.github.jvsena42.loopky.ui.profile.FollowListRoute
import com.github.jvsena42.loopky.ui.profile.FriendProfileRoute
import com.github.jvsena42.loopky.ui.restore.RestoreFileRoute
import com.github.jvsena42.loopky.ui.restore.RestorePhraseRoute
import com.github.jvsena42.loopky.ui.restore.RestoreStartRoute
import com.github.jvsena42.loopky.ui.search.SearchRoute
import com.github.jvsena42.loopky.ui.settings.SettingsRoute
import com.github.jvsena42.loopky.ui.signup.InviteCodeRoute
import com.github.jvsena42.loopky.ui.signup.LightningVerificationRoute
import com.github.jvsena42.loopky.ui.signup.LocalSignupRoute
import com.github.jvsena42.loopky.ui.signup.PhoneVerificationRoute
import com.github.jvsena42.loopky.ui.signup.SignupStartRoute
import com.github.jvsena42.loopky.ui.study.StudySessionRoute
import com.github.jvsena42.loopky.ui.tagbrowse.TagBrowseRoute

/**
 * [pendingOpen] is what the app was opened with — a `pubky://` address or a deck file. It is held
 * rather than navigated to immediately: a cold start lands on onboarding while the session is
 * restored, and pushing a profile on top of that would put an unusable screen behind the sign-in
 * flow. [onPendingOpenHandled] fires once it is consumed, so a second tap still opens it.
 */
@Composable
internal fun LoopkyNavHost(
    pendingOpen: PendingOpen? = null,
    onPendingOpenHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val currentPendingHandled by rememberUpdatedState(onPendingOpenHandled)

    /**
     * Whether the launch has already been routed off the onboarding screen.
     *
     * Onboarding is the start destination because the persisted session can only be read
     * asynchronously, so every launch lands here first and then steps aside. This flips as soon as it
     * does, which is what tells the *second* and later arrivals apart from the first: those are all
     * deliberate (the guest banner, a sign-in prompt, signing out), and a screen that steps aside
     * when it is asked for is a screen that cannot be reached.
     *
     * Held here rather than as a nav argument so the existing `navigateTo(Routes.ONBOARDING)` call
     * sites keep their duplicate-destination guard, which compares against the bare route pattern.
     */
    var launchRouted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pendingOpen, currentRoute) {
        if (pendingOpen == null || currentRoute == null || currentRoute == Routes.ONBOARDING) {
            return@LaunchedEffect
        }
        when (pendingOpen) {
            is PendingOpen.Link -> {
                when (val link = pendingOpen.link) {
                    is PubkyLink.Profile -> navController.navigateTo(Routes.friendProfile(link.pubky))
                    is PubkyLink.Deck ->
                        navController.navigateTo(Routes.deckDetail(link.deckId, link.pubky))
                }
                currentPendingHandled()
            }
            // Not cleared here: navigating is only half of it, and the file still has to reach
            // the screen. BulkImportRoute clears it once the ViewModel has taken it. navigateTo
            // dedups the current destination, so the re-run this effect gets when the route
            // changes is a no-op.
            is PendingOpen.File -> navController.navigateTo(Routes.IMPORT_BULK)
        }
    }

    NavHost(navController = navController, startDestination = Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            OnboardingRoute(
                // Browsing, not an account. Discover, deck detail, profiles and a deck's cards are
                // public records, so a visitor is shown the app before being asked for the most
                // expensive thing on this screen; every write past that point raises its own prompt.
                //
                // Automatic on the launch that finds no session — see [launchRouted]. The onboarding
                // entry is popped rather than left underneath: it would hand off again the moment a
                // back gesture returned to it.
                autoExplore = !launchRouted,
                onExplore = {
                    launchRouted = true
                    navController.navigateTo(Routes.main(guest = true)) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
                onCreatePubky = { navController.navigateTo(Routes.signupStart()) },
                onRestore = { navController.navigateTo(Routes.RESTORE_START) },
                // Ring holds this key, so Loopky cannot register it — the screen offers the two
                // routes that keep it rather than a button that would mint a different one.
                onUnregistered = { pubky ->
                    navController.navigateTo(Routes.unregisteredKey(pubky, loopkyHoldsKey = false))
                },
                // Also marks the launch routed, so a later sign-out lands on this screen rather
                // than being handed off to browsing: it is the one arrival here that follows an
                // explicit "sign me out", and answering it with the browsing shell would leave no
                // way back to an account short of finding the banner.
                onNavigateHome = {
                    launchRouted = true
                    navController.goHomeSignedIn()
                },
            )
        }
        composable(
            route = Routes.MAIN,
            arguments = listOf(
                navArgument("guest") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
            ),
        ) { entry ->
            MainScreen(
                isGuest = entry.arguments?.getString("guest").toBoolean(),
                // Pushed rather than replacing, so "Not now" and the back gesture both land the
                // visitor back where they were browsing. Everything that *completes* a sign-in
                // clears the whole stack — see [goHomeSignedIn] — so the guest shell underneath
                // can never be returned to once there is an account.
                onSignIn = { navController.navigateTo(Routes.ONBOARDING) },
                onNavigateDeckDetail = { deckId, author ->
                    navController.navigateTo(Routes.deckDetail(deckId, author))
                },
                onNavigateCreateDeck = {
                    // Deck creation always starts at the Paste import flow.
                    navController.navigateTo(Routes.IMPORT_PASTE)
                },
                onNavigateImport = {
                    navController.navigateTo(Routes.IMPORT_PASTE)
                },
                onNavigateImportFile = {
                    navController.navigateTo(Routes.IMPORT_BULK)
                },
                onNavigateStudy = { deckId ->
                    navController.navigateTo(Routes.study(deckId))
                },
                onNavigateProfile = { pubky ->
                    navController.navigateTo(Routes.friendProfile(pubky))
                },
                onNavigateSettings = {
                    navController.navigateTo(Routes.settings())
                },
                onNavigateSearch = {
                    navController.navigateTo(Routes.SEARCH)
                },
                onNavigateFollows = { pubky, source ->
                    navController.navigateTo(Routes.followList(pubky, source))
                },
                onBackUpNow = { navController.navigateTo(Routes.BACKUP_START) },
                onSignOut = {
                    navController.navigateTo(Routes.ONBOARDING) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Routes.SETTINGS,
            arguments = listOf(
                navArgument("focus") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onSignedOut = {
                    navController.navigateTo(Routes.ONBOARDING) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                },
                onBackUpNow = { navController.navigateTo(Routes.BACKUP_START) },
                focus = entry.arguments?.getString("focus"),
            )
        }
        composable(
            route = Routes.DECK_DETAIL,
            arguments = listOf(
                navArgument("deckId") { type = NavType.StringType },
                navArgument("author") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val deckId = backStackEntry.arguments?.getString("deckId") ?: return@composable
            val author = backStackEntry.arguments?.getString("author")
            DeckDetailRoute(
                deckId = deckId,
                authorPubky = author,
                onBack = { navController.popBackStack() },
                onEditDeck = { id -> navController.navigateTo(Routes.deckEditor(id)) },
                onEditCard = { dId, cId -> navController.navigateTo(Routes.editCard(dId, cId)) },
                onStudy = { id -> navController.navigateTo(Routes.study(id)) },
                onPreview = { id -> navController.navigateTo(Routes.studyPreview(id, author)) },
                onSignIn = { navController.navigateTo(Routes.ONBOARDING) },
                onOpenTag = { tag -> navController.navigateTo(Routes.tagBrowse(tag)) },
                onOpenProfile = { pubky -> navController.navigateTo(Routes.friendProfile(pubky)) },
                // The clone is what the user now owns, so replace the source in the back stack
                // rather than stacking a near-identical screen on top of it.
                onOpenClone = { id ->
                    navController.popBackStack()
                    navController.navigateTo(Routes.deckDetail(id))
                },
            )
        }
        composable(Routes.SEARCH) {
            SearchRoute(
                onBack = { navController.popBackStack() },
                onSignIn = { navController.navigateTo(Routes.ONBOARDING) },
                onOpenProfile = { pubky -> navController.navigateTo(Routes.friendProfile(pubky)) },
                onOpenDeck = { deckId, deckAuthor ->
                    navController.navigateTo(Routes.deckDetail(deckId, deckAuthor))
                },
            )
        }
        composable(
            route = Routes.TAG_BROWSE,
            arguments = listOf(navArgument("tag") { type = NavType.StringType }),
        ) { backStackEntry ->
            val tag = backStackEntry.arguments?.getString("tag") ?: return@composable
            TagBrowseRoute(
                tag = tag,
                onBack = { navController.popBackStack() },
                onOpenProfile = { pubky -> navController.navigateTo(Routes.friendProfile(pubky)) },
                onOpenDeck = { deckId, deckAuthor ->
                    navController.navigateTo(Routes.deckDetail(deckId, deckAuthor))
                },
            )
        }
        composable(
            route = Routes.DECK_EDITOR,
            arguments = listOf(navArgument("deckId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val deckId = backStackEntry.arguments?.getString("deckId")
            DeckEditorRoute(
                deckId = deckId,
                onBack = { navController.popBackStack() },
                onEditCard = { dId, cId -> navController.navigateTo(Routes.editCard(dId, cId)) },
                onNewCard = { dId -> navController.navigateTo(Routes.newCard(dId)) },
                onSaved = { savedDeckId ->
                    navController.popBackStack()
                    navController.navigateTo(Routes.deckDetail(savedDeckId))
                },
                onOpenSettings = { navController.navigateTo(Routes.settings(Routes.SETTINGS_FOCUS_UNSPLASH)) },
                onSignedOut = {
                    navController.navigateTo(Routes.ONBOARDING) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.IMPORT_PASTE) {
            PasteRoute(
                onCancel = { navController.popBackStack() },
                onNext = { navController.navigateTo(Routes.IMPORT_TRIAGE) },
            )
        }
        composable(Routes.IMPORT_BULK) {
            // Straight to the shared commit screen: a file import is confirmed once on the
            // summary, not card-by-card through triage.
            BulkImportRoute(
                incoming = (pendingOpen as? PendingOpen.File)?.state,
                onIncomingHandled = currentPendingHandled,
                onBack = { navController.popBackStack() },
                onContinue = { navController.navigateTo(Routes.IMPORT_PUBLISH) },
            )
        }
        composable(Routes.IMPORT_TRIAGE) {
            TriageRoute(
                onBack = { navController.popBackStack() },
                onEditCard = { rowIndex -> navController.navigateTo(Routes.triageEditCard(rowIndex)) },
                onNext = { navController.navigateTo(Routes.IMPORT_PUBLISH) },
            )
        }
        composable(
            route = Routes.IMPORT_TRIAGE_EDIT,
            arguments = listOf(navArgument("rowIndex") { type = NavType.IntType }),
        ) { backStackEntry ->
            val rowIndex = backStackEntry.arguments?.getInt("rowIndex") ?: return@composable
            TriageEditCardRoute(
                rowIndex = rowIndex,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigateTo(Routes.settings(Routes.SETTINGS_FOCUS_UNSPLASH)) },
            )
        }
        composable(Routes.IMPORT_PUBLISH) {
            PublishDeckRoute(
                onBack = { navController.popBackStack() },
                onPublished = { deckId ->
                    // Pop both import screens and navigate to deck detail
                    navController.popBackStack(Routes.MAIN, inclusive = false)
                    navController.navigateTo(Routes.deckDetail(deckId))
                },
                onOpenSettings = { navController.navigateTo(Routes.settings(Routes.SETTINGS_FOCUS_UNSPLASH)) },
                onSignedOut = {
                    navController.navigateTo(Routes.ONBOARDING) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                },
            )
        }
        signupDestinations(navController)
        restoreDestinations(navController)
        backupDestinations(navController)
        cardEditorDestinations(navController)
        composable(
            route = Routes.STUDY,
            arguments = listOf(
                navArgument("deckId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("preview") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("author") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val deckId = backStackEntry.arguments?.getString("deckId")
            StudySessionRoute(
                deckId = deckId,
                isPreview = backStackEntry.arguments?.getString("preview").toBoolean(),
                previewAuthorPubky = backStackEntry.arguments?.getString("author"),
                onClose = { navController.popBackStack() },
                onSignIn = { navController.navigateTo(Routes.ONBOARDING) },
            )
        }
        composable(
            route = Routes.FRIEND_PROFILE,
            arguments = listOf(navArgument("pubky") { type = NavType.StringType }),
        ) { backStackEntry ->
            val pubky = backStackEntry.arguments?.getString("pubky") ?: return@composable
            FriendProfileRoute(
                pubky = pubky,
                onBack = { navController.popBackStack() },
                onSignIn = { navController.navigateTo(Routes.ONBOARDING) },
                // The author comes from the tile: this profile's followed grid holds other
                // people's decks, and opening one against `pubky` reads a manifest that is not there.
                onOpenDeck = { author, deckId ->
                    navController.navigateTo(Routes.deckDetail(deckId, author = author))
                },
                onOpenProfile = { person -> navController.navigateTo(Routes.friendProfile(person)) },
                onOpenFollows = { person, source ->
                    navController.navigateTo(Routes.followList(person, source))
                },
            )
        }
        composable(
            route = Routes.FOLLOW_LIST,
            arguments = listOf(
                navArgument("pubky") { type = NavType.StringType },
                navArgument("source") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val pubky = backStackEntry.arguments?.getString("pubky") ?: return@composable
            // An unrecognised source is a route nobody builds — Routes.followList writes it from
            // the enum — so fall back rather than dropping the destination on the floor.
            val source = FollowSource.entries
                .firstOrNull { it.name.equals(backStackEntry.arguments?.getString("source"), ignoreCase = true) }
                ?: FollowSource.FOLLOWING
            FollowListRoute(
                pubky = pubky,
                source = source,
                onBack = { navController.popBackStack() },
                onOpenProfile = { person -> navController.navigateTo(Routes.friendProfile(person)) },
            )
        }
    }
}

/**
 * Land on the tabbed app with nothing behind it. Every path that ends in a session goes through here.
 *
 * `popUpTo(ONBOARDING)` is not enough on its own: a visitor who signed in from inside the guest shell
 * has a browsing `MAIN` *below* the onboarding screen, and popping to the nearest onboarding entry
 * would leave it there — signed in, one back gesture from a shell with no library in it. Clearing the
 * graph is also what makes the tab screens rebuild and pick up the new session.
 */
private fun NavHostController.goHomeSignedIn() {
    navigateTo(Routes.main()) { popUpTo(graph.id) { inclusive = true } }
}

/**
 * Where a finished verification goes: Ring's deeplink handoff, or Loopky's own redemption. The
 * `adopt` intent is read off the back stack rather than passed down through the three method
 * screens, so those stay byte-identical between the two spenders.
 *
 * **`lastOrNull`, not `firstOrNull`.** A start-over pushes a fresh `SIGNUP_START`, and reading the
 * oldest entry would let an abandoned one outvote the screen the user is standing on.
 */
private fun NavHostController.navigateToRedemption() {
    val entry = currentBackStack.value.lastOrNull { it.destination.route == Routes.SIGNUP_START }
    val adoptHeldKey = entry?.arguments?.getString("adopt").toBoolean()
    navigateTo(Routes.signupLocal(adoptHeldKey = adoptHeldKey))
}

private fun NavGraphBuilder.backupDestinations(navController: NavHostController) {
    composable(Routes.BACKUP_START) {
        BackupStartRoute(
            onBack = { navController.popBackStack() },
            // Both exits pop. Backup is only ever pushed from the Profile nag or Settings, so
            // there is always somewhere to return to — navigating to MAIN instead would lose the
            // caller's place and push a second MAIN onto the stack.
            onDone = { navController.popBackStack() },
            onPhrase = { navController.navigateTo(Routes.BACKUP_PHRASE) },
            onFile = { navController.navigateTo(Routes.BACKUP_FILE) },
            onRing = { navController.navigateTo(Routes.BACKUP_RING) },
        )
    }
    composable(Routes.BACKUP_PHRASE) {
        BackupPhraseRoute(
            onBack = { navController.popBackStack() },
            onConfirm = { navController.navigateTo(Routes.BACKUP_QUIZ) },
        )
    }
    composable(Routes.BACKUP_QUIZ) {
        BackupQuizRoute(
            onBack = { navController.popBackStack() },
            // Back to the menu, not out: having done one method is not a reason to stop offering
            // the others, and the menu now shows this one ticked.
            onDone = {
                navController.navigateTo(Routes.BACKUP_START) {
                    popUpTo(Routes.BACKUP_START) { inclusive = true }
                }
            },
        )
    }
    composable(Routes.BACKUP_FILE) {
        BackupFileRoute(
            onBack = { navController.popBackStack() },
            onDone = {
                navController.navigateTo(Routes.BACKUP_START) {
                    popUpTo(Routes.BACKUP_START) { inclusive = true }
                }
            },
        )
    }
    composable(Routes.BACKUP_RING) {
        BackupRingRoute(
            onBack = { navController.popBackStack() },
            onDone = {
                navController.navigateTo(Routes.BACKUP_START) {
                    popUpTo(Routes.BACKUP_START) { inclusive = true }
                }
            },
        )
    }
}

private fun NavGraphBuilder.restoreDestinations(navController: NavHostController) {
    composable(Routes.RESTORE_START) {
        RestoreStartRoute(
            onBack = { navController.popBackStack() },
            onRestoreWithPhrase = { navController.navigateTo(Routes.RESTORE_PHRASE) },
            onRestoreWithFile = { navController.navigateTo(Routes.RESTORE_FILE) },
        )
    }
    unregisteredKeyDestination(navController)
    restoreFileDestination(navController)
}

/** A valid key with no account, reachable from both the restore path and a Ring sign-in. */
private fun NavGraphBuilder.unregisteredKeyDestination(navController: NavHostController) {
    composable(
        route = Routes.ACCOUNT_UNREGISTERED,
        arguments = listOf(
            navArgument("pubky") { type = NavType.StringType },
            navArgument("local") {
                type = NavType.StringType
                nullable = true
                defaultValue = "false"
            },
        ),
    ) { entry ->
        val pubky = entry.arguments?.getString("pubky").orEmpty()
        val loopkyHolds = entry.arguments?.getString("local").toBoolean()
        UnregisteredKeyRoute(
            pubky = pubky,
            // Only the pubky and "can we register it" cross the nav boundary; the custody object
            // carries no secret either way.
            custody = if (loopkyHolds) KeyCustody.Loopky(pubky = pubky) else KeyCustody.External,
            onBack = { navController.popBackStack() },
            // `adopt`: the verification that follows must register *this* key, not mint a new
            // one. The user has just confirmed this pubky by name.
            onNeedsVerification = {
                navController.navigateTo(Routes.signupStart(adoptHeldKey = true))
            },
            // Home, not the backup menu. The account exists and the key is only on this device,
            // but a wall between registering and using the app is the wrong place to say so — the
            // Profile card above sign-out and Settings both offer it, and the sign-out guard
            // refuses to erase an un-backed-up key without an explicit choice.
            onRegistered = { navController.goHomeSignedIn() },
            onRestoreWithPhrase = { navController.navigateTo(Routes.RESTORE_PHRASE) },
        )
    }
}

private fun NavGraphBuilder.restoreFileDestination(navController: NavHostController) {
    composable(Routes.RESTORE_FILE) {
        RestoreFileRoute(
            onBack = { navController.popBackStack() },
            onUnregistered = { pubky ->
                navController.navigateTo(Routes.unregisteredKey(pubky, loopkyHoldsKey = true))
            },
            // Straight home. Someone who just typed a passphrase and opened their recovery file has
            // this second demonstrated they hold a backup, and answering that with "Back up your
            // account" is a screen arguing with what the user just did. Ring is one deliberate tap
            // away on the Profile backup card.
            onRestored = { navController.goHomeSignedIn() },
        )
    }
    composable(Routes.RESTORE_PHRASE) {
        RestorePhraseRoute(
            onBack = { navController.popBackStack() },
            // Loopky holds the key by the time this fires: derivation succeeded, so it can be
            // registered here rather than needing Ring.
            onUnregistered = { pubky ->
                navController.navigateTo(Routes.unregisteredKey(pubky, loopkyHoldsKey = true))
            },
            // The whole restore stack goes: coming "back" into it after signing in would offer to
            // restore an account the user is already using. Home rather than the backup menu, for
            // the reason on the file route above — the twelve words the user just typed *are* the
            // backup.
            onRestored = { navController.goHomeSignedIn() },
        )
    }
}

/**
 * The signup flow: obtain a token, then hand it to Pubky Ring. Flat like the import flow — the token
 * in flight lives in `SignupRepository`, so no step needs a nav argument and every back press is a
 * plain pop.
 */
private fun NavGraphBuilder.signupDestinations(navController: NavHostController) {
    composable(
        route = Routes.SIGNUP_START,
        arguments = listOf(
            navArgument("adopt") {
                type = NavType.StringType
                nullable = true
                defaultValue = "false"
            },
        ),
    ) {
        SignupStartRoute(
            onBack = { navController.popBackStack() },
            onSms = { navController.navigateTo(Routes.SIGNUP_PHONE) },
            onLightning = { navController.navigateTo(Routes.SIGNUP_LIGHTNING) },
            onInviteCode = { navController.navigateTo(Routes.SIGNUP_INVITE) },
        )
    }
    composable(
        route = Routes.SIGNUP_LOCAL,
        arguments = listOf(
            navArgument("adopt") {
                type = NavType.StringType
                nullable = true
                defaultValue = "false"
            },
        ),
    ) { entry ->
        val adoptHeldKey = entry.arguments?.getString("adopt").toBoolean()
        LocalSignupRoute(
            registerHeldKey = adoptHeldKey,
            onBack = { navController.popBackStack() },
            // Pops the whole spent attempt — the old `SIGNUP_START` included. Popping to
            // `SIGNUP_LOCAL` left the verification screen the dead token came from *and* the original
            // start entry underneath the fresh one, so "back" walked into a spent flow and
            // `navigateToRedemption` had two start entries to choose from.
            //
            // `adoptHeldKey` is carried, not defaulted away: the refused token says nothing about the
            // key, which is still the pubky the user confirmed by name. Losing it here would let the
            // retry mint a *different* identity than the one they approved.
            onStartOver = {
                navController.navigateTo(Routes.signupStart(adoptHeldKey = adoptHeldKey)) {
                    popUpTo(Routes.SIGNUP_START) { inclusive = true }
                }
            },
            // Home, not the backup menu — see `onRegistered` on the unregistered-key screen for
            // why the un-backed-up key does not earn a wall here.
            onCreated = { navController.goHomeSignedIn() },
        )
    }
    signupMethodDestinations(navController)
}

/** The three ways to prove you are not a robot. All three land on the same terminal step. */
private fun NavGraphBuilder.signupMethodDestinations(navController: NavHostController) {
    composable(Routes.SIGNUP_PHONE) {
        PhoneVerificationRoute(
            onBack = { navController.popBackStack() },
            onDone = { navController.navigateToRedemption() },
        )
    }
    composable(Routes.SIGNUP_LIGHTNING) {
        LightningVerificationRoute(
            onBack = { navController.popBackStack() },
            onDone = { navController.navigateToRedemption() },
        )
    }
    composable(Routes.SIGNUP_INVITE) {
        InviteCodeRoute(
            onBack = { navController.popBackStack() },
            onDone = { navController.navigateToRedemption() },
        )
    }
}

/**
 * The card editor, reached either on an existing card or on one that does not exist yet. Two routes
 * rather than a flag on one: the "new" route has a segment fewer, so the two patterns cannot both
 * match the same URL.
 */
private fun NavGraphBuilder.cardEditorDestinations(navController: NavHostController) {
    composable(
        route = Routes.EDIT_CARD,
        arguments = listOf(
            navArgument("deckId") { type = NavType.StringType },
            navArgument("cardId") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val deckId = backStackEntry.arguments?.getString("deckId") ?: return@composable
        val cardId = backStackEntry.arguments?.getString("cardId") ?: return@composable
        EditCardRoute(
            deckId = deckId,
            cardId = cardId,
            onBack = { navController.popBackStack() },
            onOpenSettings = { navController.navigateTo(Routes.settings(Routes.SETTINGS_FOCUS_UNSPLASH)) },
        )
    }
    composable(
        route = Routes.NEW_CARD,
        arguments = listOf(navArgument("deckId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val deckId = backStackEntry.arguments?.getString("deckId") ?: return@composable
        // Blank card id: the editor mints one and appends the card on save.
        EditCardRoute(
            deckId = deckId,
            cardId = "",
            onBack = { navController.popBackStack() },
            onOpenSettings = { navController.navigateTo(Routes.settings(Routes.SETTINGS_FOCUS_UNSPLASH)) },
        )
    }
}
