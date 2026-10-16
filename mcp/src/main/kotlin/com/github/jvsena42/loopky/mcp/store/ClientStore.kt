package com.github.jvsena42.loopky.mcp.store

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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

    private companion object {
        val uris = ListSerializer(String.serializer())
    }
}
