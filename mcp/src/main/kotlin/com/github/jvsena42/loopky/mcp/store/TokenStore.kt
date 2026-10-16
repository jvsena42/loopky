package com.github.jvsena42.loopky.mcp.store

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

sealed interface Rotation {
    data class Rotated(val previous: RefreshTokenRow) : Rotation
    data object Unknown : Rotation
    data object Revoked : Rotation

    /** The token had already been rotated: a replay, so the whole family is now revoked. */
    data class Reused(val familyId: String, val pubky: String) : Rotation
    data class Expired(val familyId: String, val pubky: String) : Rotation
}

class TokenStore(private val db: McpDatabase) {
    suspend fun issue(family: TokenFamily, access: AccessTokenRow, refresh: RefreshTokenRow) {
        db.transaction { c ->
            c.exec(
                "INSERT INTO token_families (family_id, pubky, client_id, created_at) VALUES (?, ?, ?, ?)",
                family.familyId,
                family.pubky,
                family.clientId,
                family.createdAt,
            )
            c.insertPair(access, refresh)
        }
    }

    suspend fun findAccess(tokenHash: String): AccessTokenRow? = db.transaction { c ->
        c.queryOne("SELECT * FROM access_tokens WHERE token_hash = ?", tokenHash, map = ::toAccess)
    }

    suspend fun findRefresh(tokenHash: String): RefreshTokenRow? = db.transaction { c ->
        c.queryOne("SELECT * FROM refresh_tokens WHERE token_hash = ?", tokenHash, map = ::toRefresh)
    }

    /**
     * The lookup, the reuse check and the replacement are one transaction, so two concurrent
     * refreshes with the same token cannot both succeed: the second sees `rotated_at` set.
     */
    suspend fun rotate(
        tokenHash: String,
        now: Instant,
        mint: (RefreshTokenRow) -> Pair<AccessTokenRow, RefreshTokenRow>,
    ): Rotation = db.transaction { c ->
        val row = c.queryOne("SELECT * FROM refresh_tokens WHERE token_hash = ?", tokenHash, map = ::toRefresh)
        val familyRevoked = row != null &&
            c.queryOne("SELECT revoked_at FROM token_families WHERE family_id = ?", row.familyId) {
                it.instantOrNull("revoked_at")
            } != null
        when {
            row == null -> Rotation.Unknown
            row.revokedAt != null || familyRevoked -> Rotation.Revoked
            row.rotatedAt != null -> {
                c.revokeFamily(row.familyId, now)
                Rotation.Reused(row.familyId, row.pubky)
            }
            !row.expiresAt.isAfter(now) -> {
                c.revokeFamily(row.familyId, now)
                Rotation.Expired(row.familyId, row.pubky)
            }
            else -> {
                c.exec("UPDATE refresh_tokens SET rotated_at = ? WHERE token_hash = ?", now, tokenHash)
                val (access, refresh) = mint(row)
                c.insertPair(access, refresh)
                Rotation.Rotated(row)
            }
        }
    }

    suspend fun revokeFamily(familyId: String, now: Instant) {
        db.transaction { c -> c.revokeFamily(familyId, now) }
    }

    suspend fun revokeAccess(tokenHash: String, now: Instant) {
        db.transaction { c ->
            c.exec("UPDATE access_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL", now, tokenHash)
        }
    }

    suspend fun revokeAllFor(pubky: String, now: Instant) {
        db.transaction { c ->
            c.query("SELECT family_id FROM token_families WHERE pubky = ? AND revoked_at IS NULL", pubky) {
                it.getString("family_id")
            }.forEach { c.revokeFamily(it, now) }
        }
    }

    /** A family is live while it holds an unspent, unexpired refresh token or an unexpired access token. */
    suspend fun hasLiveFamily(pubky: String, now: Instant): Boolean = db.transaction { c ->
        c.hasLiveFamily(pubky, now)
    }

    suspend fun pubkysWithoutLiveFamily(now: Instant, touchedBefore: Instant): List<String> = db.transaction { c ->
        c.query("SELECT pubky FROM links WHERE last_used_at < ?", touchedBefore) { it.getString("pubky") }
            .filterNot { c.hasLiveFamily(it, now) }
    }

    suspend fun wipeAll() {
        db.transaction { c ->
            c.exec("DELETE FROM token_families")
            c.exec("DELETE FROM auth_codes")
        }
    }

    private fun Connection.hasLiveFamily(pubky: String, now: Instant): Boolean {
        val live = queryOne(
            """
            SELECT COUNT(*) AS n FROM token_families f
            WHERE f.pubky = ? AND f.revoked_at IS NULL AND (
                EXISTS (SELECT 1 FROM refresh_tokens r WHERE r.family_id = f.family_id
                        AND r.rotated_at IS NULL AND r.revoked_at IS NULL AND r.expires_at > ?)
                OR EXISTS (SELECT 1 FROM access_tokens a WHERE a.family_id = f.family_id
                        AND a.revoked_at IS NULL AND a.expires_at > ?)
            )
            """.trimIndent(),
            pubky,
            now,
            now,
        ) { it.getInt("n") }
        return (live ?: 0) > 0
    }

    private fun Connection.revokeFamily(familyId: String, now: Instant) {
        exec("UPDATE token_families SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL", now, familyId)
        exec("UPDATE access_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL", now, familyId)
        exec("UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL", now, familyId)
    }

    private fun Connection.insertPair(access: AccessTokenRow, refresh: RefreshTokenRow) {
        exec(
            """
            INSERT INTO access_tokens (token_hash, family_id, pubky, client_id, audience, expires_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            access.tokenHash,
            access.familyId,
            access.pubky,
            access.clientId,
            access.audience,
            access.expiresAt,
        )
        exec(
            "INSERT INTO refresh_tokens (token_hash, family_id, pubky, client_id, expires_at) VALUES (?, ?, ?, ?, ?)",
            refresh.tokenHash,
            refresh.familyId,
            refresh.pubky,
            refresh.clientId,
            refresh.expiresAt,
        )
    }
}

private fun toAccess(row: ResultSet) = AccessTokenRow(
    tokenHash = row.getString("token_hash"),
    familyId = row.getString("family_id"),
    pubky = row.getString("pubky"),
    clientId = row.getString("client_id"),
    audience = row.getString("audience"),
    expiresAt = row.instant("expires_at"),
    revokedAt = row.instantOrNull("revoked_at"),
)

private fun toRefresh(row: ResultSet) = RefreshTokenRow(
    tokenHash = row.getString("token_hash"),
    familyId = row.getString("family_id"),
    pubky = row.getString("pubky"),
    clientId = row.getString("client_id"),
    expiresAt = row.instant("expires_at"),
    rotatedAt = row.instantOrNull("rotated_at"),
    revokedAt = row.instantOrNull("revoked_at"),
)
