package com.github.jvsena42.loopky.mcp.store

import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNull

class McpStoreTest {

    @Test
    fun `wipeAll takes every token with it, on an in-memory store too`() = runBlocking {
        val store = McpStore.inMemory()
        val now = Instant.parse("2026-10-16T12:00:00Z")
        store.tokens.issue(
            TokenFamily("fam", "pubky", "client", now),
            AccessTokenRow("ah", "fam", "pubky", "client", "https://mcp.test/mcp", now.plusSeconds(3600)),
            RefreshTokenRow("rh", "fam", "pubky", "client", now.plusSeconds(86_400)),
        )
        store.tokens.wipeAll()
        assertNull(store.tokens.findAccess("ah"), "an access token survived the wipe")
        assertNull(store.tokens.findRefresh("rh"))
        store.close()
    }
}
