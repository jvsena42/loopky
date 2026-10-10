package com.github.jvsena42.loopky.presentation.importflow

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.domain.model.DeckLimits
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.FormError
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeImportRepository
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.testDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

@OptIn(ExperimentalCoroutinesApi::class)
class PublishDeckViewModelTest {

    private val importRepo = FakeImportRepository(
        draft = testDraft("hola" to "hello", "gracias" to "thank you"),
    )
    private val deckRepo = FakeDeckRepository()
    private val identityRepo = FakeIdentityRepository()
    private val mediaRepo = FakeMediaRepository()
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

    private fun viewModel() = PublishDeckViewModel(
        importRepository = importRepo,
        deckRepository = deckRepo,
        identityRepository = identityRepo,
        mediaRepository = mediaRepo,
        discoveryRepository = discoveryRepo,
        appPreferences = preferences,
    )

    // ── title prefill ────────────────────────────────────────────────────

    @Test
    fun aFileImportOpensWithTheTitleItAlreadyKnows() = runTest {
        // The file name — or better, the .apkg's own deck name — reached the draft and was then
        // thrown away, leaving the user to retype something the import already had.
        importRepo.draft = testDraft("hola" to "hello").copy(suggestedTitle = "Japanese Core 2000")

        val vm = viewModel()

        assertEquals(expected = "Japanese Core 2000", actual = vm.state.value.title)
        assertNull(vm.state.value.titleError)
    }

    @Test
    fun aSuggestedTitleStaysEditable() = runTest {
        importRepo.draft = testDraft("hola" to "hello").copy(suggestedTitle = "Anki Export")
        val vm = viewModel()

        vm.onTitleChanged("Spanish Basics")

        assertEquals(expected = "Spanish Basics", actual = vm.state.value.title)
    }

    @Test
    fun aPasteStillOpensWithAnEmptyTitle() = runTest {
        // There is nothing to guess a title from, and inventing one is worse than a blank field.
        assertEquals(expected = "", actual = viewModel().state.value.title)
    }

    // ── validation ───────────────────────────────────────────────────────

    @Test
    fun blankTitleBlocksPublish() = runTest {
        val vm = viewModel()
        assertEquals(expected = 2, actual = vm.state.value.cardCount)

        vm.onPublishClick()
        runCurrent()

        // Reported on the title field now, not as a generic bottom-of-form error.
        assertEquals(FormError.TitleRequired, vm.state.value.titleError)
        assertTrue(deckRepo.published.isEmpty())
    }

    @Test
    fun anOverlongTitleIsStoppedAtTheCapRatherThanRejectedOnPublish() = runTest {
        // The field is one line that scrolls horizontally, so a title long enough to fail
        // validation had already scrolled its own beginning out of view — and the error named a
        // number the user could not watch themselves approach. The counter does that instead.
        val vm = viewModel()

        vm.onTitleChanged("x".repeat(200))

        assertEquals(expected = DeckLimits.TITLE_MAX_LENGTH, actual = vm.state.value.title.length)
        assertNull(vm.state.value.titleError)
        assertTrue(vm.state.value.canPublish)
    }

    @Test
    fun tappingPublishWithNoTitleReportsWhyRatherThanDoingNothing() = runTest {
        // The button is enabled so validation can speak. It used to be disabled, which made
        // `validateForPublish` dead code — the tap was swallowed with no feedback at all.
        val vm = viewModel()
        assertNull(vm.state.value.titleError)
        assertTrue(!vm.state.value.canPublish)

        vm.onPublishClick()
        runCurrent()

        assertEquals(FormError.TitleRequired, vm.state.value.titleError)
    }

    @Test
    fun overlongDescriptionBlocksPublish() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onDescriptionChanged("y".repeat(501))
        assertNotNull(vm.state.value.descriptionError)

        vm.onPublishClick()
        runCurrent()

        assertTrue(deckRepo.published.isEmpty())
    }

    @Test
    fun missingDraftSurfacesAnError() = runTest {
        importRepo.draft = null
        val vm = viewModel()
        vm.onTitleChanged("Spanish")

        vm.onPublishClick()
        runCurrent()

        assertEquals(PublishError.NoDraft, vm.state.value.error)
        assertTrue(deckRepo.published.isEmpty())
    }

    @Test
    fun reservedLabelsCannotBeTypedIntoTheTagInput() = runTest {
        val vm = viewModel()

        vm.onAddTag("loopky-deck")
        vm.onAddTag(" LOOPKY-User ")
        vm.onAddTag("spanish")

        assertEquals(listOf("spanish"), vm.state.value.tags)
    }

    @Test
    fun aTypedTagIsShownAsItWillBePublished() = runTest {
        val vm = viewModel()

        vm.onAddTag("Bioquímica Básica")
        vm.onAddTag("bioquimica_basica")
        vm.onAddTag("a".repeat(21))

        // One chip, in the folded spelling (#479); the over-long label never becomes one.
        assertEquals(listOf("bioquimica-basica"), vm.state.value.tags)
    }

    @Test
    fun aLabelAlreadyOnTheDeckIsNotAddedTwice() = runTest {
        val vm = viewModel()

        vm.onAddTag("spanish")
        vm.onAddTag(" SPANish ")

        assertEquals(listOf("spanish"), vm.state.value.tags)
    }

    // ── publish ──────────────────────────────────────────────────────────

    @Test
    fun successfulPublishEntersTheUndoWindow() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish Basics")
        vm.onAddTag(" SPANish ")

        vm.onPublishClick()
        runCurrent()

        val state = vm.state.value
        assertNotNull(state.publishedDeckId)
        assertEquals(expected = 10, actual = state.undoSecondsRemaining)
        assertTrue(!state.isPublishing)
        assertNull(state.error)

        val (deck, cards) = deckRepo.published.single()
        assertEquals("Spanish Basics", deck.title)
        assertEquals(TEST_PUBKY, deck.authorPubky)
        assertEquals(listOf("spanish"), deck.tags.map { it.value })
        assertEquals(listOf("hola", "gracias"), cards.map { it.front.text })
        assertEquals(listOf("hello", "thank you"), cards.map { it.back.text })
        assertEquals(cards.size, deck.cardCount)
    }

    @Test
    fun cancellingAPublishSweepsThePartialDeck() = runTest {
        // #49's marker manifest is written before the first chunk, so an interrupted publish
        // leaves a reachable deck. That is what makes cancel offerable — but a deck the user just
        // took back should not survive in their library.
        deckRepo.publishGate = CompletableDeferred()
        val vm = viewModel()
        vm.onTitleChanged("Japanese Core")
        vm.onPublishClick()
        runCurrent()
        assertTrue(vm.state.value.isPublishing)

        vm.onCancelPublish()
        runCurrent()

        val state = vm.state.value
        assertTrue(!state.isPublishing)
        assertTrue(!state.isCancelling)
        assertEquals(expected = 1, actual = deckRepo.deleted.size, "the partial deck was not swept")
        // The cancellation must not surface as a publish failure. It used to read "…was cancelled"
        // on screen, because the repository's runCatching handed it back as an ordinary error.
        assertNull(state.error)
        assertNull(state.publishedDeckId)
    }

    @Test
    fun cancellingKeepsTheDeckDetailsSoTheUserCanPublishAgain() = runTest {
        deckRepo.publishGate = CompletableDeferred()
        val vm = viewModel()
        vm.onTitleChanged("Japanese Core")
        vm.onPublishClick()
        runCurrent()

        vm.onCancelPublish()
        runCurrent()

        assertEquals(expected = "Japanese Core", actual = vm.state.value.title)
        assertTrue(vm.state.value.canPublish, "cancel must not leave the form unusable")
    }

    @Test
    fun publishFailureSurfacesTheError() = runTest {
        deckRepo.publishError = IllegalStateException("homeserver down")
        val vm = viewModel()
        vm.onTitleChanged("Spanish")

        vm.onPublishClick()
        runCurrent()

        assertEquals(PublishError.Publish(ErrorReason.Unknown), vm.state.value.error)
        assertNull(vm.state.value.publishedDeckId)
        assertTrue(!vm.state.value.isPublishing)
    }

    @Test
    fun aFailedCardImageUploadFailsThePublishInsteadOfDroppingTheImage() = runTest {
        // The deck used to come out looking successfully published and quietly missing the images
        // the user picked, because a failed upload degraded to null. A deck missing media the user
        // chose is a failed publish (#91).
        importRepo.setRowImage(0, isFront = true, image = DraftCardImage(bytes = byteArrayOf(1), mime = "image/jpeg"))
        mediaRepo.failPutImageWith = PubkyError("Request failed: 507 Insufficient Storage")
        val vm = viewModel()
        vm.onTitleChanged("Spanish")

        vm.onPublishClick()
        runCurrent()

        assertEquals(PublishError.Publish(ErrorReason.StorageFull), vm.state.value.error)
        assertTrue(deckRepo.published.isEmpty(), "a deck was published without the chosen image")
        assertFalse(vm.state.value.isPublishing)
        assertNull(vm.state.value.publishedDeckId)
    }

    @Test
    fun anAbortedPublishRemovesTheBlobsItAlreadyUploaded() = runTest {
        // Media goes up before publish() writes the #49 marker manifest, so deckRepository.delete
        // has nothing to walk — anything that landed has to be removed by hand or it is orphaned.
        importRepo.setRowImage(0, isFront = true, image = DraftCardImage(bytes = byteArrayOf(1), mime = "image/jpeg"))
        importRepo.setRowImage(1, isFront = true, image = DraftCardImage(bytes = byteArrayOf(2), mime = "image/jpeg"))
        // The first upload lands; the second is what runs the disk out.
        mediaRepo.failPutImageWith = PubkyError("Request failed: 507 Insufficient Storage")
        mediaRepo.failPutImageFromCall = 2
        val vm = viewModel()
        vm.onTitleChanged("Spanish")

        vm.onPublishClick()
        runCurrent()

        assertEquals(listOf("fake1"), mediaRepo.deletes.map { it.second.sha256 })
    }

    @Test
    fun undoCountdownTicksDown() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()

        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(expected = 7, actual = vm.state.value.undoSecondsRemaining)
    }

    @Test
    fun undoPublishDeletesTheDeckAndRestoresTheReviewState() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()
        val deckId = assertNotNull(vm.state.value.publishedDeckId)

        vm.onUndoPublish()
        runCurrent()

        assertEquals(listOf(deckId), deckRepo.deleted)
        val state = vm.state.value
        assertNull(state.publishedDeckId)
        assertEquals(expected = 0, actual = state.undoSecondsRemaining)
        assertNull(state.error)
        // The draft is preserved so the user can adjust and re-publish.
        assertEquals(expected = 0, actual = importRepo.clearCount)
    }

    @Test
    fun donePublishEmitsPublishedAndClearsTheDraft() = runTest {
        preferences.setShareOnPubky(false)
        val vm = viewModel()
        val effects = mutableListOf<PublishDeckEffect>()
        backgroundScope.launch { vm.effects.collect { effects.add(it) } }
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()
        val deckId = assertNotNull(vm.state.value.publishedDeckId)

        vm.onDonePublish()
        runCurrent()

        assertEquals(listOf<PublishDeckEffect>(PublishDeckEffect.Published(deckId)), effects)
        assertEquals(expected = 1, actual = importRepo.clearCount)
        assertTrue(deckRepo.deleted.isEmpty())
    }

    @Test
    fun countdownExpiryAutoEmitsPublished() = runTest {
        preferences.setShareOnPubky(false)
        val vm = viewModel()
        val effects = mutableListOf<PublishDeckEffect>()
        backgroundScope.launch { vm.effects.collect { effects.add(it) } }
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()
        val deckId = assertNotNull(vm.state.value.publishedDeckId)

        advanceTimeBy(11_000L)
        runCurrent()

        val effect = assertIs<PublishDeckEffect.Published>(effects.single())
        assertEquals(deckId, effect.deckId)
        assertEquals(expected = 1, actual = importRepo.clearCount)
        assertEquals(expected = 0, actual = vm.state.value.undoSecondsRemaining)
    }

    // ── share on Pubky (#39) ─────────────────────────────────────────────

    @Test
    fun theShareOfferWaitsForTheUndoWindowToRunOut() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()

        // Nothing asked while the deck can still be taken back — a post about a deck that is
        // deleted a second later advertises nothing.
        assertNull(vm.state.value.sharePrompt)

        advanceTimeBy(11_000L)
        runCurrent()

        val prompt = assertNotNull(vm.state.value.sharePrompt)
        assertEquals(DeckAnnouncement.Kind.Created, prompt.kind)
        assertTrue(prompt.preview.contains("Spanish"), prompt.preview)
    }

    @Test
    fun undoingInsideTheWindowAsksNothingAndPostsNothing() = runTest {
        val vm = viewModel()
        val effects = mutableListOf<PublishDeckEffect>()
        backgroundScope.launch { vm.effects.collect { effects.add(it) } }
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()

        vm.onUndoPublish()
        advanceTimeBy(11_000L)
        runCurrent()

        assertNull(vm.state.value.sharePrompt)
        assertTrue(discoveryRepo.announcements.isEmpty())
        assertTrue(effects.isEmpty())
    }

    @Test
    fun acceptingTheOfferPostsAndThenLeaves() = runTest {
        val vm = viewModel()
        val effects = mutableListOf<PublishDeckEffect>()
        backgroundScope.launch { vm.effects.collect { effects.add(it) } }
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()
        val deckId = assertNotNull(vm.state.value.publishedDeckId)
        advanceTimeBy(11_000L)
        runCurrent()

        vm.onShareConfirm()
        runCurrent()

        assertEquals(expected = 1, actual = discoveryRepo.announcements.size)
        assertNull(vm.state.value.sharePrompt)
        assertEquals(
            listOf(PublishDeckEffect.Shared, PublishDeckEffect.Published(deckId)),
            effects,
        )
    }

    @Test
    fun aFailedPostStillLeavesTheDeckPublished() = runTest {
        discoveryRepo.announceError = IllegalStateException("homeserver down")
        val vm = viewModel()
        val effects = mutableListOf<PublishDeckEffect>()
        backgroundScope.launch { vm.effects.collect { effects.add(it) } }
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()
        val deckId = assertNotNull(vm.state.value.publishedDeckId)
        advanceTimeBy(11_000L)
        runCurrent()

        vm.onShareConfirm()
        runCurrent()

        assertTrue(deckRepo.deleted.isEmpty())
        assertEquals(
            listOf(PublishDeckEffect.ShareFailed, PublishDeckEffect.Published(deckId)),
            effects,
        )
    }

    @Test
    fun decliningPostsNothing() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()
        advanceTimeBy(11_000L)
        runCurrent()

        vm.onShareDismiss()
        runCurrent()

        assertTrue(discoveryRepo.announcements.isEmpty())
        assertTrue(preferences.shareOnPubkyValue, "Not now must not be a permanent opt-out")
    }

    @Test
    fun dontAskAgainTurnsTheSettingsSwitchOff() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()
        advanceTimeBy(11_000L)
        runCurrent()

        vm.onShareNeverAsk()
        runCurrent()

        assertFalse(preferences.shareOnPubkyValue)
        assertTrue(discoveryRepo.announcements.isEmpty())
    }

    @Test
    fun theOfferIsSkippedEntirelyWhenSharingIsOff() = runTest {
        preferences.setShareOnPubky(false)
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onPublishClick()
        runCurrent()
        advanceTimeBy(11_000L)
        runCurrent()

        assertNull(vm.state.value.sharePrompt)
        assertTrue(discoveryRepo.announcements.isEmpty())
    }

    // ── listen / speak languages ─────────────────────────────────────────

    @Test
    fun listenAndSpeakAreOffUntilAskedFor() = runTest {
        // On by default they would demand a language pair from everyone importing a deck.
        val vm = viewModel()
        runCurrent()

        assertFalse(vm.state.value.listenEnabled)
        assertFalse(vm.state.value.speakEnabled)
        assertTrue(vm.state.value.canPublish || vm.state.value.title.isBlank())
    }

    @Test
    fun typingIsOffUntilAskedForAndNeedsNoLanguagePair() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        runCurrent()

        assertFalse(vm.state.value.typeEnabled)

        vm.onToggleType()
        runCurrent()

        assertTrue(vm.state.value.typeEnabled)
        // The point of the mode: it compares two strings, so nothing about it needs a language.
        assertNull(vm.state.value.frontLang)
        assertTrue(vm.state.value.canPublish, "typing demanded a language pair it does not use")
    }

    @Test
    fun theTypeOptInReachesThePublishedManifest() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onToggleType()
        vm.onPublishClick()
        runCurrent()

        assertTrue(deckRepo.published.single().first.typeEnabled)
    }

    @Test
    fun bothDirectionsIsOffUntilAskedForAndNeedsNoLanguagePair() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        runCurrent()

        assertFalse(vm.state.value.reverseEnabled)

        vm.onToggleReverse()
        runCurrent()

        assertTrue(vm.state.value.reverseEnabled)
        // Swapping two sides has no engine to substitute a locale into, so no pair is demanded.
        assertNull(vm.state.value.frontLang)
        assertTrue(vm.state.value.canPublish, "both directions demanded a language pair")
    }

    @Test
    fun theBothDirectionsOptInReachesThePublishedManifest() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onToggleReverse()
        vm.onPublishClick()
        runCurrent()

        assertTrue(deckRepo.published.single().first.reverseEnabled)
    }

    @Test
    fun anImportThatKnowsItIsReversibleArrivesWithTheOptInOn() = runTest {
        // A suggestion like the title and tags beside it, not a decision — an `.apkg` built on a
        // reversed note type says so, and the toggle is right there to turn off.
        importRepo.draft = testDraft("hola" to "hello").copy(suggestsReverse = true)
        val vm = viewModel()
        runCurrent()

        assertTrue(vm.state.value.reverseEnabled)
    }

    @Test
    fun anOrdinaryImportArrivesWithTheOptInOff() = runTest {
        val vm = viewModel()
        runCurrent()

        assertFalse(vm.state.value.reverseEnabled)
    }

    @Test
    fun turningOnListenWithoutLanguagesBlocksThePublish() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onToggleListen()
        runCurrent()

        assertFalse(vm.state.value.canPublish, "publishable with audio but no language to read in")

        vm.onPublishClick()
        runCurrent()

        assertEquals(FormError.LanguagesRequired, vm.state.value.languagesError)
        assertTrue(deckRepo.published.isEmpty(), "a deck went up with unusable audio metadata")
    }

    @Test
    fun aHalfSetPairIsStillIncomplete() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onToggleSpeak()
        vm.onFrontLangSelected("en-US")
        runCurrent()

        assertFalse(vm.state.value.canPublish)
    }

    @Test
    fun aCompletePairPublishesTheLanguagesOntoTheDeck() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onToggleListen()
        vm.onFrontLangSelected("en-US")
        vm.onBackLangSelected("es-ES")
        runCurrent()

        assertTrue(vm.state.value.canPublish)
        vm.onPublishClick()
        runCurrent()

        val deck = deckRepo.published.single().first
        assertEquals("en-US", deck.frontLang)
        assertEquals("es-ES", deck.backLang)
        assertTrue(deck.speechReady)
    }

    @Test
    fun pickingALanguageLabelsTheDeckWithIt() = runTest {
        // The label is what makes the deck findable by someone learning that language, and asking
        // the author to also type "spanish" by hand is a step nobody would remember.
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onToggleListen()
        vm.onFrontLangSelected("en-US")
        vm.onBackLangSelected("es-MX")
        runCurrent()

        assertEquals(listOf("language", "english", "spanish"), vm.state.value.tags)

        vm.onPublishClick()
        runCurrent()

        assertEquals(
            listOf(Tag("language"), Tag("english"), Tag("spanish")),
            deckRepo.published.single().first.tags,
        )
    }

    @Test
    fun changingALanguageTakesTheOldLabelWithIt() = runTest {
        val vm = viewModel()
        vm.onToggleListen()
        vm.onFrontLangSelected("en-US")
        vm.onBackLangSelected("es-ES")
        vm.onAddTag("verbs")
        runCurrent()

        vm.onBackLangSelected("fr-FR")
        runCurrent()

        // Left behind, "spanish" lists the deck under a language it no longer teaches.
        assertEquals(listOf("language", "english", "verbs", "french"), vm.state.value.tags)
    }

    @Test
    fun anAuthorCanDropALanguageLabelLikeAnyOther() = runTest {
        // Ordinary tags, not a reserved family the author is stuck with.
        val vm = viewModel()
        vm.onToggleListen()
        vm.onFrontLangSelected("en-US")
        vm.onBackLangSelected("es-ES")
        runCurrent()

        vm.onRemoveTag("english")
        runCurrent()

        assertEquals(listOf("language", "spanish"), vm.state.value.tags)
    }

    @Test
    fun turningTheOptInBackOffClearsTheComplaint() = runTest {
        val vm = viewModel()
        vm.onTitleChanged("Spanish")
        vm.onToggleListen()
        vm.onPublishClick()
        runCurrent()
        assertEquals(FormError.LanguagesRequired, vm.state.value.languagesError)

        vm.onToggleListen()
        runCurrent()

        assertNull(vm.state.value.languagesError)
        assertTrue(vm.state.value.canPublish)
    }
}
