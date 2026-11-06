package com.github.jvsena42.loopky.presentation.decks

import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.domain.model.ReportIssue
import com.github.jvsena42.loopky.domain.model.ReportOutcome
import com.github.jvsena42.loopky.domain.model.ReportSubject
import com.github.jvsena42.loopky.presentation.report.ReportEffect
import com.github.jvsena42.loopky.presentation.report.ReportViewModel
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeCardRepository
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakeDiscoveryRepository
import com.github.jvsena42.loopky.testing.FakeIdentityRepository
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.FakeSrsRepository
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [DiscoveryRepository.report] as the real implementation orders it: the block is cached first,
 * then each of the author's followed decks is unfollowed, one network delete at a time, each one
 * emitting [com.github.jvsena42.loopky.data.repository.DeckRepository.changes].
 */
private class RealOrderReport(
    private val fake: FakeDiscoveryRepository,
    private val decks: FakeDeckRepository,
) : DiscoveryRepository by fake {
    override suspend fun report(subject: ReportSubject, issue: ReportIssue, details: String): Result<ReportOutcome> {
        fake.blocks.add(subject.accountPubky)
        decks.followedDecks.values.filter { it.authorPubky == subject.accountPubky }.toList().forEach {
            delay(100) // the subscription delete
            decks.unfollowDeck(it.authorPubky, it.id)
        }
        return Result.success(ReportOutcome(blocked = true))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DeckDetailReportRaceTest {

    private val deckRepo = FakeDeckRepository()
    private val cardRepo = FakeCardRepository()
    private val discoveryRepo = RealOrderReport(FakeDiscoveryRepository(), deckRepo)
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `reporting a followed deck whose author has another followed deck confirms and leaves`() =
        runTest(mainDispatcher) {
            deckRepo.followedDecks["deck1"] = testDeck(id = "deck1", authorPubky = "trollpk", cardCount = 1)
            deckRepo.followedDecks["deck2"] = testDeck(id = "deck2", authorPubky = "trollpk", cardCount = 1)
            cardRepo.seed(testCard("c0", deckId = "deck1", ord = 0L))

            val vm = DeckDetailViewModel(
                deckId = "deck1",
                authorPubky = "trollpk",
                deckRepository = deckRepo,
                cardRepository = cardRepo,
                identityRepository = FakeIdentityRepository(),
                srsRepository = FakeSrsRepository(),
                mediaRepository = FakeMediaRepository(),
                tagRepository = RecordingTagRepository(),
                discoveryRepository = discoveryRepo,
                appPreferences = FakeAppPreferences(),
            )
            val effects = mutableListOf<DeckDetailEffect>()
            backgroundScope.launch { vm.effects.collect { effects += it } }
            advanceUntilIdle()

            vm.onReportClick()
            runCurrent()
            val deck = (vm.state.value as DeckDetailUiState.Content).reportingDeck!!

            // The sheet host, as ReportSheetHost composes it: present while `reportingDeck` is set,
            // collecting the report's effects, gone (collector cancelled) once it is not.
            val report = ReportViewModel(deck.authorPubky, deck, discoveryRepo)
            val host = backgroundScope.launch {
                report.effects.collect { if (it is ReportEffect.Sent) vm.onReportSent(it.blocked) }
            }
            backgroundScope.launch {
                vm.state.first { (it as? DeckDetailUiState.Content)?.reportingDeck == null }
                host.cancel()
            }
            runCurrent()

            report.onIssueSelected(ReportIssue.Harassment)
            report.onSend()
            advanceUntilIdle()
            runCurrent() // advanceUntilIdle stops once only background collectors are left

            assertEquals(DeckDetailUiState.Blocked, vm.state.value)
            assertEquals(
                listOf<DeckDetailEffect>(DeckDetailEffect.Reported(blocked = true)),
                effects,
                "the report succeeded, but the screen never got Reported: no toast, no back",
            )
        }
}
