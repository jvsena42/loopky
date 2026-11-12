package com.github.jvsena42.loopky.domain.model

/**
 * A `pubkyauth://` sign-in request: what a signer scans to approve a sign-in, such as the QR on
 * the Claude connector's page. Its `secret` opens the relay channel the approval travels through,
 * so the text must never be searched, logged or posted. Loopky approves one only with a key it
 * holds (`SignerRepository`); otherwise only Pubky Ring can.
 */
object SignInLinks {
    private const val SCHEME = "pubkyauth:"
    private val LINK = Regex("""pubkyauth:\S+""", RegexOption.IGNORE_CASE)

    /** Anywhere in [text], in any case: a QR scanner may hand the scheme back uppercased. */
    fun isIn(text: String): Boolean = text.contains(SCHEME, ignoreCase = true)

    /**
     * The first link in [text], up to the next whitespace and without the sentence punctuation a
     * pasted message ends it with, with the scheme lowercased so the SDK reads it. Null when there
     * is none.
     */
    fun extract(text: String): String? {
        val match = LINK.find(text)?.value?.trimEnd { it in TRAILING } ?: return null
        return SCHEME + match.substring(SCHEME.length)
    }

    private val TRAILING = setOf('.', ',', ';', ')', '>', '"', '\'')
}
