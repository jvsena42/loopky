package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.mcp.vault.MasterKey
import com.github.jvsena42.loopky.mcp.vault.MasterKeyException
import com.github.jvsena42.loopky.mcp.vault.VaultBootstrap
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminCommandsTest {
    @Test
    fun `revoke-all revokes every grant, deletes the links and wipes the tokens`() = oauthTest {
        val clientId = registerClient()
        val tokens = tokensFor(clientId)
        val other = "8um71us3fyw6h8wbcxb5ar3rwusy1a6u49956ikzojg3gcwd1dty"
        exchange(codeFor(clientId, verifier = VERIFIER + "-b", pubky = other, secret = "other-secret"), clientId, VERIFIER + "-b")

        val out = mutableListOf<String>()
        val exit = runAdminCommand(listOf("revoke-all"), harness.config, ring, out::add)
        assertEquals(0, exit, out.toString())
        assertEquals(setOf(SESSION_SECRET, "other-secret"), ring.revoked.toSet())
        assertTrue(harness.store.links.listAll().isEmpty())
        assertNull(server.verifier.verify(tokens.access))
        assertEquals("Revoked 2 of 2 grants; every token is wiped.", out.first())
    }

    @Test
    fun `revoke-all keeps a link whose revoke failed so a rerun retries it, and exits 1`() = oauthTest {
        val clientId = registerClient()
        tokensFor(clientId)
        ring.revokeResult = Result.failure(IllegalStateException("homeserver down"))
        val out = mutableListOf<String>()
        assertEquals(1, runAdminCommand(listOf("revoke-all"), harness.config, ring, out::add))
        assertNotNull(harness.store.links.find(PUBKY))
        assertTrue(out.any { PUBKY in it })

        ring.revokeResult = Result.success(Unit)
        assertEquals(0, runAdminCommand(listOf("revoke-all"), harness.config, ring, out::add))
        assertNull(harness.store.links.find(PUBKY))
    }

    @Test
    fun `rotate-key re-wraps every credential and the old key is refused afterwards`() = oauthTest {
        val clientId = registerClient()
        tokensFor(clientId)
        val newKey = MasterKey.generate()
        val newKeyFile = harness.dir.resolve("new.key").also { OAuthHarness.writeKey(it, newKey) }

        val out = mutableListOf<String>()
        assertEquals(0, runAdminCommand(listOf("rotate-key", newKeyFile.toString()), harness.config, ring, out::add))
        assertTrue(out.first().startsWith("Re-wrapped 1 credentials under key version 2"), out.toString())

        assertFailsWith<MasterKeyException> { runBlocking { VaultBootstrap.open(harness.store, harness.key, harness.config.environment) } }
        val rotated = runBlocking { VaultBootstrap.open(harness.store, newKey, harness.config.environment) }
        assertEquals(2, rotated.keyVersion)
        val link = assertNotNull(harness.store.links.find(PUBKY))
        assertEquals(2, link.keyVersion)
        assertEquals(SESSION_SECRET, rotated.open(PUBKY, link.credential))
    }

    @Test
    fun `an unknown command prints usage and exits 2`() = oauthTest {
        val out = mutableListOf<String>()
        assertEquals(2, runAdminCommand(listOf("drop-tables"), harness.config, ring, out::add))
        assertEquals(2, runAdminCommand(listOf("rotate-key"), harness.config, ring, out::add))
        assertTrue(out.all { it.startsWith("usage:") })
        assertTrue(Files.exists(harness.config.dataDir.resolve("loopky-mcp.db")))
    }
}
