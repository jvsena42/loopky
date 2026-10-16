package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.MutableSessionProvider
import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.isSessionExpired
import com.github.jvsena42.loopky.data.storage.LocalKey
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.testing.FakeLocalKeyStore
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.VALID_TEST_MNEMONIC
import com.github.jvsena42.loopky.testing.fakePubkyFor
import com.github.jvsena42.loopky.testing.fakeSecretKeyFor
import com.github.jvsena42.loopky.testing.fakeSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionRevalidatorImplTest {

    private val secretKey = fakeSecretKeyFor(VALID_TEST_MNEMONIC)
    private val heldPubky = fakePubkyFor(secretKey)
    private val rejected = PubkyError("Session rejected: 401 Unauthorized")

    private val pubky = FakePubkyClient()
    private val provider = MutableSessionProvider().apply { set(fakeSession(heldPubky)) }
    private val store = SavedSessionStore()

    private fun revalidator(key: LocalKey?) =
        SessionRevalidatorImpl(pubky, provider, store, FakeLocalKeyStore(key))

    private fun heldKey(
        backedUpBy: Set<BackupMethod> = emptySet(),
        registered: Boolean = true,
        pubky: String = heldPubky,
    ) = LocalKey(
        secretKeyHex = secretKey,
        pubky = pubky,
        mnemonic = VALID_TEST_MNEMONIC,
        backedUpBy = backedUpBy,
        registered = registered,
    )

    @Test
    fun aRejectedSessionOnAnUnbackedUpKeyIsReplacedBySigningInAgain() = runTest {
        pubky.revalidateResult = Result.failure(rejected)

        val refreshed = revalidator(heldKey()).revalidate().getOrThrow()

        assertEquals(listOf(secretKey), pubky.signInCalls)
        assertEquals("grant-${heldPubky.take(8)}", refreshed.sessionSecret)
        assertEquals(refreshed, provider.current())
        assertEquals(refreshed, store.saved)
    }

    @Test
    fun theReSignedInSessionKeepsTheHomeserverThePayloadDoesNotName() = runTest {
        pubky.revalidateResult = Result.failure(rejected)

        val refreshed = revalidator(heldKey()).revalidate().getOrThrow()

        assertEquals(fakeSession(heldPubky).homeserver, refreshed.homeserver)
    }

    @Test
    fun aBackedUpKeyIsNotUsed() = runTest {
        pubky.revalidateResult = Result.failure(rejected)

        val result = revalidator(heldKey(backedUpBy = setOf(BackupMethod.RecoveryPhrase))).revalidate()

        assertTrue(result.exceptionOrNull()!!.isSessionExpired())
        assertEquals(emptyList(), pubky.signInCalls)
    }

    @Test
    fun aNetworkFailureNeverSignsInAgain() = runTest {
        // Nothing reached the homeserver, so nothing is known about the session.
        pubky.revalidateResult = Result.failure(
            PubkyError("Failed to import session: Request failed: HTTP transport error: error sending request"),
        )

        revalidator(heldKey()).revalidate()

        assertEquals(emptyList(), pubky.signInCalls)
    }

    @Test
    fun aKeyForAnotherPubkyIsNotUsed() = runTest {
        pubky.revalidateResult = Result.failure(rejected)

        revalidator(heldKey(pubky = "someoneelsepk")).revalidate()

        assertEquals(emptyList(), pubky.signInCalls)
    }

    @Test
    fun anUnregisteredKeyIsNotUsed() = runTest {
        pubky.revalidateResult = Result.failure(rejected)

        revalidator(heldKey(registered = false)).revalidate()

        assertEquals(emptyList(), pubky.signInCalls)
    }

    @Test
    fun withoutAHeldKeyTheRejectionIsTheAnswer() = runTest {
        pubky.revalidateResult = Result.failure(rejected)

        val result = revalidator(key = null).revalidate()

        assertEquals(rejected, result.exceptionOrNull())
        assertEquals(null, store.saved)
    }

    @Test
    fun aFailedReSignInStillReportsTheRejection() = runTest {
        // A transport error from the second call must not turn an expiry into a "retry later".
        pubky.revalidateResult = Result.failure(rejected)
        pubky.signInFailure = PubkyError("Request failed: connection reset")

        val result = revalidator(heldKey()).revalidate()

        assertEquals(rejected, result.exceptionOrNull())
        assertEquals(fakeSession(heldPubky), provider.current())
    }

    @Test
    fun aLiveSessionIsRevalidatedWithoutTouchingTheKey() = runTest {
        pubky.revalidateResult = Result.success(
            """{"pubky":"$heldPubky","capabilities":["/pub/loopky/:rw"],"session_secret":"still-good"}""",
        )

        val refreshed = revalidator(heldKey()).revalidate().getOrThrow()

        assertEquals("still-good", refreshed.sessionSecret)
        assertEquals(emptyList(), pubky.signInCalls)
    }
}

private class SavedSessionStore : SecureSessionStore {
    override val location = "memory"
    var saved: Session? = null

    override suspend fun save(session: Session) {
        saved = session
    }

    override suspend fun load(): Session? = saved

    override suspend fun clear() {
        saved = null
    }
}
