package com.github.jvsena42.loopky.mcp.tenant

/**
 * Why [TenantPool] did not run a call. Each is a distinct answer for the caller: the first three
 * are worth a retry later, the last two are not.
 */
sealed class TenantRefusal(message: String) : RuntimeException(message) {

    /** Another call for the same user is still running, and this one waited its bound. */
    class UserBusy(pubky: String) :
        TenantRefusal("Another operation for $pubky is still running; one at a time per account. Retry when it finishes.")

    /** The server is at its concurrency cap. */
    class ServerBusy : TenantRefusal("The server is at capacity; retry shortly.")

    /** The call outlived its bound. What it had written stays written. */
    class TimedOut(limitSeconds: Long) :
        TenantRefusal("The operation took longer than ${limitSeconds}s and was stopped. What it had written stays written.")

    /** No credential is linked for this account: the user has to connect the connector again. */
    class NotLinked(pubky: String) :
        TenantRefusal("No Loopky session is linked for $pubky. Reconnect the connector.")

    /** The homeserver refused the credential outright; it has been reported revoked. */
    class Revoked(pubky: String) :
        TenantRefusal("The Loopky session for $pubky was revoked. Reconnect the connector; retrying cannot help.")

    /** Writes for this account came faster than the per-user bucket refills. */
    class RateLimited(retryAfterSeconds: Long) :
        TenantRefusal("Too many writes for this account; retry in ${retryAfterSeconds}s.")

    /** Today's inline-content budget for this account is spent; it resets at 00:00 UTC. */
    class OverBudget(remainingBytes: Long) :
        TenantRefusal("Today's upload budget for this account is spent ($remainingBytes bytes left); it resets at 00:00 UTC.")
}
