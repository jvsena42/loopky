package com.github.jvsena42.loopky.mcp.store

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

class LinkStore(private val db: McpDatabase) {
    /** Keeps `created_at` for a pubky that re-links; the credential and key version are replaced. */
    suspend fun upsert(link: Link) {
        db.transaction { c ->
            c.exec(
                """
                INSERT INTO links (pubky, credential, key_version, created_at, last_used_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (pubky) DO UPDATE SET credential = excluded.credential,
                    key_version = excluded.key_version, last_used_at = excluded.last_used_at
                """.trimIndent(),
                link.pubky,
                link.credential,
                link.keyVersion,
                link.createdAt,
                link.lastUsedAt,
            )
        }
    }

    suspend fun find(pubky: String): Link? = db.transaction { c ->
        c.queryOne("SELECT * FROM links WHERE pubky = ?", pubky, map = ::toLink)
    }

    suspend fun listAll(): List<Link> = db.transaction { c -> c.query("SELECT * FROM links", map = ::toLink) }

    suspend fun delete(pubky: String): Boolean = db.transaction { c ->
        c.exec("DELETE FROM links WHERE pubky = ?", pubky) > 0
    }

    suspend fun touch(pubky: String, now: Instant) {
        db.transaction { c -> c.exec("UPDATE links SET last_used_at = ? WHERE pubky = ?", now, pubky) }
    }

    suspend fun readVaultMeta(): VaultMeta? = db.transaction { c ->
        c.queryOne("SELECT key_version, key_check FROM vault_meta WHERE id = 1") {
            VaultMeta(it.getInt("key_version"), it.getBytes("key_check"))
        }
    }

    suspend fun writeVaultMeta(meta: VaultMeta) {
        db.transaction { c -> c.writeMeta(meta) }
    }

    /**
     * Every credential and the key check move to the new key in one transaction: a rotation that
     * stopped halfway would leave rows the running key cannot open and the new one cannot find.
     */
    suspend fun rewrapAll(meta: VaultMeta, rewrap: (Link) -> ByteArray): Int = db.transaction { c ->
        val links = c.query("SELECT * FROM links", map = ::toLink)
        links.forEach { link ->
            c.exec("UPDATE links SET credential = ?, key_version = ? WHERE pubky = ?", rewrap(link), meta.keyVersion, link.pubky)
        }
        c.writeMeta(meta)
        links.size
    }

    private fun Connection.writeMeta(meta: VaultMeta) {
        exec(
            """
            INSERT INTO vault_meta (id, key_version, key_check) VALUES (1, ?, ?)
            ON CONFLICT (id) DO UPDATE SET key_version = excluded.key_version, key_check = excluded.key_check
            """.trimIndent(),
            meta.keyVersion,
            meta.keyCheck,
        )
    }

    private fun toLink(row: ResultSet) = Link(
        pubky = row.getString("pubky"),
        credential = row.getBytes("credential"),
        keyVersion = row.getInt("key_version"),
        createdAt = row.instant("created_at"),
        lastUsedAt = row.instant("last_used_at"),
    )
}
