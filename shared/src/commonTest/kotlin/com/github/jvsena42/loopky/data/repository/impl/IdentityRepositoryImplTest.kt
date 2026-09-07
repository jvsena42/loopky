package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.fakeSession
import com.github.jvsena42.loopky.testing.identityRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IdentityRepositoryImplTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val store = RecordingSessionStore()
    private val tags = RecordingTagRepository()

    // The self-tag is fired and not awaited — it used to sit on the splash screen's critical path
    // for ~5.9s. Unconfined so the launch still runs to completion inline here, which is what lets
    // these tests assert on it without a delay.
    private val repo = identityRepository(
        pubky = pubky,
        sessionStore = store,
        sessionProvider = session,
        tagRepository = tags,
        scope = CoroutineScope(UnconfinedTestDispatcher()),
    )

    private val profileUri = PubkyUri(PubkyPaths.profile(TEST_PUBKY))

    private fun publishProfile(name: String, target: String = TEST_PUBKY) {
        pubky.store[PubkyPaths.profile(target)] = """{"name":"$name","bio":null,"image":null}"""
    }

    @Test
    fun profileIsFetchedOnceAndThenServedFromCache() = runTest {
        publishProfile("Ada")

        assertEquals("Ada", repo.fetchProfile(TEST_PUBKY).getOrThrow().displayName)
        // A second read must not hit the homeserver — a deck grid resolves the same author per tile.
        pubky.failGetWith = IllegalStateException("homeserver should not be called again")
        assertEquals("Ada", repo.fetchProfile(TEST_PUBKY).getOrThrow().displayName)
    }

    @Test
    fun forceRefreshReadsPastTheCache() = runTest {
        publishProfile("Ada")
        repo.fetchProfile(TEST_PUBKY)

        publishProfile("Ada Lovelace")

        assertEquals("Ada", repo.fetchProfile(TEST_PUBKY).getOrThrow().displayName)
        assertEquals("Ada Lovelace", repo.fetchProfile(TEST_PUBKY, forceRefresh = true).getOrThrow().displayName)
    }

    @Test
    fun aMissingProfileIsNotCached() = runTest {
        assertTrue(repo.fetchProfile(TEST_PUBKY).isFailure)

        // Publishing later must become visible without restarting the app.
        publishProfile("Ada")

        assertEquals("Ada", repo.fetchProfile(TEST_PUBKY).getOrThrow().displayName)
    }

    @Test
    fun anUnpublishedProfileIsReportedAsAbsentRatherThanAsAFailedRead() = runTest {
        // The default state of any pubky created outside pubky.app, and the reason sign-in used to
        // log an error with a stack trace on every launch (#174). Absence has to stay tellable
        // apart from "we could not read it": the first is an answer, the second is a fault.
        val failure = repo.fetchProfile(TEST_PUBKY).exceptionOrNull()

        assertEquals(ErrorReason.NotFound, failure?.toErrorReason())
    }

    @Test
    fun aProfileWeCouldNotReadIsNotReportedAsAbsent() = runTest {
        pubky.failGetWith = PubkyError(
            "Request failed: HTTP transport error: error sending request for url (…)",
        )

        val failure = repo.fetchProfile(TEST_PUBKY).exceptionOrNull()

        assertEquals(ErrorReason.Offline, failure?.toErrorReason())
    }

    @Test
    fun updatingTheProfileRefreshesTheCache() = runTest {
        publishProfile("Ada")
        repo.fetchProfile(TEST_PUBKY)

        repo.updateProfile(name = "Ada Lovelace", bio = null).getOrThrow()

        assertEquals("Ada Lovelace", repo.fetchProfile(TEST_PUBKY).getOrThrow().displayName)
    }

    // ── Pubky Ring sign-in ───────────────────────────────────────────────

    @Test
    fun aFailedApprovalIsSurfacedAsIsAndTheFlowIsPolledOnce() = runTest {
        // The FFI takes its auth flow on the first poll, so polling again could only answer
        // "No auth flow in progress" — replacing the real cause with a bogus one (#59).
        val relayDown = PubkyError(
            "Auth approval failed: Request failed: HTTP transport error: error sending " +
                "request for url (https://httprelay.pubky.app/inbox/j42EOsZz)",
        )
        pubky.approvalResult = Result.failure(relayDown)

        val failure = repo.beginSignIn().getOrThrow().complete().exceptionOrNull()

        assertSame(relayDown, failure)
        assertEquals(expected = 1, actual = pubky.awaitApprovalCalls)
    }

    @Test
    fun anApprovedSignInPersistsTheSession() = runTest {
        pubky.approvalResult = Result.success(
            """{"pubky":"$TEST_PUBKY","capabilities":["/pub/loopky/:rw"],"session_secret":"s3cret"}""",
        )

        val signedIn = repo.beginSignIn().getOrThrow().complete().getOrThrow()

        assertEquals(TEST_PUBKY, signedIn.identity.pubky)
        assertEquals(TEST_PUBKY, store.saved?.identity?.pubky)
    }

    /**
     * The FFI's session payload carries **no `homeserver` field** — the parser defaults it to `""`
     * — so a Ring sign-in used to store a session that could not say which network it was on.
     *
     * Deliberately driven through `beginSignIn().complete()` with a raw payload rather than a
     * hand-built `Session`: a fixture that supplies a homeserver tests the fixture. This is the
     * shape the only flow that mints a session for the headless client actually produces, and the
     * CLI's environment guard compares against exactly this field (#54).
     */
    @Test
    fun anApprovedSignInResolvesTheHomeserverThePayloadOmits() = runTest {
        pubky.approvalResult = Result.success(
            """{"pubky":"$TEST_PUBKY","capabilities":["/pub/loopky/:rw"],"session_secret":"s3cret"}""",
        )
        pubky.homeserverLookups[TEST_PUBKY] = Result.success("homeserverpk")

        val signedIn = repo.beginSignIn().getOrThrow().complete().getOrThrow()

        assertEquals("homeserverpk", signedIn.homeserver)
        assertEquals("homeserverpk", store.saved?.homeserver)
    }

    /** A DHT that will not answer leaves the blank rather than failing a sign-in that worked. */
    @Test
    fun aSignInStillSucceedsWhenTheHomeserverCannotBeResolved() = runTest {
        pubky.approvalResult = Result.success(
            """{"pubky":"$TEST_PUBKY","capabilities":["/pub/loopky/:rw"],"session_secret":"s3cret"}""",
        )

        val signedIn = repo.beginSignIn().getOrThrow().complete().getOrThrow()

        assertEquals("", signedIn.homeserver)
        assertEquals(TEST_PUBKY, store.saved?.identity?.pubky)
    }

    /**
     * The fix has to reach sessions that are **already stored**, not only new ones.
     *
     * Every account signed in through Ring before the backfill — on either app as well as the CLI
     * — has a blank homeserver on disk. Nothing prompts them to sign in again and `session_live`
     * reports the session as healthy, so without this the environment guard stays open for exactly
     * the installs that exist.
     */
    @Test
    fun aStoredSessionWithNoHomeserverIsHealedOnLoad() = runTest {
        store.saved = fakeSession().copy(homeserver = "")
        pubky.homeserverLookups[TEST_PUBKY] = Result.success("homeserverpk")

        val loaded = repo.loadPersistedSession()

        assertEquals("homeserverpk", loaded?.homeserver)
        // Written back, so the DHT is asked once rather than on every load.
        assertEquals("homeserverpk", store.saved?.homeserver)
    }

    @Test
    fun aStoredSessionThatAlreadyKnowsItsHomeserverIsNotLookedUp() = runTest {
        store.saved = fakeSession()

        repo.loadPersistedSession()

        assertEquals(expected = 0, actual = pubky.homeserverLookupCount)
    }

    // ── sign-out: the local half and the remote half ─────────────────────

    /**
     * Clearing locally regardless is right — a user asking to sign out should end up signed out
     * here whatever the network is doing. Reporting that as an unqualified success while the
     * bearer token is still live is not.
     */
    @Test
    fun signOutReportsWhetherTheHomeserverConfirmedRevocation() = runTest {
        store.saved = fakeSession()
        repo.loadPersistedSession()
        pubky.signOutResult = Result.failure(PubkyError("HTTP transport error: error sending request"))

        val outcome = repo.signOut(force = true).getOrThrow()

        assertEquals(false, outcome.revokedRemotely)
        // Local state still goes, which is the half that must not depend on the network.
        assertEquals(null, store.saved)
        assertEquals(null, repo.currentSession())
    }

    @Test
    fun signOutReportsSuccessWhenTheHomeserverAcceptedIt() = runTest {
        store.saved = fakeSession()
        repo.loadPersistedSession()

        assertEquals(true, repo.signOut(force = true).getOrThrow().revokedRemotely)
    }

    /**
     * The only revocation an injected session has. It must not touch the store: on a developer's
     * machine both credentials exist at once, and clearing there would revoke one and wipe the
     * other.
     */
    @Test
    fun revokingAnInjectedSessionLeavesAStoredOneAlone() = runTest {
        store.saved = fakeSession()

        repo.revokeSession("injected-secret").getOrThrow()

        assertEquals(listOf("injected-secret"), pubky.signOuts)
        assertEquals(TEST_PUBKY, store.saved?.identity?.pubky)
    }

    // ── an injected session (LOOPKY_SESSION) ─────────────────────────────

    @Test
    fun adoptingASessionSecretResolvesTheHomeserverToo() = runTest {
        pubky.revalidateResult = Result.success(
            """{"pubky":"$TEST_PUBKY","capabilities":["/pub/loopky/:rw"],"session_secret":"injected"}""",
        )
        pubky.homeserverLookups[TEST_PUBKY] = Result.success("homeserverpk")

        val adopted = repo.adoptSession("injected").getOrThrow()

        assertEquals("homeserverpk", adopted.homeserver)
        assertEquals(listOf("injected"), pubky.revalidatedSecrets)
    }

    /**
     * An injected session belongs to whoever injected it, for this process only. Persisting it
     * would leave a container's credential on a machine whose own stored session it stood in for.
     */
    @Test
    fun adoptingASessionDoesNotWriteItToDisk() = runTest {
        pubky.revalidateResult = Result.success(
            """{"pubky":"$TEST_PUBKY","capabilities":["/pub/loopky/:rw"],"session_secret":"injected"}""",
        )

        repo.adoptSession("injected").getOrThrow()

        assertEquals(null, store.saved)
        assertEquals(TEST_PUBKY, repo.currentSession()?.identity?.pubky)
    }

    // ── the loopky-user self-tag ─────────────────────────────────────────

    @Test
    fun restoringASessionAnnouncesTheAccountAsALoopkyUser() = runTest {
        // Without this the account is invisible to the only global directory Loopky has (#40).
        store.saved = fakeSession()

        repo.loadPersistedSession()

        assertEquals(listOf(profileUri to ReservedTags.USER), tags.putReservedTags)
    }

    @Test
    fun theSelfTagIsWrittenOnceNotOnEveryCall() = runTest {
        store.saved = fakeSession()
        publishProfile("Ada")

        repo.loadPersistedSession()
        repo.loadPersistedSession()
        repo.updateProfile(name = "Ada Lovelace", bio = null).getOrThrow()

        // The record is content-addressed, so repeats would overwrite rather than duplicate — but
        // there is no reason to spend the round-trips.
        assertEquals(expected = 1, actual = tags.putReservedTags.size)
    }

    @Test
    fun updatingTheProfileAnnouncesTheAccountIfLoginCouldNot() = runTest {
        publishProfile("Ada")

        repo.updateProfile(name = "Ada Lovelace", bio = null).getOrThrow()

        assertEquals(listOf(profileUri to ReservedTags.USER), tags.putReservedTags)
    }

    /**
     * The self-tag's subject is a *profile*, so the record goes to `/pub/pubky.app/tags/` — which
     * a headless client scoped to `/pub/loopky/:rw` was never granted (#54). Skipped rather than
     * attempted-and-failed: firing it anyway buys a doomed round trip on every command plus a
     * warning about the scope working exactly as designed.
     */
    @Test
    fun aSessionWithoutThePubkyAppCapabilityDoesNotSelfTag() = runTest {
        store.saved = fakeSession().copy(capabilities = listOf(Capability("/pub/loopky/:rw")))

        assertEquals(TEST_PUBKY, repo.loadPersistedSession()?.identity?.pubky)

        assertTrue(tags.putReservedTags.isEmpty(), "wrote ${tags.putReservedTags}")
    }

    @Test
    fun aFailedSelfTagDoesNotFailTheCallThatTriggeredIt() = runTest {
        tags.failWith = IllegalStateException("indexer write failed")
        store.saved = fakeSession()

        assertEquals(TEST_PUBKY, repo.loadPersistedSession()?.identity?.pubky)
        assertTrue(repo.updateProfile(name = "Ada", bio = null).isSuccess)
    }

    @Test
    fun updatingTheProfileKeepsAPictureTheSessionNeverLearnedAbout() = runTest {
        // Sign-in only enriches the session when the profile has a name or a bio, so an account
        // with a picture and neither reaches updateProfile with avatarUrl = null. Echoing that
        // back used to erase the avatar the first time the user renamed themselves.
        val image = "pubky://$TEST_PUBKY/pub/pubky.app/files/0035JHD6154X0"
        pubky.store[PubkyPaths.profile(TEST_PUBKY)] = """{"name":null,"bio":null,"image":"$image"}"""

        val updated = repo.updateProfile(name = "Ada Lovelace", bio = null).getOrThrow()

        assertEquals(image, updated.avatarUrl)
        assertEquals(image, repo.fetchProfile(TEST_PUBKY, forceRefresh = true).getOrThrow().avatarUrl)
    }

    @Test
    fun signingOutForgetsCachedProfiles() = runTest {
        publishProfile("Ada")
        repo.fetchProfile(TEST_PUBKY)

        repo.signOut().getOrThrow()
        pubky.failGetWith = IllegalStateException("offline")

        assertTrue(repo.fetchProfile(TEST_PUBKY).isFailure)
    }
}

private class RecordingSessionStore : SecureSessionStore {
    override val location: String = "in memory"
    var saved: Session? = null

    override suspend fun save(session: Session) {
        saved = session
    }

    override suspend fun load(): Session? = saved

    override suspend fun clear() {
        saved = null
    }
}
