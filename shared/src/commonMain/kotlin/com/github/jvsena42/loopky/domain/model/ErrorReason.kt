package com.github.jvsena42.loopky.domain.model

/**
 * Why an operation failed, in terms the UI can speak about.
 *
 * ViewModels carry this instead of an exception message: the FFI's text is a developer diagnostic
 * (`HTTP transport error: error sending request for url (https://_pubky.rc3om…)`) and was being
 * rendered verbatim to users. The raw string still goes to `Log.e`.
 */
enum class ErrorReason {
    /** The homeserver could not be reached — no connectivity, DNS, TLS or timeout. */
    Offline,

    /** The stored session is no longer valid; the user has to sign in again. */
    SessionExpired,

    /**
     * The `/session` round trip preceding every authenticated write failed at the transport layer, so
     * nothing was written.
     *
     * Distinct from [Offline]: the failing request is the FFI's own `https://_pubky.<pubky>/session`,
     * and on the runs this was measured on (#165) Nexus reads and a raw TCP connect to the homeserver
     * kept working from the same device — "you're offline" sends the user to fix the one thing
     * demonstrably fine.
     *
     * Distinct from [SessionExpired] in the opposite direction: the homeserver never answered, so
     * **nothing is known** about whether the session is valid. Treating it as an expiry is what
     * `requiresReauth` would act on, signing the user out over a dropped connection.
     */
    SessionUnreachable,

    /** The requested record does not exist (e.g. a deck that was deleted). */
    NotFound,

    /**
     * The homeserver has no account for this pubky.
     *
     * Distinct from [SessionExpired] because the remedies are opposite: one means "sign in again", the
     * other that there is nothing to sign in to yet. Treating it as an expiry signs out a user who was
     * never signed in — discarding the pubky that *is* their identity — and treating it as [NotFound]
     * is how a blocked sign-in came to be reported as "this deck no longer exists".
     *
     * Not derivable from an error string alone: see the note on `toErrorReason`.
     */
    NoHomeserverAccount,

    /** No signed-in session for an operation that requires one. */
    NotSignedIn,

    /** Pubky Ring is not installed, so the sign-in deeplink has nowhere to go. */
    RingNotInstalled,

    /** The Pubky Ring authorisation did not complete. */
    AuthFailed,

    /**
     * Pubky's authorisation relay did not answer, so the approval could never be collected.
     *
     * Distinct from [Offline]: the relay is a different host from the homeserver and fails on its own,
     * so "you're offline" would send the user to check a connection that is fine. Also not
     * [AuthFailed], which blames Pubky Ring for something Ring never saw (#59).
     */
    AuthRelayUnreachable,

    /**
     * The homeserver answered 429: the request was well-formed, and it is rate-limiting us.
     *
     * Distinct from [Offline] for the same reason [AuthRelayUnreachable] is — the homeserver answered,
     * so the connection is fine. Seen deleting a large deck, ~90 records, which trips the limiter even
     * after the retries back off. Unlike [StorageFull] this does fix itself.
     */
    ServerBusy,

    /**
     * The homeserver refused the write because the account is out of storage (507).
     *
     * The odd one out: every other reason either fixes itself or is fixed by signing in again. This
     * one is fixed only by deleting something or paying for more room, and **retrying is the one thing
     * that cannot work** — precisely what the generic "please try again" copy told them to do.
     *
     * Terminal by construction, so background work must stop on it rather than back off: a WorkManager
     * retry chain against a full quota never converges. See #91.
     */
    StorageFull,

    /**
     * We could not ask the DHT whether a pubky has an account, so we do not know.
     *
     * Distinct from [Offline]: pkarr resolution runs over UDP to the mainline DHT, which plenty of
     * carrier and corporate networks drop while HTTP keeps working. On the restore screen that matters
     * doubly — this state must never read as a verdict on the recovery phrase the user just typed.
     *
     * Always paired with a retry. "We couldn't check" is not "there is no account" (#147).
     */
    HomeserverLookupFailed,

    /** Anything we could not classify. */
    Unknown,
    ;

    /**
     * Whether signing in again is a remedy worth *offering* for this failure.
     *
     * Deliberately wider than `requiresReauth`, which decides whether the app may sign someone out on
     * its own and is true for exactly one reason — an expiry the homeserver confirmed. This decides
     * whether a screen puts a "Sign in again" button next to the message, which is the user's call and
     * costs nothing when it turns out not to have been needed.
     *
     * [SessionUnreachable] motivates the distinction (#165). [NoHomeserverAccount] is excluded on
     * purpose — there is nothing to sign in to.
     *
     * A member rather than an extension property because Swift reads it too, and only a member crosses
     * the ObjC bridge as `reason.offersSignIn`.
     */
    val offersSignIn: Boolean
        get() = this == SessionExpired || this == SessionUnreachable || this == NotSignedIn
}
