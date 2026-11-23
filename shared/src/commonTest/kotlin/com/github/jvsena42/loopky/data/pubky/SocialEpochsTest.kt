package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.testing.FakePubkyClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private companion object {
        const val USER = "o1gg96ewuojmopcjbz8895478wdtxtzzuxnfjjz8o8e77csa1ngo"
    }
}
