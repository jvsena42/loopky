package com.github.jvsena42.loopky.di

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.StudySettings
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two users' graphs over one process share the client and nothing else (#214).
 *
 * Every test gives both tenants the **same deck id**. Deck ids are per author on the homeserver,
 * so `deck1` is two different decks here — and a cache keyed by deck id alone, if it were ever
 * shared between graphs, would hand one tenant the other's cards. Distinct ids would pass over
 * exactly that bug.
 */
class TenantGraphTest {

    private val pubky = FakePubkyClient()
    private val services = TenantServices(
        environment = PubkyEnvironment.Staging,
        pubky = SerializedPubkyClient(pubky),
        http = FakeHttpFetcher(),
    )
    private val opened = mutableListOf<TenantGraph>()

    @AfterTest
    fun tearDown() = opened.forEach { it.close() }

    @Test
    fun aDeckPublishedInOneGraphIsInvisibleToTheOther() = runTest {
        val alice = tenant("alicepk")
        val bob = tenant("bobpk")

        alice.decks.publish(testDeck(id = DECK, authorPubky = "alicepk"), emptyList()).getOrThrow()

        assertEquals(listOf(DECK), alice.decks.listOwned().map { it.id })
        assertEquals(emptyList(), bob.decks.listOwned())
        assertNull(bob.decks.getLocal(DECK))
    }

    @Test
    fun theCardsOfOneGraphAreInvisibleToTheOther() = runTest {
        val alice = tenant("alicepk")
        val bob = tenant("bobpk")
        val cards = listOf(testCard("c1", deckId = DECK), testCard("c2", deckId = DECK))
        alice.decks.publish(testDeck(id = DECK, authorPubky = "alicepk"), cards).getOrThrow()
        bob.decks.publish(testDeck(id = DECK, authorPubky = "bobpk"), listOf(testCard("c9", deckId = DECK)))
            .getOrThrow()

        assertEquals(listOf("c1", "c2"), alice.cards.listByDeck(DECK).map { it.id }.sorted())
        assertEquals(listOf("c9"), bob.cards.listByDeck(DECK).map { it.id })
        assertNull(bob.cards.get(DECK, "c1"))
    }

    @Test
    fun settingsChangedInOneGraphDoNotReachTheOther() = runTest {
        val alice = tenant("alicepk")
        val bob = tenant("bobpk")

        alice.settings.ensureLoaded()
        alice.settings.update(StudySettings(newCardsPerDayGoal = 40)).getOrThrow()
        bob.settings.ensureLoaded()

        assertEquals(40, alice.settings.studySettings.value.settings.newCardsPerDayGoal)
        assertEquals(StudySettings.Default, bob.settings.studySettings.value.settings)
    }

    @Test
    fun reviewStateOfOneGraphIsInvisibleToTheOther() = runTest {
        val alice = tenant("alicepk")
        val bob = tenant("bobpk")
        val card = testCard("c1", deckId = DECK)
        alice.decks.publish(testDeck(id = DECK, authorPubky = "alicepk"), listOf(card)).getOrThrow()
        bob.decks.publish(testDeck(id = DECK, authorPubky = "bobpk"), listOf(card)).getOrThrow()
        alice.srs.dueForDeck(DECK)
        bob.srs.dueForDeck(DECK)

        alice.srs.review(card, SrsGrade.Good).getOrThrow()

        assertNotNull(alice.srs.stateFor(DECK, "c1"))
        assertNull(bob.srs.stateFor(DECK, "c1"))
        assertTrue(bob.srs.statesForDeck(DECK).isEmpty())
    }

    @Test
    fun aCloseDuringAdoptionLeavesNoSessionBehind() = runTest {
        answerRevalidationFor("alicepk")
        val gate = CompletableDeferred<Unit>()
        pubky.revalidateGate = gate
        val graph = TenantGraph.open(services).also(opened::add)
        val adoption = async { graph.adoptSession("secret-alicepk") }
        runCurrent()

        graph.close()
        gate.complete(Unit)

        assertTrue(adoption.await().isFailure)
        assertNull(graph.session)
    }

    @Test
    fun aCancelledSignInClosesTheGraphItOpened() = runTest {
        answerRevalidationFor("alicepk")
        pubky.revalidateGate = CompletableDeferred()
        lateinit var graph: TenantGraph
        val signIn = async {
            TenantGraph.forSession(services, "secret-alicepk") { TenantGraph.open(it).also { graph = it } }
        }
        runCurrent()

        signIn.cancel()
        runCurrent()

        assertTrue(graph.isClosed)
    }

    @Test
    fun eachGraphHoldsItsOwnSessionAndNothingPersistsBetweenThem() = runTest {
        val alice = tenant("alicepk")
        val bob = tenant("bobpk")

        assertEquals("alicepk", alice.session?.identity?.pubky)
        assertEquals("bobpk", bob.session?.identity?.pubky)
        assertEquals("alicepk", alice.identity.currentSession()?.identity?.pubky)
        // The session store is in memory and empty: an adopted session is never written anywhere.
        assertNull(TenantGraph.open(services).also(opened::add).identity.loadPersistedSession())
    }

    @Test
    fun closingOneGraphLeavesTheOtherWorking() = runTest {
        val alice = tenant("alicepk")
        val bob = tenant("bobpk")
        bob.decks.publish(testDeck(id = DECK, authorPubky = "bobpk"), emptyList()).getOrThrow()

        alice.close()

        assertTrue(alice.isClosed)
        assertNull(alice.session)
        assertTrue(alice.decks.publish(testDeck(id = DECK, authorPubky = "alicepk"), emptyList()).isFailure)
        assertEquals(listOf(DECK), bob.decks.listOwned().map { it.id })
        assertEquals("bobpk", bob.session?.identity?.pubky)
    }

    @Test
    fun aGraphAdoptsOneSessionOnly() = runTest {
        val alice = tenant("alicepk")
        answerRevalidationFor("mallorypk")

        val second = alice.adoptSession("secret-mallorypk")

        assertFailsWith<IllegalStateException> { second.getOrThrow() }
        assertEquals("alicepk", alice.session?.identity?.pubky)
        alice.close()
        assertFailsWith<IllegalStateException> { alice.adoptSession("secret-alicepk").getOrThrow() }
    }

    @Test
    fun forSessionFailsWhenTheSecretIsNotLive() = runTest {
        pubky.revalidateResult = Result.failure(PubkyError("401 Unauthorized"))

        assertTrue(TenantGraph.forSession(services, "dead-secret").isFailure)
    }

    /**
     * Real threads, not `runTest`'s single one: the repositories lock per deck and per cache, and
     * a leak between graphs would show as one tenant's card landing in the other's deck.
     */
    @Test
    fun interleavedWritesFromTwoTenantsStayApart() = runBlocking(Dispatchers.Default) {
        val alice = tenant("alicepk")
        val bob = tenant("bobpk")
        alice.decks.publish(testDeck(id = DECK, authorPubky = "alicepk"), emptyList()).getOrThrow()
        bob.decks.publish(testDeck(id = DECK, authorPubky = "bobpk"), emptyList()).getOrThrow()

        (1..WRITES_PER_TENANT).flatMap { n ->
            listOf(
                async { alice.decks.upsertCard(DECK, testCard("a$n", deckId = DECK, front = "alice $n")).getOrThrow() },
                async { bob.decks.upsertCard(DECK, testCard("b$n", deckId = DECK, front = "bob $n")).getOrThrow() },
            )
        }.awaitAll()

        val aliceCards = alice.cards.fetchByDeck(alice.decks.sync(DECK).getOrThrow()).getOrThrow()
        val bobCards = bob.cards.fetchByDeck(bob.decks.sync(DECK).getOrThrow()).getOrThrow()
        assertEquals(WRITES_PER_TENANT, aliceCards.size)
        assertEquals(WRITES_PER_TENANT, bobCards.size)
        assertTrue(aliceCards.all { it.front.text.orEmpty().startsWith("alice ") })
        assertTrue(bobCards.all { it.front.text.orEmpty().startsWith("bob ") })
        assertEquals(WRITES_PER_TENANT, alice.cards.listByDeck(DECK).size)
        assertEquals(WRITES_PER_TENANT, bob.cards.listByDeck(DECK).size)
    }

    private suspend fun tenant(pubkyId: String): TenantGraph {
        answerRevalidationFor(pubkyId)
        return TenantGraph.forSession(services, "secret-$pubkyId").getOrThrow().also(opened::add)
    }

    private fun answerRevalidationFor(pubkyId: String) {
        pubky.revalidateResult = Result.success(
            """{"pubky":"$pubkyId","capabilities":["/pub/loopky/:rw"],"session_secret":"secret-$pubkyId"}""",
        )
        pubky.homeserverLookups[pubkyId] = Result.success("homeserverpk")
    }

    private companion object {
        const val DECK = "deck1"
        const val WRITES_PER_TENANT = 25
    }
}

/**
 * [FakePubkyClient] over one lock. Its maps are plain, and the interleaving test runs real
 * threads; serialising the fake keeps it a fake rather than a race, while every suspension point
 * in the repositories above it still interleaves the two tenants.
 */
private class SerializedPubkyClient(private val delegate: FakePubkyClient) : PubkyClient by delegate {
    private val lock = Mutex()

    override suspend fun putWithSession(url: String, content: String, sessionSecret: String) =
        lock.withLock { delegate.putWithSession(url, content, sessionSecret) }

    override suspend fun putBytesWithSession(url: String, content: ByteArray, sessionSecret: String) =
        lock.withLock { delegate.putBytesWithSession(url, content, sessionSecret) }

    override suspend fun deleteWithSession(url: String, sessionSecret: String) =
        lock.withLock { delegate.deleteWithSession(url, sessionSecret) }

    override suspend fun get(url: String) = lock.withLock { delegate.get(url) }

    override suspend fun getBytes(url: String) = lock.withLock { delegate.getBytes(url) }

    override suspend fun list(url: String, cursor: String?, reverse: Boolean?, limit: UShort?, shallow: Boolean?) =
        lock.withLock { delegate.list(url, cursor, reverse, limit, shallow) }

    override suspend fun revalidateSession(sessionSecret: String) =
        lock.withLock { delegate.revalidateSession(sessionSecret) }

    override suspend fun getHomeserver(pubky: String) = lock.withLock { delegate.getHomeserver(pubky) }
}
