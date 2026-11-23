package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.testing.FakePubkyClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocialEpochsTest {

    private val pubky = FakePubkyClient()
    private var clock = 0L
    private val epochs = SocialEpochs(pubky, now = { clock })

    @Test
    fun aUserWithoutASocialV1ProfileIsV0() = runTest {
        assertEquals(SocialEpoch.V0, epochs.of(USER))
    }

    @Test
    fun aSocialV1ProfileOnTheHomeserverMakesTheUserV1() = runTest {
        pubky.store[SocialLayout.SocialV1.profile(USER)] = """{"name":"Ana"}"""

        assertEquals(SocialEpoch.V1, epochs.of(USER))
        assertEquals(SocialLayout.SocialV1, epochs.layoutOf(USER))
    }

    @Test
    fun v1IsKeptForGoodAndV0IsAskedAgainLater() = runTest {
        epochs.of(USER)
        pubky.store[SocialLayout.SocialV1.profile(USER)] = "{}"
        assertEquals(SocialEpoch.V0, epochs.of(USER), "cached inside the window")

        clock += 5 * 60 * 1000L
        assertEquals(SocialEpoch.V1, epochs.of(USER))

        pubky.store.remove(SocialLayout.SocialV1.profile(USER))
        clock += 60 * 60 * 1000L
        assertEquals(SocialEpoch.V1, epochs.of(USER))
    }

    @Test
    fun aFailedReadIsNotTakenForV0() = runTest {
        pubky.failGetWith = PubkyError("HTTP transport error: connection reset")

        assertTrue(runCatching { epochs.of(USER) }.isFailure)
    }

    @Test
    fun markV1SkipsTheRead() = runTest {
        epochs.markV1(USER)

        assertEquals(SocialEpoch.V1, epochs.of(USER))
        assertTrue(pubky.gets.isEmpty())
    }

    @Test
    fun aWriteDuringTheExclusiveHoldWaitsForIt() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { epochs.exclusive(USER) { release.await() } }
        runCurrent()

        val write = async { epochs.writing(USER) { it } }
        val read = async { epochs.of(USER) }
        runCurrent()
        assertFalse(write.isCompleted)
        assertEquals(SocialEpoch.V0, read.await(), "a read does not wait: the records are still in pubky.app")

        pubky.store[SocialLayout.SocialV1.profile(USER)] = """{"name":"Ana"}"""
        epochs.markV1(USER)
        release.complete(Unit)
        assertEquals(SocialLayout.SocialV1, write.await())
    }

    @Test
    fun aWriteAsksAgainWhenTheCacheSaysV0() = runTest {
        assertEquals(SocialEpoch.V0, epochs.of(USER))
        // Another device flips the user inside the cache's lifetime.
        pubky.store[SocialLayout.SocialV1.profile(USER)] = """{"name":"Ana"}"""

        assertEquals(SocialEpoch.V0, epochs.of(USER), "a read may use the cache")
        assertEquals(SocialLayout.SocialV1, epochs.writing(USER) { it })
    }

    @Test
    fun theExclusiveHoldWaitsForAWriteInFlight() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { epochs.writing(USER) { release.await() } }
        runCurrent()

        val held = async { epochs.exclusive(USER) { "ran" } }
        runCurrent()
        assertFalse(held.isCompleted)

        release.complete(Unit)
        assertEquals("ran", held.await())
    }

    @Test
    fun holdsAreReentrantWhereThatCannotDeadlock() = runTest {
        assertEquals(SocialLayout.Legacy, epochs.writing(USER) { epochs.writing(USER) { it } })
        assertEquals(SocialEpoch.V0, epochs.exclusive(USER) { epochs.writing(USER) { epochs.of(USER) } })
        assertTrue(runCatching { epochs.writing(USER) { epochs.exclusive(USER) { } } }.isFailure)
    }

    @Test
    fun aCancelledHolderReleasesTheUser() = runTest {
        val holder = launch { epochs.exclusive(USER) { awaitCancellation() } }
        runCurrent()
        holder.cancelAndJoin()

        assertEquals(SocialLayout.Legacy, epochs.writing(USER) { it })
    }

    private companion object {
        const val USER = "o1gg96ewuojmopcjbz8895478wdtxtzzuxnfjjz8o8e77csa1ngo"
    }
}
