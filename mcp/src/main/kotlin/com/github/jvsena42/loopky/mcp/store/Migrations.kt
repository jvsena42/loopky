package com.github.jvsena42.loopky.mcp.store

import java.sql.Connection

/**
 * Versioned in code behind SQLite's `user_version`: a step, once shipped, is never edited — the
 * next change is a new step, or a deployed database silently diverges from a fresh one.
 */
internal object Migrations {
    private val V1 = listOf(
        """
        CREATE TABLE clients (
            client_id TEXT PRIMARY KEY,
            client_name TEXT NOT NULL,
            redirect_uris TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE auth_codes (
            code_hash TEXT PRIMARY KEY,
            client_id TEXT NOT NULL,
            redirect_uri TEXT NOT NULL,
            code_challenge TEXT NOT NULL,
            resource TEXT NOT NULL,
            pubky TEXT NOT NULL,
            expires_at INTEGER NOT NULL,
            used_at INTEGER,
            family_id TEXT
        )
        """,
        """
        CREATE TABLE token_families (
            family_id TEXT PRIMARY KEY,
            pubky TEXT NOT NULL,
            client_id TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            revoked_at INTEGER
        )
        """,
        "CREATE INDEX token_families_pubky ON token_families (pubky)",
        """
        CREATE TABLE access_tokens (
            token_hash TEXT PRIMARY KEY,
            family_id TEXT NOT NULL REFERENCES token_families (family_id) ON DELETE CASCADE,
            pubky TEXT NOT NULL,
            client_id TEXT NOT NULL,
            audience TEXT NOT NULL,
            expires_at INTEGER NOT NULL,
            revoked_at INTEGER
        )
        """,
        "CREATE INDEX access_tokens_family ON access_tokens (family_id)",
        """
        CREATE TABLE refresh_tokens (
            token_hash TEXT PRIMARY KEY,
            family_id TEXT NOT NULL REFERENCES token_families (family_id) ON DELETE CASCADE,
            pubky TEXT NOT NULL,
            client_id TEXT NOT NULL,
            expires_at INTEGER NOT NULL,
            rotated_at INTEGER,
            revoked_at INTEGER
        )
        """,
        "CREATE INDEX refresh_tokens_family ON refresh_tokens (family_id)",
        """
        CREATE TABLE links (
            pubky TEXT PRIMARY KEY,
            credential BLOB NOT NULL,
            key_version INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            last_used_at INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE vault_meta (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            key_version INTEGER NOT NULL,
            key_check BLOB NOT NULL
        )
        """,
    )

    private val steps: List<List<String>> = listOf(V1)

    fun apply(connection: Connection) {
        connection.createStatement().use { statement ->
            val current = statement.executeQuery("PRAGMA user_version").use { it.getInt(1) }
            check(current <= steps.size) { "Database is at schema $current, newer than this build knows (${steps.size})" }
            for (version in current until steps.size) {
                steps[version].forEach { statement.execute(it.trimIndent()) }
                statement.execute("PRAGMA user_version = ${version + 1}")
            }
        }
    }
}
