package com.github.jvsena42.loopky.domain.model

/**
 * A `pubkyauth://` sign-in request: what Pubky Ring scans to approve a sign-in, such as the QR on
 * the Claude connector's page. Its `secret` opens the relay channel the approval travels through,
 * so the text must never be searched, logged or posted. Loopky cannot approve one; only Ring can.
 */
object SignInLinks {
    private const val SCHEME = "pubkyauth:"

    /** Anywhere in [text], in any case: a QR scanner may hand the scheme back uppercased. */
    fun isIn(text: String): Boolean = text.contains(SCHEME, ignoreCase = true)
}
