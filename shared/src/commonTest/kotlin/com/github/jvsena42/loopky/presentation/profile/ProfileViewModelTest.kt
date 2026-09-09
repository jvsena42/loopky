package com.github.jvsena42.loopky.presentation.profile

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.repository.CachedDecks
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.fakeSession
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val identity = FakeIdentityRepository()
    private val decks = FakeDeckRepository()
    private val srs = FakeSrsRepository()
    private val discovery = FakeDiscoveryRepository()
    private val preferences = FakeAppPreferences()
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        environment: PubkyEnvironment = PubkyEnvironment.Production,
        prefs: FakeAppPreferences = preferences,
    ) = ProfileViewModel(
        identityRepository = identity,
        deckRepository = decks,
        srsRepository = srs,
        discoveryRepository = discovery,
        appPreferences = prefs,
        pubkyEnvironment = environment,
    )

    private val friend = PubkyIdentity("friendpk", "Grace Hopper", null, null)

    /**
     * Followed decks are studiable (#33) and land review state on your own homeserver. Handing
     * `countsToday` the owned half alone made this screen and Home report two different "due"
     * totals to the same user, with nothing saying so.
     */
    @Test
    fun theDueCountCoversFollowedDecksAsWellAsOwnedOnes() = runTest {
        decks.decks["mine"] = testDeck(id = "mine", cardCount = 1)
        decks.followedDecks["theirs"] = testDeck(id = "theirs", authorPubky = "friendpk", cardCount = 1)
        srs.due = listOf(testCard("c1", deckId = "mine"), testCard("c2", deckId = "theirs"))
        srs.seedDue("mine", "c1")
        srs.seedDue("theirs", "c2")
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(expected = 2, actual = vm.state.value.dueCount)
        // The deck and card counters stay owned-only: those say what you have written.
        assertEquals(expected = 1, actual = vm.state.value.deckCount)
    }

    @Test
    fun sharingHandsOutAnAddressRatherThanABareKey() = runTest {
        identity.profiles[TEST_PUBKY] = PubkyIdentity(TEST_PUBKY, "Ada", null, null)
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<ProfileEffect>()
        val job = launch { vm.effects.toList(effects) }
        vm.onShareClick()
        advanceUntilIdle()
        job.cancel()

        val shared = effects.filterIsInstance<ProfileEffect.ShareProfile>().single()
        assertEquals("pubky://$TEST_PUBKY", shared.uri)
        // Named, so a recipient knows whose profile they are about to open.
        assertEquals("Ada", shared.identity.displayName)
    }

    @Test
    fun theProfileLinkGoesToTheEnvironmentTheBuildSignedInAgainst() = runTest {
        // A staging account has no production profile, so a hardcoded pubky.app would send every
        // debug user to a 404 for their own profile (#42).
        val expected = mapOf(
            PubkyEnvironment.Staging to "https://staging.pubky.app/profile/$TEST_PUBKY",
            PubkyEnvironment.Production to "https://pubky.app/profile/$TEST_PUBKY",
        )
        expected.forEach { (environment, url) ->
            val vm = viewModel(environment)
            advanceUntilIdle()

            val effects = mutableListOf<ProfileEffect>()
            val job = launch { vm.effects.toList(effects) }
            vm.onOpenOnPubkyApp()
            advanceUntilIdle()
            job.cancel()

            assertEquals(url, effects.filterIsInstance<ProfileEffect.OpenUrl>().single().url)
        }
    }

    @Test
    fun theProfileLinkIsWithheldUntilThereIsAnIdentityToLinkTo() = runTest {
        // No session means no pubky, and `.../profile/` with nothing after it is someone else's
        // page — pubky.app reads a bare profile route as the *signed-in* user's.
        identity.session = null
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<ProfileEffect>()
        val job = launch { vm.effects.toList(effects) }
        vm.onOpenOnPubkyApp()
        advanceUntilIdle()
        job.cancel()

        assertTrue(effects.filterIsInstance<ProfileEffect.OpenUrl>().isEmpty())
    }

    @Test
    fun theWholeIdentityReachesTheStateSoTheAvatarCanBeDrawn() = runTest {
        // The screen used to keep only an initial, which is why the signed-in user was the one
        // person in the app whose picture never rendered.
        val picture = "pubky://$TEST_PUBKY/pub/pubky.app/files/0035JHD6154X0"
        identity.profiles[TEST_PUBKY] = PubkyIdentity(TEST_PUBKY, "Ada", picture, "Bio")
        val vm = viewModel()

        advanceUntilIdle()

        val shown = vm.state.value.identity
        assertEquals("Ada", shown?.displayName)
        assertEquals(picture, shown?.avatarUrl)
        assertEquals("Bio", shown?.bio)
    }

    @Test
    fun countsAreOfLoopkyAccountsOnly() = runTest {
        discovery.followingByUser = mapOf(TEST_PUBKY to listOf(friend))
        discovery.followersByUser = mapOf(TEST_PUBKY to emptyList())
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(1, vm.state.value.followingCount)
        assertEquals(0, vm.state.value.followerCount)
    }

    @Test
    fun theProfileRendersBeforeTheFollowCountsResolve() = runTest {
        // Each candidate costs an indexer round-trip, so the counts must not gate the screen.
        discovery.followListGate = CompletableDeferred()
        decks.decks["d1"] = testDeck(id = "d1", cardCount = 12)
        val vm = viewModel()

        advanceUntilIdle()

        assertFalse(vm.state.value.isLoading)
        assertEquals(1, vm.state.value.deckCount)
        assertNull(vm.state.value.followingCount)
    }

    @Test
    fun theProfileIsOnScreenFromCacheWhileTheLoadRuns() = runTest {
        // The header used to sit behind a full-screen spinner for as long as a profile GET plus a
        // deck listing took, on every visit, over a name that had not changed since last launch.
        decks.cached = CachedDecks(owned = listOf(testDeck(id = "d1", cardCount = 12)), followed = emptyList())
        decks.listOwnedGate = CompletableDeferred()
        val vm = viewModel()

        advanceUntilIdle()

        val painted = vm.state.value
        assertFalse(painted.showLoadingScreen)
        assertEquals("Tester", painted.identity?.displayName)
        assertEquals(1, painted.deckCount)
        assertEquals(12, painted.cardCount)
        assertTrue(painted.libraryCountsKnown)
        // Review state is not cached across processes, so the due total is a dash rather than a
        // "0" that turns into a real number a round trip later.
        assertFalse(painted.dueCountKnown)

        decks.listOwnedGate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(vm.state.value.dueCountKnown)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun aColdCacheShowsTheHeaderWithDashesRatherThanCountsItIsGuessing() = runTest {
        decks.cached = null
        decks.listOwnedGate = CompletableDeferred()
        val vm = viewModel()

        advanceUntilIdle()

        val painted = vm.state.value
        assertFalse(painted.showLoadingScreen, "the session already names the account")
        assertFalse(painted.libraryCountsKnown)
        assertFalse(painted.dueCountKnown)
    }

    @Test
    fun aFailedListingKeepsTheCachedCountsRatherThanReportingAnEmptyLibrary() = runTest {
        // Reporting the failure as an empty library turned the counters this screen had just
        // painted from cache into three zeros — which reads as "my decks are gone".
        decks.cached = CachedDecks(owned = listOf(testDeck(id = "d1", cardCount = 12)), followed = emptyList())
        decks.listOwnedError = IllegalStateException("offline")
        val vm = viewModel()

        advanceUntilIdle()

        assertEquals(1, vm.state.value.deckCount)
        assertEquals(12, vm.state.value.cardCount)
        assertTrue(vm.state.value.libraryCountsKnown)
        assertFalse(vm.state.value.dueCountKnown, "nothing answered for the due half")
    }

    /**
     * The profile record, the owned listing and the followed listing are three independent reads;
     * running them one after another made this screen cost their sum.
     */
    @Test
    fun theProfileAndBothDeckListingsAreFetchedTogether() = runTest {
        decks.listOwnedGate = CompletableDeferred()
        identity.profiles[TEST_PUBKY] = PubkyIdentity(TEST_PUBKY, "Ada", null, null)
        val vm = viewModel()

        advanceUntilIdle()

        // The owned listing is still held open, so anything that ran did so beside it.
        assertEquals(listOf(TEST_PUBKY), identity.fetchedProfiles)
        assertEquals(1, decks.listFollowedCount)

        decks.listOwnedGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals("Ada", vm.state.value.identity?.displayName)
    }

    @Test
    fun aFailedCountLeavesTheStatBlankRatherThanBreakingTheScreen() = runTest {
        discovery.followListError = IllegalStateException("indexer down")
        val vm = viewModel()

        advanceUntilIdle()

        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.followingCount)
        assertNull(vm.state.value.followerCount)
    }

    @Test
    fun aReviewMovesTheDueCounterAndNothingElse() = runTest {
        // #102: a graded card used to cost a forceRefresh profile GET, a re-list of every deck and
        // a pair of indexer calls for the follow counts — none of which a review can change.
        decks.decks["d1"] = testDeck(id = "d1", cardCount = 2)
        val card = testCard("c1", deckId = "d1")
        srs.due = listOf(card, testCard("c2", deckId = "d1"))
        srs.seedDue("d1", "c1", "c2")
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.dueCount)
        val profileFetches = identity.fetchedProfiles.size
        val lists = decks.listOwnedCount

        srs.review(card, SrsGrade.Good)
        srs.due = listOf(testCard("c2", deckId = "d1"))
        advanceUntilIdle()

        assertEquals(1, vm.state.value.dueCount)
        assertEquals(profileFetches, identity.fetchedProfiles.size, "re-fetched the profile")
        assertEquals(lists, decks.listOwnedCount, "re-listed the decks")
    }

    @Test
    fun theBackupCardWarnsAboutTheAccountOnScreenAndNoOther() = runTest {
        // The stranded-key case: `createLocalAccount` stores a minted key before `signUp`, an
        // interrupted registration deliberately leaves it behind so the signup can be resumed, and
        // a Pubky Ring sign-in afterwards never touches the key store. Custody then reports a
        // Loopky key for a pubky nobody is signed into — and the card used to offer to back it up
        // from the Ring account's profile.
        identity.profiles[TEST_PUBKY] = PubkyIdentity(TEST_PUBKY, "Ada", null, null)
        identity.custodyFlow.value = KeyCustody.Loopky(pubky = "someone-elses-abandoned-signup")
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.needsBackup, "warned about a key the signed-in account does not own")
    }

    @Test
    fun anUnbackedUpKeyForThisAccountStillRaisesTheCard() = runTest {
        identity.profiles[TEST_PUBKY] = PubkyIdentity(TEST_PUBKY, "Ada", null, null)
        identity.custodyFlow.value = KeyCustody.Loopky(pubky = TEST_PUBKY)
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.needsBackup)
    }

    @Test
    fun anAccountRestoredFromItsPhraseIsNeverNagged() = runTest {
        // Signing in with the phrase *is* the demonstration that a copy exists — the repository
        // records the method the key was restored from, and nagging would describe a risk that
        // does not exist.
        identity.profiles[TEST_PUBKY] = PubkyIdentity(TEST_PUBKY, "Ada", null, null)
        identity.custodyFlow.value = KeyCustody.Loopky(
            pubky = TEST_PUBKY,
            backedUpBy = setOf(BackupMethod.RecoveryPhrase),
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.needsBackup)
    }

    @Test
    fun backingUpMidSessionTakesTheCardAwayWithoutAReload() = runTest {
        identity.profiles[TEST_PUBKY] = PubkyIdentity(TEST_PUBKY, "Ada", null, null)
        identity.custodyFlow.value = KeyCustody.Loopky(pubky = TEST_PUBKY)
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value.needsBackup)

        identity.custodyFlow.value = KeyCustody.Loopky(
            pubky = TEST_PUBKY,
            backedUpBy = setOf(BackupMethod.RecoveryPhrase),
        )
        advanceUntilIdle()

        assertFalse(vm.state.value.needsBackup, "the card outlived the backup that answered it")
    }

    @Test
    fun aProfileWithNoNameIsInvitedToAddOne() = runTest {
        identity.session = fakeSession(displayName = null)
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.showNameNudge)
    }

    @Test
    fun aWhitespaceNameCountsAsNoNameAtAll() = runTest {
        identity.session = fakeSession(displayName = "   ")
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.showNameNudge, "a name of spaces is a pubky on every screen")
    }

    @Test
    fun aNamedProfileIsNeverAsked() = runTest {
        identity.profiles[TEST_PUBKY] = PubkyIdentity(TEST_PUBKY, "Ada", null, null)
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.showNameNudge)
    }

    @Test
    fun dismissingTheInvitationOutlivesTheScreen() = runTest {
        identity.session = fakeSession(displayName = null)
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value.showNameNudge)

        vm.onDismissNameNudge()
        advanceUntilIdle()

        assertFalse(vm.state.value.showNameNudge)
        // Persisted, not just cleared in state: this tab is the one visited most, and a refusal
        // that lasted until the next launch would be a nag nobody can finish refusing.
        assertTrue(preferences.nameNudgeDismissedValue)
    }

    @Test
    fun aStoredDismissalIsHonouredOnTheNextVisit() = runTest {
        identity.session = fakeSession(displayName = null)
        val vm = viewModel(prefs = FakeAppPreferences(nameNudgeDismissed = true))
        advanceUntilIdle()

        assertFalse(vm.state.value.showNameNudge)
    }

    @Test
    fun nothingIsAskedWhileTheProfileIsStillLoading() = runTest {
        identity.session = fakeSession(displayName = null)
        val vm = viewModel()

        // Before the load resolves there is no identity to judge, and a prompt that flashes on
        // every visit to this tab is worse than one that arrives a beat late.
        assertFalse(vm.state.value.showNameNudge)
        assertFalse(vm.state.value.showAvatarNudge)
    }

    @Test
    fun aNamedProfileWithNoPictureIsInvitedToAddOne() = runTest {
        identity.profiles[TEST_PUBKY] = PubkyIdentity(TEST_PUBKY, "Ada", null, null)
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.showAvatarNudge)
    }

    @Test
    fun theTwoInvitationsNeverStack() = runTest {
        identity.session = fakeSession(displayName = null)
        val vm = viewModel()
        advanceUntilIdle()

        // A hero followed by two cards asking for two different things is a wall of chores; the
        // photo waits for the visit after the name is in.
        assertTrue(vm.state.value.showNameNudge)
        assertFalse(vm.state.value.showAvatarNudge)
    }

    @Test
    fun aProfileWithAPictureIsNeverAsked() = runTest {
        identity.profiles[TEST_PUBKY] =
            PubkyIdentity(TEST_PUBKY, "Ada", "pubky://$TEST_PUBKY/pub/pubky.app/files/f1", null)
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.showAvatarNudge)
    }

    @Test
    fun dismissingThePhotoInvitationOutlivesTheScreen() = runTest {
        identity.profiles[TEST_PUBKY] = PubkyIdentity(TEST_PUBKY, "Ada", null, null)
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value.showAvatarNudge)

        vm.onDismissAvatarNudge()
        advanceUntilIdle()

        assertFalse(vm.state.value.showAvatarNudge)
        assertTrue(preferences.avatarNudgeDismissedValue)
    }

    @Test
    fun theTwoDismissalsAreIndependent() = runTest {
        identity.session = fakeSession(displayName = null)
        val vm = viewModel(prefs = FakeAppPreferences(nameNudgeDismissed = true))
        advanceUntilIdle()

        // Refusing to be named must not also refuse the photo — they are two different prompts,
        // and the name card is the only reason the photo one was ever withheld.
        assertFalse(vm.state.value.showNameNudge)
        assertTrue(vm.state.value.showAvatarNudge)
    }
}
