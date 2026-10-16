package com.github.jvsena42.loopky.mcp.vault

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.mcp.store.McpStore
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CredentialVaultTest {
    private val key = MasterKey.generate()
    private val vault = CredentialVault(key, keyVersion = 1, environment = PubkyEnvironment.Staging)
    private val pubky = "o1gg96ewuojmopcjbz8895478wdtxtzzuxnfjjz8o8e77csa1ngo"
    private val secret = "grant-secret-with-a-private-key-inside"

    @Test
    fun `seals and opens, with a fresh nonce every time`() {
        val a = vault.seal(pubky, secret)
        val b = vault.seal(pubky, secret)
        assertEquals(secret, vault.open(pubky, a))
        assertEquals(secret, vault.open(pubky, b))
        assertNotEquals(a.toList(), b.toList())
        assertEquals(1, a[0].toInt())
        assertTrue(secret !in String(a, Charsets.ISO_8859_1))
    }

    @Test
    fun `a flipped byte anywhere fails to authenticate`() {
        val blob = vault.seal(pubky, secret)
        for (index in 1 until blob.size) {
            val tampered = blob.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertFailsWith<VaultException>("byte $index") { vault.open(pubky, tampered) }
        }
    }

    @Test
    fun `the AAD binds the blob to the pubky, the environment and the key version`() {
        val blob = vault.seal(pubky, secret)
        assertFailsWith<VaultException> { vault.open("8um71us3fyw6h8wbcxb5ar3rwusy1a6u49956ikzojg3gcwd1dty", blob) }
        val production = CredentialVault(key, keyVersion = 1, environment = PubkyEnvironment.Production)
        assertFailsWith<VaultException> { production.open(pubky, blob) }
        val version2 = CredentialVault(key, keyVersion = 2, environment = PubkyEnvironment.Staging)
        assertFailsWith<VaultException> { version2.open(pubky, blob) }
        val relabelled = blob.copyOf().also { it[0] = 2 }
        assertFailsWith<VaultException> { version2.open(pubky, relabelled) }
        assertFailsWith<VaultException> { vault.open(pubky, blob.copyOf(5)) }
    }

    @Test
    fun `another key cannot open it, and rewrap moves it over`() {
        val blob = vault.seal(pubky, secret)
        val next = CredentialVault(MasterKey.generate(), keyVersion = 2, environment = PubkyEnvironment.Staging)
        assertFailsWith<VaultException> { next.open(pubky, blob) }
        val rewrapped = vault.rewrap(pubky, blob, next)
        assertEquals(secret, next.open(pubky, rewrapped))
        assertFailsWith<VaultException> { vault.open(pubky, rewrapped) }
    }

    @Test
    fun `the bootstrap refuses a database sealed under another key`() = runTest {
        McpStore.inMemory().use { store ->
            val opened = VaultBootstrap.open(store, key, PubkyEnvironment.Staging)
            assertEquals(1, opened.keyVersion)
            assertEquals(1, VaultBootstrap.open(store, key, PubkyEnvironment.Staging).keyVersion, "idempotent")
            val wrong = assertFailsWith<MasterKeyException> {
                VaultBootstrap.open(store, MasterKey.generate(), PubkyEnvironment.Staging)
            }
            assertTrue("rotate-key" in wrong.message.orEmpty())
            assertFailsWith<MasterKeyException> { VaultBootstrap.open(store, key, PubkyEnvironment.Production) }
        }
    }

    @Test
    fun `a master key file must exist, be 32 bytes and be private`() {
        val dir = Files.createTempDirectory("mcp-key")
        try {
            assertFailsWith<MasterKeyException> { MasterKey.load(dir.resolve("missing")) }

            val short = dir.resolve("short").also { Files.writeString(it, MasterKey.encode(key).dropLast(4)) }
            Files.setPosixFilePermissions(short, PosixFilePermissions.fromString("rw-------"))
            assertFailsWith<MasterKeyException> { MasterKey.load(short) }

            val garbage = dir.resolve("garbage").also { Files.writeString(it, "not base64 at all!!") }
            Files.setPosixFilePermissions(garbage, PosixFilePermissions.fromString("rw-------"))
            assertFailsWith<MasterKeyException> { MasterKey.load(garbage) }

            val open = dir.resolve("open").also { Files.writeString(it, MasterKey.encode(key)) }
            Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rw-r-----"))
            val refused = assertFailsWith<MasterKeyException> { MasterKey.load(open) }
            assertTrue("chmod 600" in refused.message.orEmpty())

            Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rw-------"))
            assertEquals(key.bytes.toList(), MasterKey.load(open).bytes.toList())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
