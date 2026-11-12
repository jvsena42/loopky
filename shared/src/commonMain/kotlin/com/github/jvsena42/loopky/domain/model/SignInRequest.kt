package com.github.jvsena42.loopky.domain.model

/**
 * Another app's `pubkyauth://` request, read back from the link, that a key Loopky holds can
 * approve: the `loopky` CLI's login, the Claude connector's sign-in page, any Pubky app.
 *
 * [link] carries the relay secret the approval travels over, so it is excluded from [toString] and
 * must never be logged, searched or posted.
 */
class SignInRequest(
    val link: String,
    /** The `cid` a grant request names itself by (`mcp.loopky.app`); null on a legacy link. */
    val clientId: String?,
    val access: List<RequestedAccess>,
) {
    /** True when anything asked for lies outside Loopky's own `/pub/loopky/` folder. */
    val reachesBeyondLoopky: Boolean get() = access.any { !it.isLoopkyOnly }

    override fun equals(other: Any?): Boolean =
        other is SignInRequest && other.link == link && other.clientId == clientId && other.access == access

    override fun hashCode(): Int = link.hashCode()

    override fun toString(): String = "SignInRequest(clientId=$clientId, access=$access)"
}

/** One path a request asks for, and whether it asks to write there. */
data class RequestedAccess(val path: String, val canWrite: Boolean) {
    val isLoopkyOnly: Boolean get() = path.startsWith(LOOPKY_PATH)

    companion object {
        private const val LOOPKY_PATH = "/pub/loopky/"
    }
}

/**
 * Why Loopky will not approve a link. PascalCase, because the entries cross to Swift lowercased
 * with nothing between the words.
 */
enum class SignInRefusal {
    /** Not a `pubkyauth://` request, or one the SDK cannot read. */
    NotASignInLink,

    /** A signup link: it asks the signer to create an account, which Loopky does not do for others. */
    CreatesAccount,

    /**
     * The request asks for the root capability — every path, and the power to list and revoke
     * grants. Loopky refuses it outright rather than leaving one tap between a phishing QR and the
     * whole account.
     */
    WholeAccount,

    /** No key on this device can sign: Pubky Ring holds it, or nobody is signed in. */
    NoKeyHere,
}

class SignInRefusedException(val reason: SignInRefusal) : IllegalStateException("Sign-in refused: $reason")

/** An app the account has approved, as the homeserver lists it. Times are Unix seconds. */
data class AuthorizedSession(
    val grantId: String,
    val clientId: String,
    val access: List<RequestedAccess>,
    val issuedAt: Long,
    val expiresAt: Long,
    /** The grant this device's own session was minted from; revoking it would sign Loopky out. */
    val isThisDevice: Boolean,
)
