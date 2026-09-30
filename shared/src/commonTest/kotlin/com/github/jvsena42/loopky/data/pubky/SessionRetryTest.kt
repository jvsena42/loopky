package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.signedInProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The write retry loop, exercised on the failure of #165: the `/session` round trip every
 * authenticated write opens with stops going through, and nothing retried it.
 */
class SessionRetryTest {

    /** Verbatim from the device, minus the pubky. */
    private fun unreachable() = PubkyError(
        "Failed to import session: Request failed: HTTP transport error: error sending " +
            "request for url (https://_pubky.rc3omrqq/session)",
    )

    /** Counts attempts, which the fake cannot: it only records writes that succeeded. */
    private class CountingClient(private val delegate: FakePubkyClient) : PubkyClient by delegate {
        var putAttempts = 0

        override suspend fun putWithSession(
            url: String,
            content: String,
            sessionSecret: String,
        ): Result<String> {
            putAttempts++
            return delegate.putWithSession(url, content, sessionSecret)
        }
    }

    @Test
    fun reImportsTheSessionAndRetriesTheWriteOnce() = runTest {
        val fake = FakePubkyClient().apply { failNextSessionCallWith = unreachable() }
        val client = CountingClient(fake)
        val revalidator = CountingRevalidator()

        val result = client.putWithSessionRetry(
            url = "pubky://x/pub/loopky/decks/d1/manifest.json",
            content = "{}",
            session = signedInProvider(),
            revalidator = revalidator,
        )

        // The recovery is the point: revalidate() forgets the FFI's cached session and imports a
        // fresh one, which is the only lever the client has over a wedged session round trip.
        assertTrue(result.isSuccess)
        assertEquals(1, revalidator.invocations)
        assertEquals(2, client.putAttempts)
    }

    @Test
    fun givesUpAfterOneRecoveryRatherThanRetryingAConnectTimeoutForever() = runTest {
        val fake = FakePubkyClient().apply { failAllSessionCallsWith = unreachable() }
        val client = CountingClient(fake)
        val revalidator = CountingRevalidator()

        val result = client.putWithSessionRetry(
            url = "pubky://x/pub/loopky/decks/d1/manifest.json",
            content = "{}",
            session = signedInProvider(),
            revalidator = revalidator,
        )

        // Each attempt costs a ~5s connect timeout on device, so the budget is one recovery — and
        // then an honest answer, which is what SessionUnreachable exists to give.
        assertEquals(1, revalidator.invocations)
        assertEquals(2, client.putAttempts)
        assertEquals(
            ErrorReason.SessionUnreachable,
            result.exceptionOrNull()?.toErrorReason(),
        )
    }

    @Test
    fun retriesTheWriteEvenWhenTheReImportItselfFails() = runTest {
        // The re-import is the same round trip that just failed, so it usually fails too. That is
        // not a reason to stop: unlike an expiry, its failure tells us nothing new — the request
        // never reached the homeserver either time — so the write still gets its second chance.
        val fake = FakePubkyClient().apply { failNextSessionCallWith = unreachable() }
        val client = CountingClient(fake)
        var revalidations = 0
        val failing = SessionRevalidator {
            revalidations++
            Result.failure<Session>(unreachable())
        }

        val result = client.putWithSessionRetry(
            url = "pubky://x/pub/loopky/decks/d1/manifest.json",
            content = "{}",
            session = signedInProvider(),
            revalidator = failing,
        )

        assertTrue(result.isSuccess)
        assertEquals(1, revalidations)
        assertEquals(2, client.putAttempts)
    }
}
