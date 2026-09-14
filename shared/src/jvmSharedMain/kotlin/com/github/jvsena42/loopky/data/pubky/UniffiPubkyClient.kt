package com.github.jvsena42.loopky.data.pubky

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.pubkycore.auth as ffiAuth
import uniffi.pubkycore.awaitCookieAuthApproval as ffiAwaitCookieAuthApproval
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
import uniffi.pubkycore.signInCookie as ffiSignInCookie
import uniffi.pubkycore.signOut as ffiSignOut
import uniffi.pubkycore.signUpCookie as ffiSignUpCookie
import uniffi.pubkycore.startCookieAuthFlow as ffiStartCookieAuthFlow
import uniffi.pubkycore.switchNetwork as ffiSwitchNetwork
import uniffi.pubkycore.validateMnemonicPhrase as ffiValidateMnemonicPhrase

/**
 * Which application the homeserver is being told it is talking to, required by pubky 0.10's
 * `ClientId` on every sign-in, sign-up and secret-key write.
 *
 * Not on the Ring deeplink flow: that binds to the cookie variant, which predates `ClientId` and
 * takes none — see [PubkyClient.startAuthFlow] for why.
 *
 * The type wants a domain string (its own example is `franky.pubky.app`), non-empty and at most
 * 253 characters — not the Android application id, which is what makes this a constant here
 * rather than something derived from `BuildConfig`. It travels to the homeserver, so treat it as
 * a public, stable name for Loopky and not as a build-variant knob: staging and release identify
 * as the same client on purpose.
 */
private const val LOOPKY_CLIENT_ID = "loopky.app"

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
 */
class UniffiPubkyClient : PubkyClient {

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
        runFfiSuspend { ffiSignInCookie(secretKey) }

    override suspend fun signOut(sessionSecret: String) =
        runFfiSuspend { ffiSignOut(sessionSecret) }

    override suspend fun revalidateSession(sessionSecret: String) =
        runFfiSuspend { ffiRevalidateSession(sessionSecret) }

    override suspend fun startAuthFlow(capabilities: String) =
        runFfiSuspend { ffiStartCookieAuthFlow(capabilities) }

    override suspend fun awaitAuthApproval() = runFfiSuspend { ffiAwaitCookieAuthApproval() }
    override fun parseAuthUrl(url: String) = runFfi { ffiParseAuthUrl(url) }
    override suspend fun auth(url: String, secretKey: String) =
        runFfiSuspend { ffiAuth(url, secretKey) }

    // --- Records (secret-key auth) --------------------------------------------
    override suspend fun publish(recordName: String, recordContent: String, secretKey: String) =
        runFfiSuspend { ffiPublish(recordName, recordContent, secretKey) }

    override suspend fun publishHttps(recordName: String, target: String, secretKey: String) =
        runFfiSuspend { ffiPublishHttps(recordName, target, secretKey) }

    override suspend fun put(url: String, content: String, secretKey: String) =
        runFfiSuspend { ffiPut(url, content, secretKey, LOOPKY_CLIENT_ID) }

    override suspend fun putBytes(url: String, content: ByteArray, secretKey: String) =
        runFfiSuspend { ffiPutBytes(url, content, secretKey, LOOPKY_CLIENT_ID) }

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
        runFfiSuspend { ffiDeleteFile(url, secretKey, LOOPKY_CLIENT_ID) }

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
