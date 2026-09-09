package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.ErrorReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PubkyErrorsTest {

    @Test
    fun classifiesTheTransportFailureSeenOnDevice() {
        val real = PubkyError(
            "Failed to send list request: Request failed: HTTP transport error: " +
                "error sending request for url (https://_pubky.rc3omrqq/pub/loopky/decks/?)",
        )

        assertEquals(ErrorReason.Offline, real.toErrorReason())
    }

    @Test
    fun classifiesTheAuthRelayFailureSeenOnDevice() {
        val real = PubkyError(
            "Auth approval failed: Request failed: HTTP transport error: error sending " +
                "request for url (https://httprelay.pubky.app/inbox/j42EOsZz)",
        )

        assertEquals(ErrorReason.Offline, real.toErrorReason())
    }

    @Test
    fun classifiesAMissingRecordAsNotFound() {
        assertEquals(ErrorReason.NotFound, PubkyError("not found: pubky://x/pub/loopky").toErrorReason())
    }

    @Test
    fun classifiesAnExpiredSessionAheadOfEverythingElse() {
        // Session expiry must win: it is actionable in a way "offline" is not.
        val expired = PubkyError("session import failed: invalid session")

        assertEquals(ErrorReason.SessionExpired, expired.toErrorReason())
    }

    @Test
    fun anUnreachableSessionImportIsNeitherAnExpiryNorBeingOffline() {
        // Verbatim from three separate device runs (#165). It is not an expiry — the request never
        // arrived, so nothing is known about the session, and reading it as one signed the user out
        // over a dropped connection. Nor is it "you're offline": the failing host is the FFI's own
        // `_pubky.<pubky>`, and while this was live Nexus reads, homeserver.pubky.app and a raw TCP
        // connect to the homeserver all answered from the same device. The publish screen said
        // "check your connection and try again" and that was the only thing it said.
        val unreachable = PubkyError(
            "Failed to import session: Request failed: HTTP transport error: error sending " +
                "request for url (https://_pubky.rc3omrqq/session)",
        )

        assertFalse(unreachable.isSessionExpired())
        assertEquals(ErrorReason.SessionUnreachable, unreachable.toErrorReason())
    }

    @Test
    fun anOrdinaryTransportFailureIsStillOffline() {
        // The narrowness guard. SessionUnreachable claims the device's connection is fine, so it
        // must only fire on the session preamble — a read that fails the same way is the ordinary
        // offline case and has to keep the ordinary copy.
        val read = PubkyError(
            "Failed to send list request: Request failed: HTTP transport error: error sending " +
                "request for url (https://_pubky.rc3omrqq/pub/loopky/decks/?)",
        )

        assertFalse(read.isSessionUnreachable())
        assertEquals(ErrorReason.Offline, read.toErrorReason())
    }

    @Test
    fun aSessionImportThatTheHomeserverAnsweredIsNotUnreachable() {
        // The other half of the guard, and the reason isSessionUnreachable is an AND rather than a
        // prefix match: the fork wraps every import failure in the same words, so a 429 and a 507
        // arrive worded identically to a dead connection and must keep their own reasons.
        assertFalse(
            PubkyError(
                "Failed to import session: Request failed: Server responded with an error: " +
                    "429 Too Many Requests",
            ).isSessionUnreachable(),
        )
        assertFalse(PubkyError("Failed to import session: 507 Insufficient Storage").isSessionUnreachable())
        assertFalse(PubkyError("Failed to import session: invalid session").isSessionUnreachable())
    }

    @Test
    fun aHomeserverFiveHundredOnTheSessionPreambleIsNotAnExpiry() {
        // The failure this classifier exists to keep out. The fork wraps every `restore_session`
        // failure in one prefix, so a homeserver 500 — or a 502 from whatever proxies it — arrives
        // carrying both "session" and "import" and used to read as an expiry. That is not a
        // mislabelling: `signOut` revokes the session on the homeserver and clears the local key,
        // so a five-hundred that would have gone away on its own destroyed working credentials.
        val serverError = PubkyError(
            "Failed to import session: Request failed: Server responded with an error: " +
                "500 Internal Server Error",
        )

        assertFalse(serverError.isSessionExpired())
        assertFalse(serverError.requiresReauth())
        assertEquals(ErrorReason.SessionUnreachable, serverError.toErrorReason())
        // Offered, never taken: the user may sign in again, the app may not do it for them.
        assertTrue(serverError.toErrorReason().offersSignIn)
    }

    @Test
    fun aGatewayErrorInFrontOfTheHomeserverIsNotAnExpiryEither() {
        val badGateway = PubkyError(
            "Failed to import session: Request failed: Server responded with an error: 502 Bad Gateway",
        )

        assertFalse(badGateway.requiresReauth())
        assertEquals(ErrorReason.SessionUnreachable, badGateway.toErrorReason())
    }

    @Test
    fun aRefusedSessionIsAnExpiryEvenWhenTheMessageNamesNoSession() {
        // 401 and 403 are the two statuses the fork itself treats as a rejected session. The write
        // that reports one need not mention the session at all: `with_session` re-imports and
        // re-sends, and the second rejection comes back under the *operation's* wording.
        val refused = PubkyError(
            "Failed to put pubky://rc3omrqq/pub/loopky/decks/d1/manifest.json: Request failed: " +
                "Server responded with an error: 401 Unauthorized",
        )

        assertTrue(refused.isSessionExpired())
        assertEquals(ErrorReason.SessionExpired, refused.toErrorReason())
        assertFalse(refused.isSessionUnreachable())
    }

    @Test
    fun aRateLimitedSessionImportIsServerBusyNotAnExpiry() {
        // Seen on device deleting a deck: it fires one session-authenticated delete per record,
        // the homeserver 429s the session import, and the FFI wraps it in wording carrying both
        // "session" and "import". Reading that as an expiry did more than mislabel it — the retry
        // loop answers an expiry by revalidating, which is itself a homeserver call, so it hit the
        // same rate limit and gave up instead of backing off. The deck stayed put and the user was
        // told their session had expired.
        val limited = PubkyError(
            "Failed to import session: Request failed: Server responded with an error: " +
                "429 Too Many Requests - Rate limit exceeded",
        )

        assertFalse(limited.isSessionExpired())
        // ServerBusy, not Offline: the homeserver answered, so telling the user to check their
        // connection points them at the one thing that is definitely working.
        assertEquals(ErrorReason.ServerBusy, limited.toErrorReason())
    }

    @Test
    fun aQuotaFailureDuringSessionImportIsStorageFullNotAnExpiry() {
        // Same wording trap as the 429, but terminal: routing it through revalidate() would spend
        // a round trip to reach the same answer, and "sign in again" does not free any disk.
        val full = PubkyError("Failed to import session: 507 Insufficient Storage")

        assertFalse(full.isSessionExpired())
        assertEquals(ErrorReason.StorageFull, full.toErrorReason())
    }

    @Test
    fun classifiesTheHomeserverQuotaBodyAsStorageFull() {
        // The body the homeserver actually returns with 507, verbatim.
        val full = PubkyError("Request failed: HTTP status 507: Disk space quota exceeded")

        assertEquals(ErrorReason.StorageFull, full.toErrorReason())
    }

    @Test
    fun classifiesTheStatusLineAloneAsStorageFull() {
        // The storage layer builds its own 507 separately from the pre-flight check, so the
        // wording reaching the FFI need not include the body at all.
        assertEquals(
            ErrorReason.StorageFull,
            PubkyError("Failed to put record: 507 Insufficient Storage").toErrorReason(),
        )
    }

    @Test
    fun doesNotReadAnIdContaining507AsAFullDisk() {
        // Every failure message carries a pubky:// URL and ids are random alphanumerics; a bare
        // "507" substring match would send this user to delete decks over a missing record.
        val notFound = PubkyError("not found: pubky://x/pub/loopky/decks/a507bc/manifest.json")

        assertEquals(ErrorReason.NotFound, notFound.toErrorReason())
    }

    @Test
    fun quotaWinsOverTheTransientClassifiers() {
        // A quota message that also trips isRateLimited must not be retried: the request is not
        // going to succeed after a backoff.
        val ambiguous = PubkyError("rate limit: storage quota exceeded")

        assertEquals(ErrorReason.StorageFull, ambiguous.toErrorReason())
    }

    @Test
    fun fallsBackToUnknownForUnrecognisedText() {
        assertEquals(ErrorReason.Unknown, IllegalStateException("kaboom").toErrorReason())
    }

    @Test
    fun namesAPubkyWithNoHomeserverRecordInsteadOfGuessingAtTheNetwork() {
        // `get_homeserver` turns Ok(None) into an error carrying this exact wording. Without its
        // own classifier it fell through to Unknown, and the sign-in path renders Unknown as
        // "sign-in didn't finish" — a Pubky Ring failure, for a phrase that simply has no account.
        val noRecord = PubkyError("No homeserver found for this public key")

        assertEquals(ErrorReason.NoHomeserverAccount, noRecord.toErrorReason())
    }

    @Test
    fun aDhtResolutionFailureIsNotReportedAsHavingNoAccount() {
        // The other arm of the same FFI call. This one genuinely is a transport failure and must
        // stay one: telling a user their recovery phrase belongs to no account because the DHT was
        // unreachable is a verdict we have no basis for (#147).
        val unreachable = PubkyError("Failed to get homeserver: pkarr: failed to resolve packet for key")

        assertEquals(ErrorReason.Offline, unreachable.toErrorReason())
    }

    @Test
    fun theNoRecordClassifierWinsOverTheTransportOneWhenAMessageCarriesBoth() {
        // The ordering guard. "failed to resolve" is in isNetworkFailure's list, so a message
        // containing both substrings is exactly how the specific answer gets swallowed by the
        // generic one. isNoHomeserverRecord runs first precisely so this cannot happen.
        val both = PubkyError("No homeserver found for this public key (failed to resolve)")

        assertEquals(ErrorReason.NoHomeserverAccount, both.toErrorReason())
    }

    @Test
    fun readsTheStatusOutOfTheAnswerTheHomeserverActuallySends() {
        // Verbatim from an iOS sign-in against a real homeserver (#174). The FFI has no typed
        // error surface, so this prose is the only place the status exists.
        val absent = PubkyError(
            "Request failed: Request failed: Server responded with an error: " +
                "404 Not Found - Not Found",
        )

        assertEquals(404, absent.status)
        assertTrue(absent.isNotFound())
        assertEquals(ErrorReason.NotFound, absent.toErrorReason())
    }

    @Test
    fun carriesNoStatusWhenTheMessageNamesNone() {
        // A transport failure never reached the homeserver, so there is no status to report —
        // and the classifiers must fall back to substrings rather than read one out of the URL.
        val offline = PubkyError(
            "Request failed: HTTP transport error: error sending request for url " +
                "(https://_pubky.rc3omrqq/pub/loopky/decks/404abc/manifest.json)",
        )

        assertNull(offline.status)
        assertEquals(ErrorReason.Offline, offline.toErrorReason())
    }

    @Test
    fun doesNotReadAnIdContaining404AsAMissingRecord() {
        // The same hazard STATUS_507 guards against: ids are random alphanumerics, and a bare
        // "404" substring match would report a live record as absent.
        val unrelated = PubkyError("Failed to put record: pubky://x/pub/loopky/decks/a404bc/manifest.json")

        assertFalse(unrelated.isNotFound())
        assertEquals(ErrorReason.Unknown, unrelated.toErrorReason())
    }

    @Test
    fun aStatusInTheMessageWinsOverABodyThatSaysNotFound() {
        // A server error whose body happens to carry the words is not an absent record, and the
        // difference is the whole point: one is an answer, the other is a failure to get one.
        val serverError = PubkyError(
            "Request failed: Server responded with an error: 500 Internal Server Error - not found",
        )

        assertFalse(serverError.isNotFound())
    }

    @Test
    fun anUnrelatedNotFoundIsStillANotFound() {
        // Proof the new classifier is narrow enough to sit second: it must not capture the
        // ordinary record misses that every deck and profile read produces.
        val deckMiss = PubkyError("Request failed: 404 Not Found - pubky://x/pub/loopky/decks/1/manifest.json")

        assertEquals(ErrorReason.NotFound, deckMiss.toErrorReason())
        assertFalse(deckMiss.isNoHomeserverRecord())
    }
}
