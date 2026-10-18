package com.github.jvsena42.loopky.mcp.store

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class McpStoreTest {

    @Test
    fun `wipeAll takes every token with it, on an in-memory store too`() = runBlocking {
        val store = McpStore.inMemory()
        val now = Instant.parse("2026-10-16T12:00:00Z")
        store.links.upsert(Link("pubky", ByteArray(1), keyVersion = 1, createdAt = now, lastUsedAt = now))
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

    @Test
    fun `no token is stored for an account that is not linked`() = runBlocking {
        // The unlink race (#536): a code exchanged while the account is being unlinked.
        val store = McpStore.inMemory()
        val now = Instant.parse("2026-10-16T12:00:00Z")
        val stored = store.tokens.issue(
            TokenFamily("fam", "pubky", "client", now),
            AccessTokenRow("ah", "fam", "pubky", "client", "https://mcp.test/mcp", now.plusSeconds(3600)),
            RefreshTokenRow("rh", "fam", "pubky", "client", now.plusSeconds(86_400)),
        )
        assertFalse(stored)
        assertNull(store.tokens.findAccess("ah"))
        store.close()
    }

    @Test
    fun `a data directory is held by one store at a time, and freed when it closes`() {
        val dir = Files.createTempDirectory("mcp-store-lock")
        try {
            val first = McpStore.open(dir)
            assertFailsWith<DataDirBusyException> { McpStore.open(dir) }
            first.close()
            McpStore.open(dir).close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
