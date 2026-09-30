package com.github.jvsena42.loopky.presentation.decks

import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.ChunkMeta
import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.domain.model.FormError
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.ordForIndex
import com.github.jvsena42.loopky.testing.CardMove
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeCardRepository
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.testDeck
import com.github.jvsena42.loopky.testing.testDeckWithCards
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
 * The deck editor's two load-bearing rules, both from #52: the card list is a **page** of the deck
 * rather than the deck, and saving an existing deck writes the manifest alone. Everything a card
 * change does — adding, moving — is written when it happens, one or two chunks at a time.
 *
 * The older half of this suite guards what saving must not destroy: anything the editor does not
 * expose (card media, the deck cover, Listen/Speak) used to be wiped on every save.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckEditorViewModelTest {

    private val identityRepo = FakeIdentityRepository()
    private val deckRepo = FakeDeckRepository()
    private val discoveryRepo = FakeDiscoveryRepository()
    private val preferences = FakeAppPreferences()
    private val cardRepo = FakeCardRepository()
    private val mediaRepo = FakeMediaRepository()

    private val mainDispatcher = StandardTestDispatcher()

    private val coverImage = MediaRef.Image(
        path = "media/cover.jpg",
        mime = "image/jpeg",
        sha256 = "coversha",
        width = 800,
        height = 600,
    )
    private val frontImage = MediaRef.Image(
        path = "media/front.jpg",
        mime = "image/jpeg",
        sha256 = "frontsha",
        width = 400,
        height = 300,
    )
    private val backAudio = MediaRef.Audio(
        path = "media/back.m4a",
        mime = "audio/mp4",
        sha256 = "backsha",
        durationMs = 1_200L,
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        // So a move is visible on a reload, not just recorded.
        deckRepo.cardRepository = cardRepo
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seedDeckWithMedia() {
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            authorPubky = TEST_PUBKY,
            cardCount = 1,
            chunks = listOf(ChunkMeta(n = 0, count = 1, updatedAt = 1_000L)),
        ).copy(
            coverImageRef = coverImage,
            listenEnabled = false,
            speakEnabled = false,
        )
        cardRepo.seed(
            Card(
                id = "card1",
                deckId = "deck1",
                updatedAt = 1_000L,
                front = CardSide(text = "hola", imageRef = frontImage),
                back = CardSide(text = "hello", audioRef = backAudio),
            ),
        )
    }

    private fun seedTwoCardDeck() {
        val cards = listOf(
            Card("card1", "deck1", 1L, CardSide(text = "first"), CardSide(text = "1"), ord = 0L),
            Card("card2", "deck1", 2L, CardSide(text = "second"), CardSide(text = "2"), ord = 1_000L),
        )
        deckRepo.decks["deck1"] = testDeckWithCards(cards)
        cardRepo.seed(*cards.toTypedArray())
    }

    /** A deck laid out over several chunk records, so the editor has pages to walk. */
    private fun seedPagedDeck(cardCount: Int) {
        val cards = List(cardCount) { index ->
            Card(
                id = "card${index.toString().padStart(3, '0')}",
                deckId = "deck1",
                updatedAt = 1_000L,
                front = CardSide(text = "front $index"),
                back = CardSide(text = "back $index"),
                ord = ordForIndex(index),
            )
        }
        deckRepo.decks["deck1"] = testDeckWithCards(cards)
        cardRepo.seed(*cards.toTypedArray())
    }

    private fun viewModel(deckId: String? = "deck1") = DeckEditorViewModel(
        deckId = deckId,
        deckRepository = deckRepo,
        cardRepository = cardRepo,
        identityRepository = identityRepo,
        mediaRepository = mediaRepo,
        discoveryRepository = discoveryRepo,
        appPreferences = preferences,
    )

    @Test
    fun `saving an existing deck never rewrites its cards`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTitleChanged("Spanish Basics")
        vm.onSaveClick()
        advanceUntilIdle()

        // The card list is a page of the deck, so rebuilding the deck's cards from it would
        // delete everything not paged in — and destroy card media the editor cannot even see.
        assertTrue(deckRepo.published.isEmpty(), "a metadata save republished the deck's cards")
        val saved = cardRepo.cards.getValue("deck1").getValue("card1")
        assertEquals(frontImage, saved.front.imageRef, "front image was dropped on save")
        assertEquals(backAudio, saved.back.audioRef, "back audio was dropped on save")
    }

    @Test
    fun `a metadata save keeps the chunk table a card write moved`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        // What the card editor's upsertCard does while this screen sits on the back stack.
        val patched = listOf(ChunkMeta(n = 0, count = 2, updatedAt = 9_000L))
        deckRepo.decks["deck1"] = deckRepo.decks.getValue("deck1").copy(cardCount = 2, chunks = patched)

        vm.onTitleChanged("Renamed")
        vm.onSaveClick()
        advanceUntilIdle()

        val saved = deckRepo.decks.getValue("deck1")
        assertEquals(patched, saved.chunks, "the pre-edit chunk table was written back, orphaning a chunk")
        assertEquals(expected = 2, actual = saved.cardCount)
    }

    @Test
    fun `onResume picks up a card the card editor changed`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals("hello", vm.state.value.cards.single().backText)

        cardRepo.seed(
            Card(
                id = "card1",
                deckId = "deck1",
                updatedAt = 2_000L,
                front = CardSide(text = "hola", imageRef = frontImage),
                back = CardSide(text = "hi there", audioRef = backAudio),
            ),
        )
        vm.onResume()
        advanceUntilIdle()

        assertEquals(
            "hi there",
            vm.state.value.cards.single().backText,
            "the list still shows the card as it was before the card editor wrote it",
        )
    }

    @Test
    fun `save preserves the deck cover and card options`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTitleChanged("Spanish Basics")
        vm.onSaveClick()
        advanceUntilIdle()

        val deck = deckRepo.decks.getValue("deck1")
        assertEquals(coverImage, deck.coverImageRef, "deck cover was destroyed on save")
        assertEquals(false, deck.listenEnabled, "listenEnabled was reset to its default")
        assertEquals(false, deck.speakEnabled, "speakEnabled was reset to its default")
    }

    @Test
    fun `save applies edited text`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTitleChanged("Renamed")
        vm.onSaveClick()
        advanceUntilIdle()

        assertEquals("Renamed", deckRepo.decks.getValue("deck1").title)
    }

    // ── reordering (#52) ─────────────────────────────────────────────────

    @Test
    fun `moving a card reorders the list and writes the move straight away`() = runTest(mainDispatcher) {
        seedTwoCardDeck()
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(listOf("card1", "card2"), vm.state.value.cards.map { it.id })

        vm.onMoveCard(from = 0, to = 1)
        advanceUntilIdle()

        assertEquals(listOf("card2", "card1"), vm.state.value.cards.map { it.id })
        // Republishing would rewrite every chunk in the deck to move one row.
        assertTrue(deckRepo.published.isEmpty(), "a reorder republished the whole deck")
        assertEquals(CardMove("deck1", "card1", 1), deckRepo.movedCards.single())
    }

    @Test
    fun `a move that fails puts the list back`() = runTest(mainDispatcher) {
        seedTwoCardDeck()
        val vm = viewModel()
        advanceUntilIdle()
        deckRepo.moveCardError = IllegalStateException("offline")

        vm.onMoveCard(from = 0, to = 1)
        advanceUntilIdle()

        assertEquals(
            listOf("card1", "card2"),
            vm.state.value.cards.map { it.id },
            "the optimistic reorder was left standing after the write failed",
        )
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun `a card can be moved past the loaded window`() = runTest(mainDispatcher) {
        seedPagedDeck(cardCount = 250)
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(expected = 100, actual = vm.state.value.cards.size)

        // The destination is in a chunk this screen has never read — the whole point of
        // "move to position…" on a deck too big to drag through.
        vm.onMoveCard(from = 0, to = 200)
        advanceUntilIdle()

        assertEquals(CardMove("deck1", "card000", 200), deckRepo.movedCards.single())
        assertTrue(
            vm.state.value.cards.none { it.id == "card000" },
            "a card moved out of the loaded window is still shown inside it",
        )
    }

    @Test
    fun `a move target past the end of the deck lands on the last position`() = runTest(mainDispatcher) {
        seedTwoCardDeck()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onMoveCard(from = 0, to = 99)
        advanceUntilIdle()

        assertEquals(CardMove("deck1", "card1", 1), deckRepo.movedCards.single())
    }

    @Test
    fun `renaming a deck writes only the manifest`() = runTest {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTitleChanged("Renamed")
        vm.onSaveClick()
        advanceUntilIdle()

        // Republishing would rewrite every chunk to change one field.
        assertTrue(deckRepo.published.isEmpty(), "a rename republished the whole deck")
        assertEquals("Renamed", deckRepo.decks.getValue("deck1").title)
    }

    @Test
    fun `reserved labels cannot be typed into the tag input`() = runTest {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAddTag("loopky-deck")
        vm.onAddTag("spanish")

        assertEquals(listOf("spanish"), vm.state.value.tags)
    }

    @Test
    fun `a label already on the deck is not added twice`() = runTest {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAddTag("spanish")
        vm.onAddTag(" SPANish ")

        assertEquals(listOf("spanish"), vm.state.value.tags)
    }

    @Test
    fun `adding a card to an existing deck opens the card editor`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()
        val effects = mutableListOf<DeckEditorEffect>()
        val job = launch { vm.effects.collect { effects.add(it) } }

        vm.onAddCard()
        advanceUntilIdle()
        job.cancel()

        // A blank row in this list has nowhere to go now that saving never rewrites the cards.
        assertEquals(DeckEditorEffect.NavigateNewCard("deck1"), effects.single())
        assertEquals(expected = 1, actual = vm.state.value.cards.size)
    }

    @Test
    fun `adding a card to a deck that does not exist yet keeps it in the list`() = runTest(mainDispatcher) {
        val vm = viewModel(deckId = null)
        advanceUntilIdle()

        vm.onAddCard()

        // Nowhere to write it: the deck has no manifest yet, so Save publishes it with the rest.
        assertEquals(expected = 1, actual = vm.state.value.cards.size)
        assertEquals(expected = 1, actual = vm.state.value.totalCards)
    }

    @Test
    fun `a blank row is not published with a new deck`() = runTest(mainDispatcher) {
        val vm = viewModel(deckId = null)
        advanceUntilIdle()
        vm.onTitleChanged("Kanji N5")

        // publish() rejects an empty side, so an untouched Add-card row would fail the save.
        vm.onAddCard()
        vm.onSaveClick()
        advanceUntilIdle()

        val (_, cards) = deckRepo.published.single()
        assertTrue(cards.isEmpty(), "an untouched blank row was published")
    }

    @Test
    fun `moving a card from out of bounds is ignored`() = runTest(mainDispatcher) {
        seedDeckWithMedia()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onMoveCard(from = 4, to = 0)
        advanceUntilIdle()

        assertEquals(listOf("card1"), vm.state.value.cards.map { it.id })
        assertTrue(deckRepo.movedCards.isEmpty())
    }

    // ── paging (#52) ─────────────────────────────────────────────────────

    @Test
    fun `opening a deck reads one chunk rather than the whole deck`() = runTest(mainDispatcher) {
        seedPagedDeck(cardCount = 250)
        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(expected = 100, actual = state.cards.size, "the editor loaded more than one page")
        assertEquals(expected = 250, actual = state.totalCards, "the header counts the page, not the deck")
        assertTrue(state.hasMoreCards)
        assertEquals(listOf("deck1" to 0), cardRepo.readChunks)
    }

    @Test
    fun `scrolling pulls the next chunk in`() = runTest(mainDispatcher) {
        seedPagedDeck(cardCount = 250)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onLoadMoreCards()
        advanceUntilIdle()
        assertEquals(expected = 200, actual = vm.state.value.cards.size)
        assertTrue(vm.state.value.hasMoreCards)

        vm.onLoadMoreCards()
        advanceUntilIdle()
        assertEquals(expected = 250, actual = vm.state.value.cards.size)
        assertFalse(vm.state.value.hasMoreCards, "the last chunk still reports more to come")
    }

    @Test
    fun `a deck bigger than a page does not offer drag-to-reorder`() = runTest(mainDispatcher) {
        seedPagedDeck(cardCount = 250)
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(
            vm.state.value.canDragReorder,
            "dragging one row across 250 would have to cross rows that are not loaded",
        )
    }

    @Test
    fun `a deck that fits in one page still drags`() = runTest(mainDispatcher) {
        seedTwoCardDeck()
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.canDragReorder)
    }

    @Test
    fun `a deck published before the chunk table still loads whole`() = runTest(mainDispatcher) {
        // No chunks in the manifest: there are no page boundaries to walk, so the old
        // whole-deck read is what such a deck gets.
        deckRepo.decks["deck1"] = testDeck(id = "deck1", cardCount = 2)
        cardRepo.seed(
            Card("card1", "deck1", 1L, CardSide(text = "first"), CardSide(text = "1"), ord = 0L),
            Card("card2", "deck1", 2L, CardSide(text = "second"), CardSide(text = "2"), ord = 1_000L),
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("card1", "card2"), vm.state.value.cards.map { it.id })
        assertFalse(vm.state.value.hasMoreCards)
        assertEquals(expected = 1, actual = cardRepo.fetchCount)
    }

    @Test
    fun `onResume re-reads only the pages already on screen`() = runTest(mainDispatcher) {
        seedPagedDeck(cardCount = 250)
        val vm = viewModel()
        advanceUntilIdle()
        cardRepo.readChunks.clear()

        vm.onResume()
        advanceUntilIdle()

        // Returning from a card edit must not silently re-download the rest of a big deck.
        assertEquals(listOf("deck1" to 0), cardRepo.readChunks)
        assertEquals(expected = 100, actual = vm.state.value.cards.size)
    }

    // ── share on Pubky (#39) ─────────────────────────────────────────────

    @Test
    fun `creating a deck in the editor offers to announce it`() = runTest(mainDispatcher) {
        val vm = viewModel(deckId = null)
        advanceUntilIdle()
        vm.onTitleChanged("Kanji N5")

        vm.onSaveClick()
        advanceUntilIdle()

        val prompt = assertNotNull(vm.state.value.sharePrompt)
        assertEquals(DeckAnnouncement.Kind.Created, prompt.kind)
        assertTrue(prompt.preview.contains("Kanji N5"), prompt.preview)
        assertTrue(discoveryRepo.announcements.isEmpty())
    }

    @Test
    fun `editing an existing deck never offers and never posts`() = runTest(mainDispatcher) {
        // save() republishes the whole manifest on every edit, so announcing from the success path
        // unconditionally would post again every time someone fixed a typo.
        deckRepo.decks["deck1"] = testDeck(id = "deck1", title = "Kanji N5")
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTitleChanged("Kanji N5 revised")

        vm.onSaveClick()
        advanceUntilIdle()

        assertNull(vm.state.value.sharePrompt)
        assertTrue(discoveryRepo.announcements.isEmpty())
    }

    @Test
    fun `accepting the offer posts and then leaves for the new deck`() = runTest(mainDispatcher) {
        val vm = viewModel(deckId = null)
        advanceUntilIdle()
        vm.onTitleChanged("Kanji N5")
        val effects = mutableListOf<DeckEditorEffect>()
        val job = launch { vm.effects.collect { effects.add(it) } }

        vm.onSaveClick()
        advanceUntilIdle()
        // The editor stays put while the offer is up; navigating would take it off screen.
        assertTrue(effects.isEmpty())

        vm.onShareConfirm()
        advanceUntilIdle()
        job.cancel()

        assertEquals(expected = 1, actual = discoveryRepo.announcements.size)
        assertEquals(expected = 2, actual = effects.size)
        assertEquals(DeckEditorEffect.Shared, effects.first())
        assertIs<DeckEditorEffect.SaveSuccess>(effects.last())
    }

    @Test
    fun `declining leaves for the new deck without posting`() = runTest(mainDispatcher) {
        val vm = viewModel(deckId = null)
        advanceUntilIdle()
        vm.onTitleChanged("Kanji N5")
        val effects = mutableListOf<DeckEditorEffect>()
        val job = launch { vm.effects.collect { effects.add(it) } }

        vm.onSaveClick()
        advanceUntilIdle()
        vm.onShareDismiss()
        advanceUntilIdle()
        job.cancel()

        assertTrue(discoveryRepo.announcements.isEmpty())
        assertIs<DeckEditorEffect.SaveSuccess>(effects.single())
    }

    @Test
    fun `with sharing off the save leaves immediately`() = runTest(mainDispatcher) {
        preferences.setShareOnPubky(false)
        val vm = viewModel(deckId = null)
        advanceUntilIdle()
        vm.onTitleChanged("Kanji N5")
        val effects = mutableListOf<DeckEditorEffect>()
        val job = launch { vm.effects.collect { effects.add(it) } }

        vm.onSaveClick()
        advanceUntilIdle()
        job.cancel()

        assertNull(vm.state.value.sharePrompt)
        assertIs<DeckEditorEffect.SaveSuccess>(effects.single())
    }

    // ── listen / speak languages ─────────────────────────────────────────

    @Test
    fun `a legacy deck's opt-ins read as off until it declares its languages`() =
        runTest(mainDispatcher) {
            // Every deck published before the pair existed looks like this: the opt-ins say on,
            // but with nothing to read in, study offers neither. Showing them on would misreport
            // what the deck does today.
            deckRepo.decks["deck1"] = testDeck(id = "deck1", authorPubky = TEST_PUBKY)
            val vm = viewModel()
            advanceUntilIdle()

            assertFalse(vm.state.value.listenEnabled)
            assertFalse(vm.state.value.speakEnabled)
        }

    @Test
    fun `an existing deck's languages load into the editor`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            authorPubky = TEST_PUBKY,
            frontLang = "en-US",
            backLang = "es-ES",
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("en-US", vm.state.value.frontLang)
        assertEquals("es-ES", vm.state.value.backLang)
        assertTrue(vm.state.value.listenEnabled)
    }

    @Test
    fun `the type opt-in loads and saves without a language pair`() = runTest(mainDispatcher) {
        // The deck listen/speak cannot help: no declared pair, so typing is its only assisted mode.
        deckRepo.decks["deck1"] = testDeck(id = "deck1", authorPubky = TEST_PUBKY, typeEnabled = true)
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.typeEnabled, "the opt-in was folded through speechReady")

        vm.onToggleType()
        vm.onSaveClick()
        advanceUntilIdle()

        assertNull(vm.state.value.languagesError)
        assertFalse(deckRepo.decks.getValue("deck1").typeEnabled)
    }

    @Test
    fun `the both-directions opt-in loads and saves without a language pair`() =
        runTest(mainDispatcher) {
            // Editable here and not only at publish: a deck published before the opt-in existed
            // has no other way to gain one.
            deckRepo.decks["deck1"] =
                testDeck(id = "deck1", authorPubky = TEST_PUBKY, reverseEnabled = true)
            val vm = viewModel()
            advanceUntilIdle()

            assertTrue(vm.state.value.reverseEnabled, "the opt-in was folded through speechReady")

            vm.onToggleReverse()
            vm.onSaveClick()
            advanceUntilIdle()

            assertNull(vm.state.value.languagesError)
            assertFalse(deckRepo.decks.getValue("deck1").reverseEnabled)
        }

    @Test
    fun `turning on listen without languages blocks the save`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(id = "deck1", authorPubky = TEST_PUBKY)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTitleChanged("Renamed")
        vm.onToggleListen()
        vm.onSaveClick()
        advanceUntilIdle()

        assertEquals(FormError.LanguagesRequired, vm.state.value.languagesError)
        assertEquals("Deck deck1", deckRepo.decks.getValue("deck1").title, "the save went through")
    }

    @Test
    fun `picking a language labels the deck with it`() = runTest(mainDispatcher) {
        // The label is what makes the deck findable by someone learning that language; asking the
        // author to also type "spanish" by hand is a step nobody would remember.
        deckRepo.decks["deck1"] = testDeck(id = "deck1", authorPubky = TEST_PUBKY)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onToggleListen()
        vm.onFrontLangSelected("en-US")
        vm.onBackLangSelected("es-MX")
        vm.onSaveClick()
        advanceUntilIdle()

        assertEquals(
            listOf(Tag("language"), Tag("english"), Tag("spanish")),
            deckRepo.decks.getValue("deck1").tags,
        )
    }

    @Test
    fun `changing a language takes the old label with it`() = runTest(mainDispatcher) {
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            authorPubky = TEST_PUBKY,
            tags = listOf(Tag("verbs")),
            frontLang = "en-US",
            backLang = "es-ES",
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.onBackLangSelected("fr-FR")
        advanceUntilIdle()

        // Left behind, "spanish" lists the deck under a language it no longer teaches. The editor
        // is the one screen that can retype a published deck, so this is where it matters.
        assertEquals(listOf("verbs", "language", "english", "french"), vm.state.value.tags)
    }

    @Test
    fun `the editor is where a legacy deck gets its languages`() = runTest(mainDispatcher) {
        // The whole point of putting these controls here: a published deck could not change its
        // opt-ins at all before, so without this there is no way to fix one.
        deckRepo.decks["deck1"] = testDeck(id = "deck1", authorPubky = TEST_PUBKY)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onToggleListen()
        vm.onFrontLangSelected("en-US")
        vm.onBackLangSelected("es-ES")
        vm.onSaveClick()
        advanceUntilIdle()

        val deck = deckRepo.decks.getValue("deck1")
        assertTrue(deck.listenEnabled)
        assertTrue(deck.speechReady)
        assertEquals("es-ES", deck.backLang)
    }
}
