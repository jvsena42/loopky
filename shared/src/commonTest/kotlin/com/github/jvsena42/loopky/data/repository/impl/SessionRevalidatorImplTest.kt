package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.MutableSessionProvider
import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.isSessionExpired
import com.github.jvsena42.loopky.data.storage.LocalKey
import com.github.jvsena42.loopky.data.storage.LocalKeyStore
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.testing.FakeLocalKeyStore
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.VALID_TEST_MNEMONIC
import com.github.jvsena42.loopky.testing.fakePubkyFor
import com.github.jvsena42.loopky.testing.fakeSecretKeyFor
import com.github.jvsena42.loopky.testing.fakeSession
import com.github.jvsena42.loopky.testing.identityRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun theReSignedInSessionKeepsTheProfile() = runTest {
        pubky.revalidateResult = Result.failure(rejected)

        val refreshed = revalidator(heldKey()).revalidate().getOrThrow()

        assertEquals(fakeSession(heldPubky).identity, refreshed.identity)
    }

    @Test
    fun aBurstOfRejectedWritesSignsInOnce() = runTest {
        // Each sign-in mints a session on the homeserver, so a broken coalesce would leak one per waiter.
        pubky.revalidateResult = Result.failure(rejected)
        pubky.signInGate = CompletableDeferred()
        val revalidator = revalidator(heldKey())

        val waiters = List(5) { async { revalidator.revalidate() } }
        runCurrent()
        pubky.signInGate!!.complete(Unit)
        val secrets = waiters.awaitAll().map { it.getOrThrow().sessionSecret }.toSet()

        assertEquals(1, pubky.signInCalls.size)
        assertEquals(1, secrets.size)
    }

    @Test
    fun signOutDuringAReSignInRevokesTheSessionItMinted() = runTest {
        pubky.revalidateResult = Result.failure(rejected)
        pubky.signInGate = CompletableDeferred()
        val keys = FakeLocalKeyStore(heldKey())
        val revalidator = SessionRevalidatorImpl(pubky, provider, store, keys)
        val identity = identityRepository(
            pubky = pubky,
            sessionStore = store,
            sessionProvider = provider,
            localKeyStore = keys,
            scope = backgroundScope,
        )

        val revalidating = async { revalidator.revalidate() }
        runCurrent()
        val signingOut = async { identity.signOut(force = true) }
        runCurrent()
        assertFalse(signingOut.isCompleted, "sign-out must wait for the re-sign-in in flight")

        pubky.signInGate!!.complete(Unit)
        val minted = revalidating.await().getOrThrow().sessionSecret
        signingOut.await().getOrThrow()

        assertTrue(minted in pubky.signOuts)
        assertEquals(null, provider.current())
        assertEquals(null, store.saved)
    }

    @Test
    fun aReSignInIsNeverWiderThanTheSessionItReplaces() = runTest {
        // The CLI's session is /pub/loopky/:rw by construction; a held key must not widen it.
        provider.set(fakeSession(heldPubky).copy(capabilities = listOf(Capability("/pub/loopky/:rw"))))
        pubky.revalidateResult = Result.failure(rejected)
        pubky.signInCapabilities = listOf("/:rw")

        val result = revalidator(heldKey()).revalidate()

        assertEquals(rejected, result.exceptionOrNull())
        assertEquals(listOf("grant-${heldPubky.take(8)}"), pubky.signOuts)
        assertEquals(null, store.saved)
    }

    @Test
    fun anUnreadableKeystoreStillReportsTheRejection() = runTest {
        pubky.revalidateResult = Result.failure(rejected)

        val result = SessionRevalidatorImpl(pubky, provider, store, UnreadableKeyStore).revalidate()

        assertEquals(rejected, result.exceptionOrNull())
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

private object UnreadableKeyStore : LocalKeyStore {
    override val custody: Flow<KeyCustody> = flowOf(KeyCustody.External)
    override suspend fun save(key: LocalKey) = Unit
    override suspend fun current(): LocalKey? = error("keystore would not decrypt")
    override suspend fun markBackedUp(method: BackupMethod) = Unit
    override suspend fun markRegistered() = Unit
    override suspend fun clear() = Unit
}
