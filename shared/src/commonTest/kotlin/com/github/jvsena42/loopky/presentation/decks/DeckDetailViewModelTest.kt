package com.github.jvsena42.loopky.presentation.decks

import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.SrsState
import com.github.jvsena42.loopky.domain.model.StudySettings
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeCardRepository
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deck detail is the only screen that shows what is *inside* a deck. It used to read the card
 * cache, which is empty until something else has loaded the deck — so the list was blank on
 * every cold open, and permanently blank for a deck you don't own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckDetailViewModelTest {

    private val identityRepo = FakeIdentityRepository()
    private val deckRepo = FakeDeckRepository()
    private val cardRepo = FakeCardRepository()
    private val srsRepo = FakeSrsRepository()
    private val tagRepo = RecordingTagRepository()
    private val discoveryRepo = FakeDiscoveryRepository()
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

    private fun viewModel(deckId: String = "deck1", authorPubky: String? = null) =
        DeckDetailViewModel(
            deckId = deckId,
            authorPubky = authorPubky,
            deckRepository = deckRepo,
            cardRepository = cardRepo,
            identityRepository = identityRepo,
            srsRepository = srsRepo,
            mediaRepository = FakeMediaRepository(),
            tagRepository = tagRepo,
            discoveryRepository = discoveryRepo,
            appPreferences = preferences,
        )

    @Test
    fun `shows the cards on a cold cache`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(
            cardCount = 2,
        )
        // Nothing has read this deck yet — the cards exist only on the homeserver.
        cardRepo.seedRemote(testCard("c1"), testCard("c2"))

        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals(listOf("c1", "c2"), state.cardPreviews.map { it.id })
    }

    @Test
    fun `shows the cards of a deck you do not own`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(
            authorPubky = "friendpk",
            cardCount = 1,
        )
        cardRepo.seedRemote(testCard("c1", front = "el zorro", back = "the fox"))

        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals(false, state.isOwned)
        assertEquals("el zorro", state.cardPreviews.single().frontText)
        assertEquals("the fox", state.cardPreviews.single().backText)
    }

    @Test
    fun `orders the cards by study order rather than by id`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(
            cardCount = 2,
        )
        // Ids deliberately sort the opposite way to the study order.
        cardRepo.seedRemote(testCard("apple", ord = 1000), testCard("zebra", ord = 0))

        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals(listOf("zebra", "apple"), state.cardPreviews.map { it.id })
    }

    @Test
    fun `a deck with no cards is content rather than an error`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(cardCount = 0)

        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertTrue(state.cardPreviews.isEmpty())
    }

    @Test
    fun `an unreadable card list surfaces as a retryable error`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(cardCount = 1)
        cardRepo.fetchError = IllegalStateException("homeserver unreachable")

        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Error>(vm.state.value)
        assertTrue(state.canRetry)
    }

    @Test
    fun `an owned deck is marked as owned`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = TEST_PUBKY)

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(true, assertIs<DeckDetailUiState.Content>(vm.state.value).isOwned)
    }

    @Test
    fun `your own deck names you from the session without waiting for a profile fetch`() =
        runTest(mainDispatcher) {
            deckRepo.decks["deck1"] = testDeck(authorPubky = TEST_PUBKY)

            val vm = viewModel()
            advanceUntilIdle()

            val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
            assertEquals("Tester", state.author.displayName)
            assertEquals(TEST_PUBKY, state.author.pubky)
        }

    @Test
    fun `another author profile fills in both the name and the avatar`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
        identityRepo.profiles["friendpk"] =
            PubkyIdentity("friendpk", "Ada Lovelace", avatarUrl = "https://pic", bio = null)

        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals("Ada Lovelace", state.author.displayName)
        assertEquals("https://pic", state.author.avatarUrl)
    }

    @Test
    fun `the due count refreshes after this deck is studied`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(cardCount = 1)
        cardRepo.seedRemote(testCard("c1"))
        val card = testCard("c1", deckId = "deck1")
        srsRepo.due = listOf(card)
        // Graded before, so it counts as due rather than new.
        srsRepo.upsert("deck1", dueState("c1"))

        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(expected = "1", actual = assertIs<DeckDetailUiState.Content>(vm.state.value).dueLabel)

        // Grading empties the queue the way a finished study session does.
        srsRepo.review(card, SrsGrade.Good)
        srsRepo.due = emptyList()
        advanceUntilIdle()

        assertEquals(expected = "0", actual = assertIs<DeckDetailUiState.Content>(vm.state.value).dueLabel)
    }

    @Test
    fun `a freshly imported deck reads as new rather than overdue`() = runTest(mainDispatcher) {
        // #101 §7: every card counted as due, so an import opened on "1669 due" and Mastered 0% —
        // indistinguishable from a deck you are hopelessly behind on.
        deckRepo.decks["deck1"] = testDeck(cardCount = 2)
        cardRepo.seedRemote(testCard("c1"), testCard("c2"))
        srsRepo.due = listOf(testCard("c1", deckId = "deck1"), testCard("c2", deckId = "deck1"))

        val vm = viewModel()
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals(expected = "0", actual = state.dueLabel)
        assertEquals(expected = 2, actual = state.newCards)
        assertTrue(state.canStudy, "an untouched deck must still be studiable")
    }

    @Test
    fun `a deck with nothing due and nothing new cannot be studied`() = runTest(mainDispatcher) {
        // The CTA used to stay an enabled primary action that landed straight on "All done!".
        deckRepo.decks["deck1"] = testDeck(cardCount = 1)
        cardRepo.seedRemote(testCard("c1"))
        srsRepo.due = emptyList()

        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(assertIs<DeckDetailUiState.Content>(vm.state.value).canStudy)
    }

    @Test
    fun `unreadable review state shows a dash rather than a confident zero`() = runTest(mainDispatcher) {
        // Due 0 / Mastered 0% presented a fully-mature deck as untouched and caught up.
        deckRepo.decks["deck1"] = testDeck(cardCount = 1)
        cardRepo.seedRemote(testCard("c1"))
        srsRepo.masteryUnavailable = true

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(expected = "—", actual = assertIs<DeckDetailUiState.Content>(vm.state.value).masteredPercent)
    }

    @Test
    fun `a review refreshes the counters without re-reading the deck`() = runTest(mainDispatcher) {
        // #102: this fired a full load per graded card — re-syncing the manifest, rebuilding the
        // card list, and re-fetching the author profile and the Nexus tagger counts. A review can
        // only move the two SRS numbers.
        deckRepo.decks["deck1"] = testDeck(cardCount = 2)
        cardRepo.seedRemote(testCard("c1"), testCard("c2"))
        val card = testCard("c1", deckId = "deck1")
        srsRepo.due = listOf(card, testCard("c2", deckId = "deck1"))

        srsRepo.upsert("deck1", dueState("c1"))
        srsRepo.upsert("deck1", dueState("c2"))

        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(expected = "2", actual = assertIs<DeckDetailUiState.Content>(vm.state.value).dueLabel)
        val fetches = cardRepo.fetchCount
        val profileFetches = identityRepo.fetchedProfiles.size
        val taggerCalls = tagRepo.taggerCountsCalls

        srsRepo.review(card, SrsGrade.Good)
        srsRepo.due = listOf(testCard("c2", deckId = "deck1"))
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals(expected = "1", actual = state.dueLabel, "due count did not follow the review")
        assertEquals(expected = fetches, actual = cardRepo.fetchCount, "re-fetched the deck's cards")
        assertEquals(
            expected = profileFetches,
            actual = identityRepo.fetchedProfiles.size,
            "re-fetched the author profile",
        )
        assertEquals(expected = taggerCalls, actual = tagRepo.taggerCountsCalls, "re-hit the indexer")
    }

    @Test
    fun `mastered share follows the review state`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(cardCount = 2)
        cardRepo.seedRemote(testCard("c1"), testCard("c2"))
        srsRepo.due = listOf(testCard("c1", deckId = "deck1"), testCard("c2", deckId = "deck1"))

        val vm = viewModel()
        advanceUntilIdle()
        // Nothing reviewed yet, so nothing is mature.
        assertEquals(expected = "0%", actual = assertIs<DeckDetailUiState.Content>(vm.state.value).masteredPercent)

        // One of the two cards past SM-2's 21-day maturity line.
        srsRepo.upsert("deck1", matureState("c1")).getOrThrow()
        advanceUntilIdle()

        assertEquals(expected = "50%", actual = assertIs<DeckDetailUiState.Content>(vm.state.value).masteredPercent)
    }

    @Test
    fun `mastered moves within the first session`() = runTest(mainDispatcher) {
        // #101's table, row 4: a deck studied end to end read 0%, identical to one never opened.
        // Partial credit puts a 3-day Good card most of the way to the 7-day Easy line.
        deckRepo.decks["deck1"] = testDeck(cardCount = 1)
        cardRepo.seedRemote(testCard("c1"))
        srsRepo.due = listOf(testCard("c1", deckId = "deck1"))

        val vm = viewModel()
        advanceUntilIdle()
        srsRepo.upsert("deck1", dueState("c1").copy(intervalDays = 3)).getOrThrow()
        advanceUntilIdle()

        assertEquals(expected = "43%", actual = assertIs<DeckDetailUiState.Content>(vm.state.value).masteredPercent)
    }

    @Test
    fun `real but tiny progress says less than one percent instead of zero`() = runTest(mainDispatcher) {
        // 16 mature cards in 1669 rendered "0%" under integer division (#101 §2).
        val cards = (1..200).map { testCard("c$it") }
        deckRepo.decks["deck1"] = testDeck(cardCount = cards.size)
        cardRepo.seedRemote(*cards.toTypedArray())
        srsRepo.due = cards.map { testCard(it.id, deckId = "deck1") }

        val vm = viewModel()
        advanceUntilIdle()
        srsRepo.upsert("deck1", dueState("c1").copy(intervalDays = 1)).getOrThrow()
        advanceUntilIdle()

        assertEquals(expected = "<1%", actual = assertIs<DeckDetailUiState.Content>(vm.state.value).masteredPercent)
    }

    @Test
    fun `a longer interval moves the mastery line with it`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(cardCount = 1)
        cardRepo.seedRemote(testCard("c1"))
        srsRepo.due = listOf(testCard("c1", deckId = "deck1"))
        srsRepo.studySettings = StudySettings(easyDays = 30)

        val vm = viewModel()
        advanceUntilIdle()
        // A card sitting on the longest configured interval is as far out as this scheduler can
        // ever put it, so it reads as mastered. Raising Easy raises the bar; it does not put 100%
        // out of reach, which a fixed 21-day line would.
        srsRepo.upsert("deck1", dueState("c1").copy(intervalDays = 30)).getOrThrow()
        advanceUntilIdle()

        assertEquals(expected = "100%", actual = assertIs<DeckDetailUiState.Content>(vm.state.value).masteredPercent)
    }

    @Test
    fun `studying another deck leaves this one alone`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(cardCount = 1)
        cardRepo.seedRemote(testCard("c1"))
        srsRepo.due = listOf(testCard("c1", deckId = "deck1"))
        srsRepo.upsert("deck1", dueState("c1"))

        val vm = viewModel()
        advanceUntilIdle()
        val fetchesAfterFirstLoad = cardRepo.fetchCount

        srsRepo.review(testCard("c2", deckId = "deck2"), SrsGrade.Good)
        advanceUntilIdle()

        // A review in an unrelated deck must not cost this screen another round of fetches.
        assertEquals(expected = fetchesAfterFirstLoad, actual = cardRepo.fetchCount)
        assertEquals(expected = "1", actual = assertIs<DeckDetailUiState.Content>(vm.state.value).dueLabel)
    }

    @Test
    fun `an author with no profile keeps the pubky instead of blanking out`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")

        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
        assertEquals("friendpk", state.author.pubky)
        assertNull(state.author.displayName)
    }

    // ── follow deck (#33) ────────────────────────────────────────────────

    @Test
    fun `following a foreign deck flips the pill and writes the subscription`() =
        runTest(mainDispatcher) {
            deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
            val vm = viewModel(authorPubky = "friendpk")
            advanceUntilIdle()
            assertEquals(false, assertIs<DeckDetailUiState.Content>(vm.state.value).isFollowing)

            vm.onToggleFollow()
            advanceUntilIdle()

            val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
            assertTrue(state.isFollowing)
            assertEquals(false, state.isFollowPending)
            assertTrue("deck1" in deckRepo.followedDecks)
        }

    @Test
    fun `a failed follow reverts the pill and reports without losing the deck`() =
        runTest(mainDispatcher) {
            deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
            deckRepo.followError = IllegalStateException("homeserver unreachable")
            val vm = viewModel(authorPubky = "friendpk")
            advanceUntilIdle()

            vm.onToggleFollow()
            advanceUntilIdle()

            // The deck is fine; only the write failed, so the page must survive it.
            val state = assertIs<DeckDetailUiState.Content>(vm.state.value)
            assertEquals(false, state.isFollowing)
            assertEquals(false, state.isFollowPending)
            assertNotNull(state.errorReason)
        }

    @Test
    fun `unfollowing an already-followed deck drops the subscription`() = runTest(mainDispatcher) {
        val deck = testDeck(id = "deck1", authorPubky = "friendpk")
        deckRepo.decks["deck1"] = deck
        deckRepo.followedDecks["deck1"] = deck
        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()
        assertTrue(assertIs<DeckDetailUiState.Content>(vm.state.value).isFollowing)

        vm.onToggleFollow()
        advanceUntilIdle()

        assertEquals(false, assertIs<DeckDetailUiState.Content>(vm.state.value).isFollowing)
        assertTrue("deck1" !in deckRepo.followedDecks)
    }

    @Test
    fun `you cannot follow your own deck`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = TEST_PUBKY)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onToggleFollow()
        advanceUntilIdle()

        assertTrue(deckRepo.followedDecks.isEmpty())
        assertEquals(false, assertIs<DeckDetailUiState.Content>(vm.state.value).isFollowing)
    }

    @Test
    fun `opening a followed deck marks it seen so the library stops flagging it`() =
        runTest(mainDispatcher) {
            val deck = testDeck(id = "deck1", authorPubky = "friendpk")
            deckRepo.decks["deck1"] = deck
            deckRepo.followedDecks["deck1"] = deck
            deckRepo.updatedDecks.add("deck1")

            viewModel(authorPubky = "friendpk")
            advanceUntilIdle()

            assertEquals(listOf("deck1"), deckRepo.seen)
        }

    @Test
    fun `a deck you merely browse is not marked seen`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")

        viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        assertTrue(deckRepo.seen.isEmpty())
    }

    @Test
    fun `a foreign deck you have not kept cannot be studied`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk", cardCount = 1)
        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        val effects = mutableListOf<DeckDetailEffect>()
        val job = launch { vm.effects.collect { effects.add(it) } }
        advanceUntilIdle()

        vm.onStudyClick()
        advanceUntilIdle()
        job.cancel()

        // Grading a deck you are only browsing would strand review state under something that
        // never reaches your library or your due queue.
        assertTrue(effects.isEmpty(), "browsing a deck was enough to study it: $effects")
    }

    @Test
    fun `following a foreign deck makes it studiable`() = runTest(mainDispatcher) {
        val deck = testDeck(id = "deck1", authorPubky = "friendpk", cardCount = 1)
        deckRepo.decks["deck1"] = deck
        deckRepo.followedDecks["deck1"] = deck
        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        val effects = mutableListOf<DeckDetailEffect>()
        val job = launch { vm.effects.collect { effects.add(it) } }
        advanceUntilIdle()

        vm.onStudyClick()
        advanceUntilIdle()
        job.cancel()

        assertEquals<List<DeckDetailEffect>>(listOf(DeckDetailEffect.NavigateStudy), effects)
    }

    @Test
    fun `your own deck is studiable without following anything`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = TEST_PUBKY, cardCount = 1)
        val vm = viewModel()
        advanceUntilIdle()

        val effects = mutableListOf<DeckDetailEffect>()
        val job = launch { vm.effects.collect { effects.add(it) } }
        advanceUntilIdle()

        vm.onStudyClick()
        advanceUntilIdle()
        job.cancel()

        assertEquals<List<DeckDetailEffect>>(listOf(DeckDetailEffect.NavigateStudy), effects)
    }

    // ── share on Pubky (#39) ─────────────────────────────────────────────

    @Test
    fun `following a deck offers to announce it and credit the author`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk", title = "Kanji N5")
        identityRepo.profiles["friendpk"] = PubkyIdentity(
            pubky = "friendpk",
            displayName = "Ada",
            avatarUrl = null,
            bio = null,
        )
        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        vm.onToggleFollow()
        advanceUntilIdle()

        val prompt = assertNotNull(assertIs<DeckDetailUiState.Content>(vm.state.value).sharePrompt)
        assertEquals(DeckAnnouncement.Kind.Followed, prompt.kind)
        // The author's key, not the "Ada" the profile fetch resolved: a display name is
        // self-declared and can be renamed out from under a credit already posted.
        assertTrue(prompt.preview.contains("\"Kanji N5\" by friendpk"), prompt.preview)
        // Nothing written until the user says so.
        assertTrue(discoveryRepo.announcements.isEmpty())
    }

    @Test
    fun `unfollowing is never announced`() = runTest(mainDispatcher) {
        val deck = testDeck(id = "deck1", authorPubky = "friendpk")
        deckRepo.decks["deck1"] = deck
        deckRepo.followedDecks["deck1"] = deck
        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        vm.onToggleFollow()
        advanceUntilIdle()

        assertNull(assertIs<DeckDetailUiState.Content>(vm.state.value).sharePrompt)
    }

    @Test
    fun `a failed follow raises no offer`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
        deckRepo.followError = IllegalStateException("homeserver unreachable")
        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        vm.onToggleFollow()
        advanceUntilIdle()

        assertNull(assertIs<DeckDetailUiState.Content>(vm.state.value).sharePrompt)
    }

    @Test
    fun `accepting the offer posts and then follows through to the clone`() =
        runTest(mainDispatcher) {
            deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk", cardCount = 40)
            val vm = viewModel(authorPubky = "friendpk")
            advanceUntilIdle()
            val effects = mutableListOf<DeckDetailEffect>()
            val job = launch { vm.effects.collect { effects.add(it) } }

            vm.onEditClick()
            vm.onConfirmClone("My copy")
            advanceUntilIdle()

            // The clone is done, but the screen has not moved yet — the offer is still up.
            assertEquals(listOf("deck1"), deckRepo.cloned.map { it.id })
            val prompt = assertNotNull(assertIs<DeckDetailUiState.Content>(vm.state.value).sharePrompt)
            assertEquals(DeckAnnouncement.Kind.Cloned, prompt.kind)
            assertTrue(effects.isEmpty())

            vm.onShareConfirm()
            advanceUntilIdle()
            job.cancel()

            assertEquals(expected = 1, actual = discoveryRepo.announcements.size)
            assertEquals<List<DeckDetailEffect>>(
                listOf(DeckDetailEffect.Shared, DeckDetailEffect.Cloned("clone-of-deck1")),
                effects,
            )
        }

    @Test
    fun `a failed announcement still leaves the clone intact and navigates`() =
        runTest(mainDispatcher) {
            discoveryRepo.announceError = IllegalStateException("homeserver down")
            deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk", cardCount = 40)
            val vm = viewModel(authorPubky = "friendpk")
            advanceUntilIdle()
            val effects = mutableListOf<DeckDetailEffect>()
            val job = launch { vm.effects.collect { effects.add(it) } }

            vm.onEditClick()
            vm.onConfirmClone("My copy")
            advanceUntilIdle()
            vm.onShareConfirm()
            advanceUntilIdle()
            job.cancel()

            assertEquals(listOf("deck1"), deckRepo.cloned.map { it.id })
            assertEquals<List<DeckDetailEffect>>(
                listOf(DeckDetailEffect.ShareFailed, DeckDetailEffect.Cloned("clone-of-deck1")),
                effects,
            )
        }

    @Test
    fun `dont ask again turns the settings switch off and posts nothing`() =
        runTest(mainDispatcher) {
            deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
            val vm = viewModel(authorPubky = "friendpk")
            advanceUntilIdle()

            vm.onToggleFollow()
            advanceUntilIdle()
            vm.onShareNeverAsk()
            advanceUntilIdle()

            assertFalse(preferences.shareOnPubkyValue)
            assertTrue(discoveryRepo.announcements.isEmpty())
            assertNull(assertIs<DeckDetailUiState.Content>(vm.state.value).sharePrompt)
        }

    @Test
    fun `with sharing off nothing is asked and nothing is posted`() = runTest(mainDispatcher) {
        preferences.setShareOnPubky(false)
        deckRepo.decks["deck1"] = testDeck(authorPubky = "friendpk")
        val vm = viewModel(authorPubky = "friendpk")
        advanceUntilIdle()

        vm.onToggleFollow()
        advanceUntilIdle()

        assertTrue("deck1" in deckRepo.followedDecks)
        assertNull(assertIs<DeckDetailUiState.Content>(vm.state.value).sharePrompt)
        assertTrue(discoveryRepo.announcements.isEmpty())
    }
}

/** Previously graded and back up for review — "due" in the sense the counters now mean. */
private fun dueState(cardId: String) = SrsState(
    cardId = cardId,
    dueAt = 0L,
    intervalDays = 3,
    easeFactor = 2.5,
    repetitions = 1,
    lastGrade = SrsGrade.Good,
)

/** A card the scheduler has stretched past the 21-day maturity threshold. */
private fun matureState(cardId: String) = SrsState(
    cardId = cardId,
    dueAt = 0L,
    intervalDays = 24,
    easeFactor = 2.5,
    repetitions = 4,
    lastGrade = SrsGrade.Easy,
)
