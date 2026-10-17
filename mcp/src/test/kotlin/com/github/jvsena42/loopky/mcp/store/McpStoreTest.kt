package com.github.jvsena42.loopky.mcp.store

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
