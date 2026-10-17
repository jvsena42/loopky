package com.github.jvsena42.loopky.data.pubky

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.pubkycore.auth as ffiAuth
import uniffi.pubkycore.awaitAuthApproval as ffiAwaitAuthApproval
import uniffi.pubkycore.cancelGrantAuthFlowHandle as ffiCancelGrantAuthFlowHandle
import uniffi.pubkycore.configureGrantAuthFlows as ffiConfigureGrantAuthFlows
import uniffi.pubkycore.configureMaxBodyBytes as ffiConfigureMaxBodyBytes
import uniffi.pubkycore.configureSessionCache as ffiConfigureSessionCache
import uniffi.pubkycore.createRecoveryFile as ffiCreateRecoveryFile
import uniffi.pubkycore.createTagId as ffiCreateTagId
import uniffi.pubkycore.decryptRecoveryFile as ffiDecryptRecoveryFile
import uniffi.pubkycore.deleteFile as ffiDeleteFile
import uniffi.pubkycore.deleteWithSession as ffiDeleteWithSession
import uniffi.pubkycore.generateMnemonicPhrase as ffiGenerateMnemonicPhrase
import uniffi.pubkycore.generateMnemonicPhraseAndKeypair as ffiGenerateMnemonicPhraseAndKeypair
import uniffi.pubkycore.generateSecretKey as ffiGenerateSecretKey
import uniffi.pubkycore.get as ffiGet
import uniffi.pubkycore.getBytes as ffiGetBytes
import uniffi.pubkycore.getHomeserver as ffiGetHomeserver
import uniffi.pubkycore.getPublicKeyFromSecretKey as ffiGetPublicKeyFromSecretKey
import uniffi.pubkycore.getSignupToken as ffiGetSignupToken
import uniffi.pubkycore.list as ffiList
import uniffi.pubkycore.mnemonicPhraseToKeypair as ffiMnemonicPhraseToKeypair
import uniffi.pubkycore.parseAuthUrl as ffiParseAuthUrl
import uniffi.pubkycore.pollGrantAuthFlowHandle as ffiPollGrantAuthFlowHandle
import uniffi.pubkycore.publish as ffiPublish
import uniffi.pubkycore.publishHttps as ffiPublishHttps
import uniffi.pubkycore.put as ffiPut
import uniffi.pubkycore.putBytes as ffiPutBytes
import uniffi.pubkycore.putBytesWithSession as ffiPutBytesWithSession
import uniffi.pubkycore.putWithSession as ffiPutWithSession
import uniffi.pubkycore.republishHomeserver as ffiRepublishHomeserver
import uniffi.pubkycore.resolve as ffiResolve
import uniffi.pubkycore.resolveHttps as ffiResolveHttps
import uniffi.pubkycore.revalidateSession as ffiRevalidateSession
import uniffi.pubkycore.revokeOwnGrant as ffiRevokeOwnGrant
import uniffi.pubkycore.signIn as ffiSignIn
import uniffi.pubkycore.signOut as ffiSignOut
import uniffi.pubkycore.signUpCookie as ffiSignUpCookie
import uniffi.pubkycore.startAuthFlow as ffiStartAuthFlow
import uniffi.pubkycore.startGrantAuthFlowHandle as ffiStartGrantAuthFlowHandle
import uniffi.pubkycore.switchNetwork as ffiSwitchNetwork
import uniffi.pubkycore.validateMnemonicPhrase as ffiValidateMnemonicPhrase

/**
 * Which application the homeserver is being told it is talking to, required by pubky 0.10's
 * `ClientId` on every sign-in, sign-up and secret-key write.
 *
 * The type wants a domain string (its own example is `franky.pubky.app`), non-empty and at most
 * 253 characters — not the Android application id, which is what makes this a constant here
 * rather than something derived from `BuildConfig`. It travels to the homeserver, so treat it as
 * a public, stable name for Loopky and not as a build-variant knob: staging and release identify
 * as the same client on purpose.
 */
const val LOOPKY_CLIENT_ID = "loopky.app"

/** pubky's `ClientId` ceiling — a domain name's. */
private const val MAX_CLIENT_ID_LENGTH = 253

/**
 * JVM implementation of [PubkyClient] delegating to the UniFFI-generated Kotlin bindings in
 * `uniffi.pubkycore`, shared by every target that runs on a JVM with JNA — Android and the
 * desktop `jvm()` target the CLI is built on (#54).
 *
 * One implementation rather than two, because the generated bindings are plain JNA with no
 * Android imports: only *where the native library comes from* differs. Android ships it as a
 * packaged JNI library (`shared/androidMain/jniLibs/<abi>/libpubkycore.so`); the desktop target
 * ships it as a jar resource under JNA's own search layout
 * (`shared/jvmMain/resources/linux-x86-64/libpubkycore.so`), which `Native.load` extracts at
 * runtime. Both end at the same `Native.load("pubkycore")` inside the bindings.
 *
 * The UniFFI surface returns `List<String>` of shape `[status, payload]` where status is
 * `"success"` or `"error"`. [runFfi]/[runFfiSuspend] translate that into [Result].
 *
 * Blocking network calls are routed through [Dispatchers.IO] so callers on the main
 * dispatcher stay responsive.
 *
 * [clientId] is what Ring lists the grant under and what a user revokes by name. The apps and
 * the CLI are one client, [LOOPKY_CLIENT_ID]; a hosted server that holds grants on users' behalf
 * identifies as its own (`mcp.loopky.app`, #214) so that "Loopky for Claude" can be revoked
 * without signing the phone out.
 */
class UniffiPubkyClient(private val clientId: String = LOOPKY_CLIENT_ID) : PubkyClient {

    init {
        // pubky's `ClientId::new` checks exactly these two (empty, and > 253 UTF-8 bytes), no
        // domain syntax; blank is refused here as well, since no real client id is whitespace.
        require(clientId.isNotBlank() && clientId.encodeToByteArray().size <= MAX_CLIENT_ID_LENGTH) {
            "clientId must be non-blank and at most $MAX_CLIENT_ID_LENGTH bytes"
        }
    }

    // --- Keys & mnemonics -----------------------------------------------------
    override fun generateSecretKey() = runFfi { ffiGenerateSecretKey() }
    override fun getPublicKeyFromSecretKey(secretKey: String) =
        runFfi { ffiGetPublicKeyFromSecretKey(secretKey) }

    override fun generateMnemonicPhrase() = runFfi { ffiGenerateMnemonicPhrase() }
    override fun generateMnemonicPhraseAndKeypair() =
        runFfi { ffiGenerateMnemonicPhraseAndKeypair() }

    override fun mnemonicPhraseToKeypair(mnemonicPhrase: String) =
        runFfi { ffiMnemonicPhraseToKeypair(mnemonicPhrase) }

    override fun validateMnemonicPhrase(mnemonicPhrase: String) =
        runFfi { ffiValidateMnemonicPhrase(mnemonicPhrase) }

    // --- Recovery files -------------------------------------------------------
    override fun createRecoveryFile(secretKey: String, passphrase: String) =
        runFfi { ffiCreateRecoveryFile(secretKey, passphrase) }

    override fun decryptRecoveryFile(recoveryFile: String, passphrase: String) =
        runFfi { ffiDecryptRecoveryFile(recoveryFile, passphrase) }

    // --- Auth / sessions ------------------------------------------------------
    override suspend fun signUp(
        secretKey: String,
        homeserver: String,
        signupToken: String?,
    ) = runFfiSuspend { ffiSignUpCookie(secretKey, homeserver, signupToken) }

    override suspend fun getSignupToken(homeserverPubky: String, adminPassword: String) =
        runFfiSuspend { ffiGetSignupToken(homeserverPubky, adminPassword) }

    override suspend fun signIn(secretKey: String) =
        runFfiSuspend { ffiSignIn(secretKey, clientId) }

    override suspend fun signOut(sessionSecret: String) =
        runFfiSuspend { ffiSignOut(sessionSecret) }

    override suspend fun revalidateSession(sessionSecret: String) =
        runFfiSuspend { ffiRevalidateSession(sessionSecret) }

    override suspend fun startAuthFlow(capabilities: String) =
        runFfiSuspend { ffiStartAuthFlow(capabilities, clientId) }

    override suspend fun awaitAuthApproval() = runFfiSuspend { ffiAwaitAuthApproval() }
    override fun parseAuthUrl(url: String) = runFfi { ffiParseAuthUrl(url) }
    override suspend fun auth(url: String, secretKey: String) =
        runFfiSuspend { ffiAuth(url, secretKey) }

    // --- Records (secret-key auth) --------------------------------------------
    override suspend fun publish(recordName: String, recordContent: String, secretKey: String) =
        runFfiSuspend { ffiPublish(recordName, recordContent, secretKey) }

    override suspend fun publishHttps(recordName: String, target: String, secretKey: String) =
        runFfiSuspend { ffiPublishHttps(recordName, target, secretKey) }

    override suspend fun put(url: String, content: String, secretKey: String) =
        runFfiSuspend { ffiPut(url, content, secretKey, clientId) }

    override suspend fun putBytes(url: String, content: ByteArray, secretKey: String) =
        runFfiSuspend { ffiPutBytes(url, content, secretKey, clientId) }

    override suspend fun get(url: String) = runFfiSuspend { ffiGet(url) }
    override suspend fun getBytes(url: String) = runFfiSuspend { ffiGetBytes(url) }
    override suspend fun list(
        url: String,
        cursor: String?,
        reverse: Boolean?,
        limit: UShort?,
        shallow: Boolean?,
    ) = runFfiSuspend { ffiList(url, cursor, reverse, limit, shallow) }

    override suspend fun deleteFile(url: String, secretKey: String) =
        runFfiSuspend { ffiDeleteFile(url, secretKey, clientId) }

    override suspend fun republishHomeserver(secretKey: String, homeserver: String) =
        runFfiSuspend { ffiRepublishHomeserver(secretKey, homeserver) }

    // --- Records (session auth) -----------------------------------------------
    override suspend fun putWithSession(url: String, content: String, sessionSecret: String) =
        runFfiSuspend { ffiPutWithSession(url, content, sessionSecret) }

    override suspend fun putBytesWithSession(
        url: String,
        content: ByteArray,
        sessionSecret: String,
    ) = runFfiSuspend { ffiPutBytesWithSession(url, content, sessionSecret) }

    override suspend fun deleteWithSession(url: String, sessionSecret: String) =
        runFfiSuspend { ffiDeleteWithSession(url, sessionSecret) }

    // --- pubky-app-specs helpers ------------------------------------------------
    override fun createTagId(uri: String, label: String) =
        runFfi { ffiCreateTagId(uri, label) }

    // --- DHT resolution -------------------------------------------------------
    override suspend fun resolve(publicKey: String) = runFfiSuspend { ffiResolve(publicKey) }
    override suspend fun resolveHttps(publicKey: String) =
        runFfiSuspend { ffiResolveHttps(publicKey) }

    override suspend fun getHomeserver(pubky: String) =
        runFfiSuspend { ffiGetHomeserver(pubky) }

    // --- Network --------------------------------------------------------------
    override fun switchNetwork(useTestnet: Boolean) = runFfi { ffiSwitchNetwork(useTestnet) }

    // --- Server-only: many users in one process (#214) -------------------------
    // Not on [PubkyClient]: the apps and the CLI hold one session, and the iOS client has no
    // binding for any of this.

    /** Starts a grant flow under its own id, so concurrent sign-ins never share a slot. JSON: `flow_id`, `authorization_url`. */
    suspend fun startGrantAuthFlowHandle(capabilities: String, relay: String? = null) =
        runFfiSuspend { ffiStartGrantAuthFlowHandle(capabilities, clientId, relay) }

    /** Never blocks. JSON: `{"status":"pending"}`, or `"approved"` with the session — handed out once. */
    suspend fun pollGrantAuthFlowHandle(flowId: String) = runFfiSuspend { ffiPollGrantAuthFlowHandle(flowId) }

    suspend fun cancelGrantAuthFlowHandle(flowId: String) = runFfiSuspend { ffiCancelGrantAuthFlowHandle(flowId) }

    /** Revokes the grant behind a stored secret with a proof of possession; no live bearer needed. */
    suspend fun revokeOwnGrant(sessionSecret: String) = runFfiSuspend { ffiRevokeOwnGrant(sessionSecret) }

    // `require` before `toUInt()`: a negative Int would otherwise wrap to a huge cap, silently.
    fun configureGrantAuthFlows(maxConcurrent: Int, ttlSeconds: Int): Result<String> {
        require(maxConcurrent > 0 && ttlSeconds > 0) { "maxConcurrent and ttlSeconds must be positive" }
        return runFfi { ffiConfigureGrantAuthFlows(maxConcurrent.toUInt(), ttlSeconds.toUInt()) }
    }

    /** How many grant sessions the FFI keeps minted; an LRU, so a busy server sizes it to its tenants. */
    fun configureSessionCache(capacity: Int): Result<String> {
        require(capacity > 0) { "capacity must be positive" }
        return runFfi { ffiConfigureSessionCache(capacity.toUInt()) }
    }

    /**
     * The most bytes one homeserver read may return; the limit then in force comes back. A server
     * sizes it to its concurrency, since every read is buffered whole, several times over.
     */
    fun configureMaxBodyBytes(maxBytes: Long): Long {
        require(maxBytes > 0) { "maxBytes must be positive" }
        return ffiConfigureMaxBodyBytes(maxBytes.toULong()).toLong()
    }

    // --- Helpers --------------------------------------------------------------

    private inline fun runFfi(block: () -> List<String>): Result<String> =
        try {
            block().toResult()
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private suspend inline fun runFfiSuspend(
        crossinline block: () -> List<String>,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            block().toResult()
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * FFI convention from `pubky-core-ffi-fork::utils::create_response_vector`:
     *   `[error.to_string(), data]` → `["false", "<payload>"]` on success,
     *   `["true", "<message>"]` on error.
     *
     * We treat everything that is not the literal `"false"` as an error to stay defensive.
     */
    private fun List<String>.toResult(): Result<String> {
        if (size < 2) return Result.failure(PubkyError("Unexpected FFI response: $this"))
        return when (this[0]) {
            "false" -> Result.success(this[1])
            else -> Result.failure(PubkyError(this[1]))
        }
    }
}
