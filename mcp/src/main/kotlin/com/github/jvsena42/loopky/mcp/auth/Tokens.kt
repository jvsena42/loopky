package com.github.jvsena42.loopky.mcp.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The prefix says what a leaked string is, so a secret scanner and a human can both tell. */
enum class TokenKind(val prefix: String) {
    Access("lk_at_"),
    Refresh("lk_rt_"),
    Code("lk_ac_"),
}

/** 256 random bits, Base64url, behind a [TokenKind] prefix. Opaque: the store knows them only by hash. */
class TokenMinter(private val random: SecureRandom = SecureRandom()) {
    fun mint(kind: TokenKind): String = kind.prefix + opaque()

    fun opaque(bytes: Int = OPAQUE_BYTES): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    private companion object {
        const val OPAQUE_BYTES = 32
    }
}

object TokenHash {
    fun of(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** RFC 7636, S256 only: `plain` is refused at `/authorize` and never reaches here. */
object Pkce {
    private val verifierChars = Regex("^[A-Za-z0-9._~-]{43,128}$")
    private val challengeChars = Regex("^[A-Za-z0-9_-]{43}$")

    fun isValidChallenge(challenge: String): Boolean = challengeChars.matches(challenge)

    fun challengeOf(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))

    fun verify(verifier: String, challenge: String): Boolean =
        verifierChars.matches(verifier) && MessageDigest.isEqual(challengeOf(verifier).encodeToByteArray(), challenge.encodeToByteArray())
}
