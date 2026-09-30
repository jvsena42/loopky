package com.github.jvsena42.loopky.presentation.decks

import com.github.jvsena42.loopky.domain.model.ChunkMeta
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeCardRepository
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cover the deck editor is about to replace (#166).
 *
 * This screen is the only place a published deck's cover can be changed, and it was the only
 * place that would not show the cover being changed: the state carried no ref for it, so every
 * deck with a picture opened on the same accent-soft initial a deck with none gets.
 *
 * Covers come in two shapes and each needs its own answer — a remote cover is a URL the manifest
 * already carries, a homeserver blob is bytes that have to be fetched — so both are pinned here,
 * along with what a save must not do to a cover nobody touched.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class DeckEditorCoverTest {

    private val identityRepo = FakeIdentityRepository()
    private val deckRepo = FakeDeckRepository()
    private val discoveryRepo = FakeDiscoveryRepository()
    private val preferences = FakeAppPreferences()
    private val cardRepo = FakeCardRepository()
    private val mediaRepo = FakeMediaRepository()

    private val mainDispatcher = StandardTestDispatcher()

    private val blobCover = MediaRef.Image(
        path = "media/cover.jpg",
        mime = "image/jpeg",
        sha256 = "coversha",
        width = 800,
        height = 600,
    )

    /** A remote cover: no blob, no digest, and dimensions a rebuild from the URL alone would lose. */
    private val remoteCover = MediaRef.Image(
        path = "",
        mime = "image/jpeg",
        sha256 = "",
        width = 1_200,
        height = 800,
        url = "https://img.test/cover.jpg",
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        deckRepo.cardRepository = cardRepo
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seedDeck(cover: MediaRef.Image?) {
        deckRepo.decks["deck1"] = testDeck(
            id = "deck1",
            authorPubky = TEST_PUBKY,
            cardCount = 0,
            chunks = listOf(ChunkMeta(n = 0, count = 0, updatedAt = 1_000L)),
        ).copy(coverImageRef = cover)
    }

    private fun viewModel() = DeckEditorViewModel(
        deckId = "deck1",
        deckRepository = deckRepo,
        cardRepository = cardRepo,
        identityRepository = identityRepo,
        mediaRepository = mediaRepo,
        discoveryRepository = discoveryRepo,
        appPreferences = preferences,
    )

    @Test
    fun `opening a deck with a blob cover loads its bytes`() = runTest(mainDispatcher) {
        seedDeck(blobCover)
        mediaRepo.blobs[blobCover.sha256] = byteArrayOf(1, 2, 3)
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(
            Base64.encode(byteArrayOf(1, 2, 3)),
            vm.state.value.coverImageBase64,
            "the editor cannot show the cover it is about to replace",
        )
        assertEquals(
            Triple(TEST_PUBKY, "deck1", blobCover as MediaRef),
            mediaRepo.gets.single(),
            "the blob must be read from the deck's author, not the signed-in user",
        )
    }

    @Test
    fun `opening a deck with a remote cover carries its url and fetches nothing`() = runTest(mainDispatcher) {
        seedDeck(remoteCover)
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(remoteCover.url, vm.state.value.coverImageUrl)
        assertNull(vm.state.value.coverImageBase64)
        assertTrue(mediaRepo.gets.isEmpty(), "a remote cover needs no blob fetch")
    }

    @Test
    fun `a deck with no cover fetches nothing`() = runTest(mainDispatcher) {
        seedDeck(null)
        val vm = viewModel()
        advanceUntilIdle()

        assertNull(vm.state.value.coverImageUrl)
        assertNull(vm.state.value.coverImageBase64)
        assertTrue(mediaRepo.gets.isEmpty())
    }

    @Test
    fun `saving an untouched remote cover keeps the stored ref`() = runTest(mainDispatcher) {
        seedDeck(remoteCover)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTitleChanged("Renamed")
        vm.onSaveClick()
        advanceUntilIdle()

        assertEquals(
            remoteCover,
            deckRepo.decks.getValue("deck1").coverImageRef,
            "the loaded url was rebuilt into a poorer ref instead of being left alone",
        )
    }

    @Test
    fun `picking a new cover replaces the loaded one`() = runTest(mainDispatcher) {
        seedDeck(blobCover)
        mediaRepo.blobs[blobCover.sha256] = byteArrayOf(1, 2, 3)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onCoverWebSelected("https://img.test/new.jpg")
        assertNull(vm.state.value.coverImageBase64, "the replaced blob cover is still on screen")

        vm.onSaveClick()
        advanceUntilIdle()

        assertEquals(
            "https://img.test/new.jpg",
            deckRepo.decks.getValue("deck1").coverImageRef?.url,
        )
    }

    @Test
    fun `a cover picked while the blob loads is not overwritten`() = runTest(mainDispatcher) {
        seedDeck(blobCover)
        mediaRepo.blobs[blobCover.sha256] = byteArrayOf(1, 2, 3)
        val gate = CompletableDeferred<Unit>()
        mediaRepo.getGate = gate
        val vm = viewModel()
        advanceUntilIdle()

        vm.onCoverWebSelected("https://img.test/new.jpg")
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("https://img.test/new.jpg", vm.state.value.coverImageUrl)
        assertNull(vm.state.value.coverImageBase64, "a late blob read overwrote the picked cover")
    }

    @Test
    fun `a cover that fails to load leaves the emoji fallback`() = runTest(mainDispatcher) {
        seedDeck(blobCover)
        mediaRepo.failGetWith = RuntimeException("offline")
        val vm = viewModel()
        advanceUntilIdle()

        assertNull(vm.state.value.coverImageBase64)
        assertEquals("D", vm.state.value.coverEmoji, "the initial is the fallback, and it survives")
    }
}
