package com.github.jvsena42.loopky.data.pubky

/**
 * Thin wrapper around `pubky-core-ffi-fork`. Each function mirrors a UniFFI-generated primitive and
 * returns a [Result] instead of the raw `List<String>` `[status, payload]` convention.
 *
 * Higher-level domain operations compose these primitives in the repositories layer — do not add
 * deck/card concepts here.
 *
 * A plain interface Koin-binds per platform, not `expect`/`actual`: Android and the desktop `jvm()`
 * target the CLI runs on share [UniffiPubkyClient] (JNA + UniFFI); iOS implements
 * `RawPubkyClient` in Swift (`iosApp/iosApp/Pubky/IosPubkyClient.swift`) and
 * `IosPubkyClientAdapter` wraps it into this contract.
 */
interface PubkyClient {

    // --- Keys & mnemonics -----------------------------------------------------
    fun generateSecretKey(): Result<String>
    fun getPublicKeyFromSecretKey(secretKey: String): Result<String>
    fun generateMnemonicPhrase(): Result<String>
    fun generateMnemonicPhraseAndKeypair(): Result<String>
    fun mnemonicPhraseToKeypair(mnemonicPhrase: String): Result<String>
    fun validateMnemonicPhrase(mnemonicPhrase: String): Result<String>

    // --- Recovery files -------------------------------------------------------
    fun createRecoveryFile(secretKey: String, passphrase: String): Result<String>
    fun decryptRecoveryFile(recoveryFile: String, passphrase: String): Result<String>

    // --- Auth / sessions ------------------------------------------------------

    /**
     * Sign up / sign in with a secret key Loopky holds — the local alternative to the Ring
     * deeplink (#147).
     *
     * [signIn] is grant, verified against staging on 2026-10-16 (sign in, write, read, delete,
     * revalidate, sign out). [signUp] still binds to the cookie variant on the JVM — iOS is already
     * grant for both — because nobody has driven a grant signup against a real account yet. A
     * pubky whose record names a homeserver it has no account on gets the same 404 from either
     * flow (measured 2026-10-16), which is what routes restore to the unregistered-key screen.
     *
     * Mixing the two kinds is safe: `restore_session` sniffs which it was handed, so [signOut],
     * [revalidateSession] and `put_with_session` take either.
     *
     * Upstream marks the cookie flow deprecated, so this is a hold rather than a destination.
     */
    suspend fun signUp(
        secretKey: String,
        homeserver: String,
        signupToken: String?,
    ): Result<String>

    suspend fun getSignupToken(homeserverPubky: String, adminPassword: String): Result<String>
    suspend fun signIn(secretKey: String): Result<String>
    suspend fun signOut(sessionSecret: String): Result<String>
    suspend fun revalidateSession(sessionSecret: String): Result<String>

    /**
     * Pubky Ring-style deeplink flow, minting `pubkyauth://signin_grant?…` (#130).
     *
     * Needs **Ring v2.0 or newer**: older releases answer a grant URL with "Unrecognized format"
     * and the user cannot sign in at all (#321).
     *
     * The approval payload names the secret `grant_secret`, which [parseSessionPayload] aliases;
     * `restore_session` sniffs the token kind, so [signOut], [revalidateSession] and
     * `put_with_session` take it unchanged.
     */
    suspend fun startAuthFlow(capabilities: String): Result<String>
    suspend fun awaitAuthApproval(): Result<String>
    fun parseAuthUrl(url: String): Result<String>
    suspend fun auth(url: String, secretKey: String): Result<String>

    // --- Records (secret-key auth) --------------------------------------------
    suspend fun publish(
        recordName: String,
        recordContent: String,
        secretKey: String,
    ): Result<String>

    suspend fun publishHttps(
        recordName: String,
        target: String,
        secretKey: String,
    ): Result<String>

    suspend fun put(url: String, content: String, secretKey: String): Result<String>

    /** Raw binary PUT — content lands on the homeserver as-is (no Base64 envelope). */
    suspend fun putBytes(url: String, content: ByteArray, secretKey: String): Result<String>

    suspend fun get(url: String): Result<String>

    /** Raw binary GET — the FFI returns the payload Base64-encoded for transport. */
    suspend fun getBytes(url: String): Result<String>

    /**
     * Directory listing with homeserver pagination. All filters are optional; the
     * no-arg form lists everything (legacy behaviour).
     */
    suspend fun list(
        url: String,
        cursor: String? = null,
        reverse: Boolean? = null,
        limit: UShort? = null,
        shallow: Boolean? = null,
    ): Result<String>

    suspend fun deleteFile(url: String, secretKey: String): Result<String>
    suspend fun republishHomeserver(secretKey: String, homeserver: String): Result<String>

    // --- Records (session auth) -----------------------------------------------
    suspend fun putWithSession(
        url: String,
        content: String,
        sessionSecret: String,
    ): Result<String>

    /** Raw binary PUT under session auth — the Pubky Ring flow never exposes the secret key. */
    suspend fun putBytesWithSession(
        url: String,
        content: ByteArray,
        sessionSecret: String,
    ): Result<String>

    suspend fun deleteWithSession(url: String, sessionSecret: String): Result<String>

    // --- pubky-app-specs helpers ------------------------------------------------

    /**
     * Derive a pubky-app-specs tag id (Crockford-base32 of half a blake3 hash of
     * `"$uri:$label"`). [label] must already be sanitized (trimmed, lowercase).
     */
    fun createTagId(uri: String, label: String): Result<String>

    // --- DHT resolution -------------------------------------------------------
    suspend fun resolve(publicKey: String): Result<String>
    suspend fun resolveHttps(publicKey: String): Result<String>
    suspend fun getHomeserver(pubky: String): Result<String>

    // --- Network --------------------------------------------------------------
    fun switchNetwork(useTestnet: Boolean): Result<String>
}

/** Error returned by [PubkyClient] when the FFI replies with `["error", message]`. */
class PubkyError(message: String) : RuntimeException(message) {
    /**
     * The HTTP status the homeserver answered with, when the FFI's message carries one.
     *
     * The FFI has no typed error surface — a homeserver answer reaches us as prose
     * (`"Request failed: Server responded with an error: 404 Not Found - Not Found"`) — so the
     * status is read back out of it once here rather than at each call site. A bare
     * `"404" in message` is the bug [isNotFound] used to have: every failure message carries a
     * `pubky://` URL, and deck and card ids are random alphanumerics.
     *
     * `null` when the message names no status. Classifiers fall back to substrings there, so a miss
     * degrades to today's behaviour rather than to a wrong verdict.
     */
    val status: Int? = STATUS_IN_MESSAGE.find(message)?.groupValues?.get(1)?.toIntOrNull()
}

/**
 * Matches the status in the one shape the homeserver's answers reach us in. Deliberately
 * anchored to the phrase rather than hunting for three digits: an unanchored match would read a
 * status out of the URL in the same message.
 */
private val STATUS_IN_MESSAGE =
    Regex("""responded with an error:\s*(\d{3})""", RegexOption.IGNORE_CASE)
