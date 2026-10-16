package com.github.jvsena42.loopky.mcp.store

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant

class ClientStore(private val db: McpDatabase) {
    suspend fun insert(client: RegisteredClient) {
        db.transaction { c ->
            c.exec(
                "INSERT INTO clients (client_id, client_name, redirect_uris, created_at) VALUES (?, ?, ?, ?)",
                client.clientId,
                client.clientName,
                Json.encodeToString(uris, client.redirectUris),
                client.createdAt,
            )
        }
    }

    suspend fun find(clientId: String): RegisteredClient? = db.transaction { c ->
        c.queryOne("SELECT * FROM clients WHERE client_id = ?", clientId) { row ->
            RegisteredClient(
                clientId = row.getString("client_id"),
                clientName = row.getString("client_name"),
                redirectUris = Json.decodeFromString(uris, row.getString("redirect_uris")),
                createdAt = row.instant("created_at"),
            )
        }
    }

    /**
     * Deletes registrations older than [createdBefore] that never issued a code and own no token
     * family. Registration is free and unauthenticated, so every row it made used to be
     * permanent; a client that has completed a flow is kept whatever its families' state, since
     * a connector keeps its client id and would otherwise be told "unknown client" on the next
     * sign-in. Returns how many went.
     */
    suspend fun deleteUnused(createdBefore: Instant): Int = db.transaction { c ->
        c.exec(
            """
            DELETE FROM clients WHERE created_at < ?
                AND client_id NOT IN (SELECT client_id FROM auth_codes)
                AND client_id NOT IN (SELECT client_id FROM token_families)
            """.trimIndent(),
            createdBefore,
        )
    }

    private companion object {
        val uris = ListSerializer(String.serializer())
    }
}
