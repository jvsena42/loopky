package com.github.jvsena42.loopky.mcp.store

import java.time.Instant

data class RegisteredClient(
    val clientId: String,
    val clientName: String,
    val redirectUris: List<String>,
    val createdAt: Instant,
)

/** A row holds the code's SHA-256, never the code; [familyId] is set when it is spent, for replay revocation. */
data class AuthorizationCode(
    val codeHash: String,
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val resource: String,
    val pubky: String,
    val expiresAt: Instant,
    val usedAt: Instant? = null,
    val familyId: String? = null,
)

data class TokenFamily(
    val familyId: String,
    val pubky: String,
    val clientId: String,
    val createdAt: Instant,
    val revokedAt: Instant? = null,
)

data class AccessTokenRow(
    val tokenHash: String,
    val familyId: String,
    val pubky: String,
    val clientId: String,
    val audience: String,
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
)

data class RefreshTokenRow(
    val tokenHash: String,
    val familyId: String,
    val pubky: String,
    val clientId: String,
    val expiresAt: Instant,
    val rotatedAt: Instant? = null,
    val revokedAt: Instant? = null,
)

/** [credential] is the vault's sealed blob; the plaintext grant never touches the database. */
class Link(
    val pubky: String,
    val credential: ByteArray,
    val keyVersion: Int,
    val createdAt: Instant,
    val lastUsedAt: Instant,
)

class VaultMeta(val keyVersion: Int, val keyCheck: ByteArray)
