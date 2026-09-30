package com.github.jvsena42.loopky.data.pubky

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Bridges the Swift-implemented [RawPubkyClient] (dumb `[status, payload]` pass-through to the
 * UniFFI Swift bindings) into the shared [PubkyClient] contract — the iOS counterpart of
 * `UniffiPubkyClient`. Blocking FFI calls are routed through [Dispatchers.Default]
 * (Dispatchers.IO is not public on Kotlin/Native); binary payloads are Base64-encoded
 * across the Swift boundary and decoded back to raw bytes there.
 */
@OptIn(ExperimentalEncodingApi::class)
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
class IosPubkyClientAdapter(private val raw: RawPubkyClient) : PubkyClient {

    // --- Keys & mnemonics -----------------------------------------------------
    override fun generateSecretKey() = runFfi { raw.generateSecretKey() }
    override fun getPublicKeyFromSecretKey(secretKey: String) =
        runFfi { raw.getPublicKeyFromSecretKey(secretKey) }

    override fun generateMnemonicPhrase() = runFfi { raw.generateMnemonicPhrase() }
    override fun generateMnemonicPhraseAndKeypair() =
        runFfi { raw.generateMnemonicPhraseAndKeypair() }

    override fun mnemonicPhraseToKeypair(mnemonicPhrase: String) =
        runFfi { raw.mnemonicPhraseToKeypair(mnemonicPhrase) }

    override fun validateMnemonicPhrase(mnemonicPhrase: String) =
        runFfi { raw.validateMnemonicPhrase(mnemonicPhrase) }

    // --- Recovery files -------------------------------------------------------
    override fun createRecoveryFile(secretKey: String, passphrase: String) =
        runFfi { raw.createRecoveryFile(secretKey, passphrase) }

    override fun decryptRecoveryFile(recoveryFile: String, passphrase: String) =
        runFfi { raw.decryptRecoveryFile(recoveryFile, passphrase) }

    // --- Auth / sessions ------------------------------------------------------
    override suspend fun signUp(
        secretKey: String,
        homeserver: String,
        signupToken: String?,
    ) = runFfiSuspend { raw.signUp(secretKey, homeserver, signupToken) }

    override suspend fun getSignupToken(homeserverPubky: String, adminPassword: String) =
        runFfiSuspend { raw.getSignupToken(homeserverPubky, adminPassword) }

    override suspend fun signIn(secretKey: String) = runFfiSuspend { raw.signIn(secretKey) }
    override suspend fun signOut(sessionSecret: String) =
        runFfiSuspend { raw.signOut(sessionSecret) }

    override suspend fun revalidateSession(sessionSecret: String) =
        runFfiSuspend { raw.revalidateSession(sessionSecret) }

    override suspend fun startAuthFlow(capabilities: String) =
        runFfiSuspend { raw.startAuthFlow(capabilities) }

    override suspend fun awaitAuthApproval() = runFfiSuspend { raw.awaitAuthApproval() }
    override fun parseAuthUrl(url: String) = runFfi { raw.parseAuthUrl(url) }
    override suspend fun auth(url: String, secretKey: String) =
        runFfiSuspend { raw.auth(url, secretKey) }

    // --- Records (secret-key auth) --------------------------------------------
    override suspend fun publish(recordName: String, recordContent: String, secretKey: String) =
        runFfiSuspend { raw.publish(recordName, recordContent, secretKey) }

    override suspend fun publishHttps(recordName: String, target: String, secretKey: String) =
        runFfiSuspend { raw.publishHttps(recordName, target, secretKey) }

    override suspend fun put(url: String, content: String, secretKey: String) =
        runFfiSuspend { raw.put(url, content, secretKey) }

    override suspend fun putBytes(url: String, content: ByteArray, secretKey: String) =
        runFfiSuspend { raw.putBytesBase64(url, Base64.encode(content), secretKey) }

    override suspend fun get(url: String) = runFfiSuspend { raw.get(url) }
    override suspend fun getBytes(url: String) = runFfiSuspend { raw.getBytes(url) }

    override suspend fun list(
        url: String,
        cursor: String?,
        reverse: Boolean?,
        limit: UShort?,
        shallow: Boolean?,
    ) = runFfiSuspend { raw.list(url, cursor, reverse, limit?.toInt(), shallow) }

    override suspend fun deleteFile(url: String, secretKey: String) =
        runFfiSuspend { raw.deleteFile(url, secretKey) }

    override suspend fun republishHomeserver(secretKey: String, homeserver: String) =
        runFfiSuspend { raw.republishHomeserver(secretKey, homeserver) }

    // --- Records (session auth) -----------------------------------------------
    override suspend fun putWithSession(url: String, content: String, sessionSecret: String) =
        runFfiSuspend { raw.putWithSession(url, content, sessionSecret) }

    override suspend fun putBytesWithSession(
        url: String,
        content: ByteArray,
        sessionSecret: String,
    ) = runFfiSuspend {
        raw.putBytesBase64WithSession(url, Base64.encode(content), sessionSecret)
    }

    override suspend fun deleteWithSession(url: String, sessionSecret: String) =
        runFfiSuspend { raw.deleteWithSession(url, sessionSecret) }

    // --- pubky-app-specs helpers ------------------------------------------------
    override fun createTagId(uri: String, label: String) =
        runFfi { raw.createTagId(uri, label) }

    // --- DHT resolution -------------------------------------------------------
    override suspend fun resolve(publicKey: String) = runFfiSuspend { raw.resolve(publicKey) }
    override suspend fun resolveHttps(publicKey: String) =
        runFfiSuspend { raw.resolveHttps(publicKey) }

    override suspend fun getHomeserver(pubky: String) =
        runFfiSuspend { raw.getHomeserver(pubky) }

    // --- Network --------------------------------------------------------------
    override fun switchNetwork(useTestnet: Boolean) = runFfi { raw.switchNetwork(useTestnet) }

    // --- Helpers --------------------------------------------------------------
    private inline fun runFfi(block: () -> List<String>): Result<String> =
        try {
            block().toResult()
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private suspend inline fun runFfiSuspend(
        crossinline block: () -> List<String>,
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            block().toResult()
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Same `[error, data]` convention as `UniffiPubkyClient` — see that class for details. */
    private fun List<String>.toResult(): Result<String> {
        if (size < 2) return Result.failure(PubkyError("Unexpected FFI response: $this"))
        return when (this[0]) {
            "false" -> Result.success(this[1])
            else -> Result.failure(PubkyError(this[1]))
        }
    }
}
