package com.github.jvsena42.loopky.mcp.vault

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class VaultException(message: String) : IllegalStateException(message)

/**
 * AES-256-GCM over a grant credential. The AAD is `[keyVersion] pubky|environment`, so a blob
 * copied onto another user's row, or a staging database restored under production, fails to
 * open rather than handing out the wrong account's credential.
 *
 * Blob layout: one key-version byte, a 96-bit random nonce, then ciphertext and tag.
 */
class CredentialVault(
    private val key: MasterKey,
    val keyVersion: Int,
    private val environment: PubkyEnvironment,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(keyVersion in 1..MAX_KEY_VERSION) { "keyVersion must fit one byte" }
    }

    fun seal(pubky: String, secret: String): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, nonce, pubky)
        return byteArrayOf(keyVersion.toByte()) + nonce + cipher.doFinal(secret.encodeToByteArray())
    }

    fun open(pubky: String, blob: ByteArray): String {
        if (blob.size < NONCE_BYTES + 1 + TAG_BYTES) refuse("Credential blob is truncated")
        if (blob[0].toInt() != keyVersion) refuse("Credential was sealed under key version ${blob[0]}, this is $keyVersion")
        val nonce = blob.copyOfRange(1, 1 + NONCE_BYTES)
        val cipher = cipher(Cipher.DECRYPT_MODE, nonce, pubky)
        return runCatching { cipher.doFinal(blob, 1 + NONCE_BYTES, blob.size - 1 - NONCE_BYTES) }
            .getOrElse { if (it is GeneralSecurityException) refuse("Credential failed to authenticate") else throw it }
            .decodeToString()
    }

    fun rewrap(pubky: String, blob: ByteArray, to: CredentialVault): ByteArray = to.seal(pubky, open(pubky, blob))

    private fun refuse(reason: String): Nothing = throw VaultException(reason)

    private fun cipher(mode: Int, nonce: ByteArray, pubky: String): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(mode, SecretKeySpec(key.bytes, "AES"), GCMParameterSpec(TAG_BYTES * Byte.SIZE_BITS, nonce))
        updateAAD(byteArrayOf(keyVersion.toByte()) + "$pubky|${environment.name}".encodeToByteArray())
    }

    companion object {
        const val MAX_KEY_VERSION = 127
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
    }
}
