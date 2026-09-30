package com.github.jvsena42.loopky.data.pubky

/**
 * Swift-facing mirror of the UniFFI surface. Every method returns the FFI's native
 * `[status, payload]` convention (`["false", data]` on success, `["true", message]` on error)
 * so the Swift implementation stays a dumb pass-through to `pubkycore.swift` — no
 * `kotlin.Result` or suspend interop crosses the Swift boundary (value classes and suspend
 * functions do not survive the ObjC bridge cleanly).
 *
 * [IosPubkyClientAdapter] converts this into the shared [PubkyClient] contract on the Kotlin
 * side, mirroring what `UniffiPubkyClient` does over the generated Kotlin bindings.
 *
 * Binary payloads cross this boundary Base64-encoded ([putBytesBase64]) and are decoded to
 * raw bytes by the Swift side before hitting the FFI, so blobs still land raw on the
 * homeserver.
 */
@Suppress("TooManyFunctions")
interface RawPubkyClient {
    // --- Keys & mnemonics -----------------------------------------------------
    fun generateSecretKey(): List<String>
    fun getPublicKeyFromSecretKey(secretKey: String): List<String>
    fun generateMnemonicPhrase(): List<String>
    fun generateMnemonicPhraseAndKeypair(): List<String>
    fun mnemonicPhraseToKeypair(mnemonicPhrase: String): List<String>
    fun validateMnemonicPhrase(mnemonicPhrase: String): List<String>

    // --- Recovery files -------------------------------------------------------
    fun createRecoveryFile(secretKey: String, passphrase: String): List<String>
    fun decryptRecoveryFile(recoveryFile: String, passphrase: String): List<String>

    // --- Auth / sessions ------------------------------------------------------
    fun signUp(secretKey: String, homeserver: String, signupToken: String?): List<String>
    fun getSignupToken(homeserverPubky: String, adminPassword: String): List<String>
    fun signIn(secretKey: String): List<String>
    fun signOut(sessionSecret: String): List<String>
    fun revalidateSession(sessionSecret: String): List<String>
    fun startAuthFlow(capabilities: String): List<String>
    fun awaitAuthApproval(): List<String>
    fun parseAuthUrl(url: String): List<String>
    fun auth(url: String, secretKey: String): List<String>

    // --- Records (secret-key auth) --------------------------------------------
    fun publish(recordName: String, recordContent: String, secretKey: String): List<String>
    fun publishHttps(recordName: String, target: String, secretKey: String): List<String>
    fun put(url: String, content: String, secretKey: String): List<String>
    fun putBytesBase64(url: String, contentBase64: String, secretKey: String): List<String>
    fun get(url: String): List<String>
    fun getBytes(url: String): List<String>
    fun list(
        url: String,
        cursor: String?,
        reverse: Boolean?,
        limit: Int?,
        shallow: Boolean?,
    ): List<String>

    fun deleteFile(url: String, secretKey: String): List<String>
    fun republishHomeserver(secretKey: String, homeserver: String): List<String>

    // --- Records (session auth) -----------------------------------------------
    fun putWithSession(url: String, content: String, sessionSecret: String): List<String>
    fun putBytesBase64WithSession(
        url: String,
        contentBase64: String,
        sessionSecret: String,
    ): List<String>

    fun deleteWithSession(url: String, sessionSecret: String): List<String>

    // --- pubky-app-specs helpers ------------------------------------------------
    fun createTagId(uri: String, label: String): List<String>

    // --- DHT resolution -------------------------------------------------------
    fun resolve(publicKey: String): List<String>
    fun resolveHttps(publicKey: String): List<String>
    fun getHomeserver(pubky: String): List<String>

    // --- Network --------------------------------------------------------------
    fun switchNetwork(useTestnet: Boolean): List<String>
}
