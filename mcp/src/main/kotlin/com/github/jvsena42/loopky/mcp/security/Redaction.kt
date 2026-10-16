package com.github.jvsena42.loopky.mcp.security

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Strips credentials from anything about to be logged (#486). After the named shapes, any 40+
 * character base64url run is masked too: it is more likely a token, a grant secret or a code than
 * a word, and losing a long identifier from a log line costs less than keeping a credential in it.
 */
object Redaction {
    private const val MASK = "[redacted]"

    private val patterns: List<Pair<Regex, String>> = listOf(
        Regex("pubkyauth://\\S+") to "pubkyauth://$MASK",
        Regex("\\blk_(at|rt|ac)_[A-Za-z0-9_-]+") to "lk_$1_$MASK",
        Regex("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]+") to "$1$MASK",
        Regex("(?i)([?&]?\\b(code|token|refresh_token|access_token|code_verifier|txn|secret|grant_secret)=)[^&\\s\"']+") to "$1$MASK",
    )

    private val longRun = Regex("[A-Za-z0-9_-]{40,}")

    /** A pubky is 52 z-base-32 characters and is the one long run a log line has to keep: it is who, not what. */
    private val pubky = Regex("^[ybndrfg8ejkmcpqxot1uwisza345h769]{52}$")

    fun redact(text: String?): String {
        val explicit = patterns.fold(text.orEmpty()) { acc, (regex, replacement) -> regex.replace(acc, replacement) }
        return longRun.replace(explicit) { if (pubky.matches(it.value)) it.value else MASK }
    }
}

/** The only logger the auth half uses: every message goes through [Redaction] on its way out. */
class RedactingLogger(private val delegate: Logger) {
    constructor(name: String) : this(LoggerFactory.getLogger(name))

    fun info(message: String) = delegate.info(Redaction.redact(message))

    fun warn(message: String, cause: Throwable? = null) = delegate.warn(describe(message, cause))

    fun error(message: String, cause: Throwable? = null) = delegate.error(describe(message, cause))

    /** The exception's class and redacted message, never its stack: a frame carries nothing a message does not. */
    private fun describe(message: String, cause: Throwable?): String = when (cause) {
        null -> Redaction.redact(message)
        else -> Redaction.redact("$message (${cause::class.simpleName}: ${cause.message})")
    }
}
