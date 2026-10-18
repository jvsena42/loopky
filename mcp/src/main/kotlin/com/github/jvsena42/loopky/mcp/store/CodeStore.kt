package com.github.jvsena42.loopky.mcp.store

import java.sql.ResultSet
import java.time.Duration
import java.time.Instant

sealed interface CodeConsumption {
    data class Consumed(val code: AuthorizationCode) : CodeConsumption
    data object Unknown : CodeConsumption
    data object Expired : CodeConsumption

    /** Spent once already; [familyId] is what that first use minted, and RFC 6749 §4.1.2 says to revoke it. */
    data class Replayed(val familyId: String?) : CodeConsumption
}

class CodeStore(private val db: McpDatabase) {
    suspend fun insert(code: AuthorizationCode) {
        db.transaction { c ->
            c.exec(
                """
                INSERT INTO auth_codes (code_hash, client_id, redirect_uri, code_challenge, resource, pubky, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                code.codeHash,
                code.clientId,
                code.redirectUri,
                code.codeChallenge,
                code.resource,
                code.pubky,
                code.expiresAt,
            )
        }
    }

    suspend fun find(codeHash: String): AuthorizationCode? = db.transaction { c ->
        c.queryOne("SELECT * FROM auth_codes WHERE code_hash = ?", codeHash, map = ::toCode)
    }

    /** Marks the code spent and records [familyId] as what it bought, in the same transaction as the lookup. */
    suspend fun consume(codeHash: String, now: Instant, familyId: String): CodeConsumption = db.transaction { c ->
        val code = c.queryOne("SELECT * FROM auth_codes WHERE code_hash = ?", codeHash, map = ::toCode)
        when {
            code == null -> CodeConsumption.Unknown
            code.usedAt != null -> CodeConsumption.Replayed(code.familyId)
            !code.expiresAt.isAfter(now) -> CodeConsumption.Expired
            else -> {
                c.exec("UPDATE auth_codes SET used_at = ?, family_id = ? WHERE code_hash = ?", now, familyId, codeHash)
                CodeConsumption.Consumed(code)
            }
        }
    }

    /**
     * Unspent codes go once expired; spent ones are kept for [SPENT_RETENTION] past that. A
     * replay is detected only while the spent row exists, and a code outlives its sixty seconds
     * in the callback URL — browser history, a proxy's access log — so a sweep that dropped it
     * turned a replay from "family revoked" into "unknown code" after the first run.
     */
    suspend fun deleteExpired(now: Instant) {
        db.transaction { c ->
            c.exec("DELETE FROM auth_codes WHERE expires_at < ? AND (used_at IS NULL OR used_at < ?)", now, now.minus(SPENT_RETENTION))
        }
    }

    private companion object {
        val SPENT_RETENTION: Duration = Duration.ofDays(1)
    }

    private fun toCode(row: ResultSet) = AuthorizationCode(
        codeHash = row.getString("code_hash"),
        clientId = row.getString("client_id"),
        redirectUri = row.getString("redirect_uri"),
        codeChallenge = row.getString("code_challenge"),
        resource = row.getString("resource"),
        pubky = row.getString("pubky"),
        expiresAt = row.instant("expires_at"),
        usedAt = row.instantOrNull("used_at"),
        familyId = row.getString("family_id"),
    )
}
