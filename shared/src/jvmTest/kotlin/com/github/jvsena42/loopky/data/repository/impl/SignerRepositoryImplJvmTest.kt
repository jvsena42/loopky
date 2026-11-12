package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.MutableSessionProvider
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.UniffiPubkyClient
import com.github.jvsena42.loopky.data.storage.LocalKey
import com.github.jvsena42.loopky.domain.model.RequestedAccess
import com.github.jvsena42.loopky.domain.model.SignInRefusal
import com.github.jvsena42.loopky.domain.model.SignInRefusedException
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeLocalKeyStore
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.signedInProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On the JVM target because links are read by the real `parse_auth_url`: a fake parser would agree
 * with whatever this repository expects, and the point is what the SDK will sign.
 */
class SignerRepositoryImplJvmTest {

    private val fake = FakePubkyClient()
    private val real = UniffiPubkyClient()

    /** The FFI's own parser in front of the fake's approvals and grants. */
    private val client = object : PubkyClient by fake {
        override fun parseAuthUrl(url: String) = real.parseAuthUrl(url)
    }

    private fun repository(
        key: LocalKey? = heldKey(),
        session: MutableSessionProvider = signedInProvider(),
    ) = SignerRepositoryImpl(
        pubky = client,
        keyStore = FakeLocalKeyStore(key),
        session = session,
        revalidator = CountingRevalidator(),
    )

    @Test
    fun `reads a grant sign-in with its client and capabilities`() {
        val request = repository().inspect(grantLink("/pub/loopky/:rw")).getOrThrow()

        assertEquals("mcp.loopky.app", request.clientId)
        assertEquals(listOf(RequestedAccess("/pub/loopky/", canWrite = true)), request.access)
        assertFalse(request.reachesBeyondLoopky)
    }

    @Test
    fun `finds the link inside pasted text and drops the full stop after it`() {
        val request = repository().inspect("Approve this: ${grantLink("/pub/loopky/:rw")}.").getOrThrow()
        assertEquals("mcp.loopky.app", request.clientId)
    }

    @Test
    fun `flags a request for another app's folder`() {
        val request = repository().inspect(grantLink("/pub/pubky.app/:rw")).getOrThrow()
        assertTrue(request.reachesBeyondLoopky)
    }

    @Test
    fun `refuses a legacy sign-in, whose session the grant list would never show`() {
        assertRefused(SignInRefusal.OldFormat) { repository().inspect(LEGACY_LINK).getOrThrow() }
    }

    @Test
    fun `refuses the whole account, and every app's data under pub`() {
        assertRefused(SignInRefusal.WholeAccount) { repository().inspect(grantLink("/:rw")).getOrThrow() }
        assertRefused(SignInRefusal.WholeAccount) { repository().inspect(grantLink("/pub/:rw")).getOrThrow() }
    }

    @Test
    fun `strips bidi controls from the name a request gives itself`() {
        val link = grantLink("/pub/loopky/:rw").replace("cid=mcp.loopky.app", "cid=evil%E2%80%AEppa.ykpool")
        val request = repository().inspect(link).getOrThrow()
        assertEquals("evilppa.ykpool", request.clientId)
    }

    @Test
    fun `refuses a signup link`() {
        val signup = grantLink("/pub/loopky/:rw").replace("signin_grant", "signup_grant") + "&hs=$CLIENT_KEY"
        assertRefused(SignInRefusal.CreatesAccount) { repository().inspect(signup).getOrThrow() }
    }

    @Test
    fun `refuses text that is not a sign-in link`() {
        assertRefused(SignInRefusal.NotASignInLink) { repository().inspect("pubky://$CLIENT_KEY").getOrThrow() }
        assertRefused(SignInRefusal.NotASignInLink) { repository().inspect("pubkyauth://garbage").getOrThrow() }
    }

    @Test
    fun `approves with the held key`() = runTest {
        val repo = repository()
        val request = repo.inspect(grantLink("/pub/loopky/:rw")).getOrThrow()

        repo.approve(request).getOrThrow()

        assertEquals(listOf(request.link to SECRET_HEX), fake.approvals)
    }

    @Test
    fun `will not approve when Pubky Ring holds the key`() = runTest {
        val repo = repository(key = null)
        val request = repo.inspect(grantLink("/pub/loopky/:rw")).getOrThrow()

        assertRefused(SignInRefusal.NoKeyHere) { repo.approve(request).getOrThrow() }
        assertTrue(fake.approvals.isEmpty())
        assertFalse(repo.canApprove.first())
    }

    @Test
    fun `will not approve with a key that belongs to another account`() = runTest {
        val repo = repository(session = signedInProvider("someoneelse"))
        val request = repo.inspect(grantLink("/pub/loopky/:rw")).getOrThrow()

        assertRefused(SignInRefusal.NoKeyHere) { repo.approve(request).getOrThrow() }
        assertTrue(fake.approvals.isEmpty())
    }

    @Test
    fun `will not approve with a key that has no account yet`() = runTest {
        val repo = repository(key = heldKey().copy(registered = false))
        val request = repo.inspect(grantLink("/pub/loopky/:rw")).getOrThrow()

        assertFalse(repo.canApprove.first())
        assertRefused(SignInRefusal.NoKeyHere) { repo.approve(request).getOrThrow() }
    }

    @Test
    fun `lists grants newest first and marks this device's own`() = runTest {
        fake.grantsJson = Result.success(
            """[
              {"grant_id":"old","client_id":"loopky.app","capabilities":"/:rw","issued_at":100,"expires_at":900},
              {"grant_id":"new","client_id":"mcp.loopky.app","capabilities":"/pub/loopky/:rw","issued_at":200,"expires_at":900}
            ]""",
        )
        fake.revalidateResult = Result.success("""{"pubky":"$TEST_PUBKY","grant_id":"old","grant_secret":"s"}""")

        val sessions = repository().authorizedSessions().getOrThrow()

        assertEquals(listOf("new", "old"), sessions.map { it.grantId })
        assertEquals(listOf(false, true), sessions.map { it.isThisDevice })
        assertEquals(listOf(RequestedAccess("/pub/loopky/", canWrite = true)), sessions.first().access)
    }

    @Test
    fun `revokes another app but never this device's own grant`() = runTest {
        fake.revalidateResult = Result.success("""{"pubky":"$TEST_PUBKY","grant_id":"mine","grant_secret":"s"}""")
        val repo = repository()

        repo.revoke("theirs").getOrThrow()
        assertTrue(repo.revoke("mine").isFailure)

        assertEquals(listOf("theirs"), fake.revokedGrants)
    }

    @Test
    fun `revokes nothing when it cannot tell which grant is this device's`() = runTest {
        fake.revalidateResult = Result.failure(IllegalStateException("rate limited"))

        assertTrue(repository().revoke("theirs").isFailure)
        assertTrue(fake.revokedGrants.isEmpty())
    }

    @Test
    fun `refuses a request for encryption keys`() {
        assertRefused(SignInRefusal.KeyMaterial) { repository().inspect(keysLink("/pub/pubky.app/:rwe")).getOrThrow() }
        assertRefused(SignInRefusal.KeyMaterial) { repository().inspect(keysLink("/pub/loopky/:e")).getOrThrow() }
    }

    private fun assertRefused(reason: SignInRefusal, block: suspend () -> Unit) = runTest {
        val error = runCatching { block() }.exceptionOrNull()
        assertEquals(reason, (error as? SignInRefusedException)?.reason, "got: $error")
    }

    private companion object {
        const val SECRET_HEX = "secret-hex"
        const val CLIENT_KEY = "5jsjx1o6fzu6aeeo697r3i5rx15zq41kikcye8wtwdqm4nb4tryo"
        const val CHANNEL_SECRET = "kqnceEMgrNQM_xi06oQXjA3cJHX_RQmw1BY6JE1bse8"
        const val RELAY = "https://httprelay.pubky.app/inbox/"
        const val LEGACY_LINK = "pubkyauth:///?caps=/pub/loopky/:rw&secret=$CHANNEL_SECRET&relay=$RELAY"

        fun grantLink(caps: String) =
            "pubkyauth://signin_grant?caps=$caps&relay=$RELAY&secret=$CHANNEL_SECRET&cid=mcp.loopky.app&cpk=$CLIENT_KEY"

        fun keysLink(caps: String) =
            "pubkyauth://signin_grant?caps=$caps&relay=$RELAY&epk=$CHANNEL_SECRET&af=v1&cid=mcp.loopky.app&cpk=$CLIENT_KEY"

        fun heldKey() = LocalKey(secretKeyHex = SECRET_HEX, pubky = TEST_PUBKY)
    }
}
