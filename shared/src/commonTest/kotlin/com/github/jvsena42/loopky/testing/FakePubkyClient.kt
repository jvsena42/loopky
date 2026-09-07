package com.github.jvsena42.loopky.testing

import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyError
import kotlinx.coroutines.CompletableDeferred
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * In-memory [PubkyClient] test double shared by repository and ViewModel tests.
 *
 * - Text records live in [store] keyed by url. Binary records are stored Base64-encoded
 *   (matching the FFI transport contract: [getBytes] returns a Base64 payload).
 * - [puts], [bytePuts], [deletes] and [listedPrefixes] record calls for assertions.
 * - [list] returns a JSON array of the stored urls under the given prefix.
 * - [createTagId] is deterministic so tests can derive the expected tag path.
 * - Every method no repository under test uses fails with [UnsupportedOperationException].
 */
@OptIn(ExperimentalEncodingApi::class)
class FakePubkyClient : PubkyClient {

    val store = mutableMapOf<String, String>()
    val gets = mutableListOf<String>()
    val puts = mutableListOf<Pair<String, String>>()
    val bytePuts = mutableListOf<Pair<String, ByteArray>>()
    val deletes = mutableListOf<String>()
    val listedPrefixes = mutableListOf<String>()
    val signOuts = mutableListOf<String>()

    /** When set, the next session-authenticated write/delete fails once with this error. */
    var failNextSessionCallWith: Throwable? = null

    /**
     * When set, *every* session-authenticated write/delete fails with this error. For conditions
     * that do not clear on their own — a full storage quota is the one this exists for, where the
     * point is that retrying cannot help.
     */
    var failAllSessionCallsWith: Throwable? = null

    /** Fails the next N session-authenticated writes with 429, as a busy homeserver would. */
    var rateLimitNextCalls: Int = 0

    /** When set, every [list] call fails with this error (simulates an unreachable homeserver). */
    var failListWith: Throwable? = null

    /**
     * When set, only [list] calls whose url contains this fail, as a transport error.
     *
     * Distinct from [failListWith], which takes down every homeserver at once: a fan-out across
     * several authors needs *one* of them unreachable to prove the others still answer.
     */
    var failListWhenUrlContains: String? = null

    /**
     * Hard cap on entries per [list] call, overriding what the caller asked for. For tests that
     * need to force paging with a handful of records rather than hundreds.
     */
    var listPageSize: Int? = null

    /**
     * What the homeserver returns when the caller sends no `limit` — its `DEFAULT_LIST_LIMIT`.
     *
     * Modelling this is the point: the fake used to answer an unpaged call with *everything*,
     * which let a listing that never paged pass every test and then lose whole decks on device.
     */
    var defaultListLimit: Int = 100

    /** The homeserver's `DEFAULT_MAX_LIST_LIMIT`: a larger `limit` is clamped to it. */
    var maxListLimit: Int = 1000

    /**
     * Whether [list] honours `shallow` by collapsing to one entry per first-level child. Set false
     * to model a homeserver that ignores the flag, which the paging fallback has to survive.
     */
    var honoursShallow: Boolean = true

    /** When set, [list] succeeds this many times and fails afterwards, as a mid-listing drop would. */
    var failListAfterPages: Int? = null

    /** Every [list] call, with the arguments it was made with. */
    val listCalls = mutableListOf<ListCall>()

    /** When set, every [get] call fails with this error (simulates an unreachable homeserver). */
    var failGetWith: Throwable? = null

    /**
     * Fail only the reads whose URL contains this — a *transient* failure, not a 404, so callers
     * that distinguish "gone" from "could not read" can be tested on the difference.
     */
    var failGetWhenUrlContains: String? = null

    /** What [startAuthFlow] hands back, and the capabilities it was asked for. */
    var authFlowResult: Result<String> = Result.success("pubkyauth:///?caps=&secret=test")
    val authFlowCapabilities = mutableListOf<String>()

    /**
     * What [awaitAuthApproval] answers, and how often it was asked. The count matters: the real FFI
     * consumes its auth flow on the first poll, so a second call could only ever fail (#59).
     */
    var approvalResult: Result<String> = Result.failure(PubkyError("No auth flow in progress"))
    var awaitApprovalCalls = 0

    /**
     * When set, [putWithSession] parks until it completes. `runTest` runs on one thread, so without
     * a real suspension point inside the write two "concurrent" writers can never interleave and a
     * race test passes vacuously.
     */
    var putGate: CompletableDeferred<Unit>? = null

    override suspend fun putWithSession(
        url: String,
        content: String,
        sessionSecret: String,
    ): Result<String> {
        putGate?.await()
        consumeInjectedFailure()?.let { return Result.failure(it) }
        store[url] = content
        puts.add(url to content)
        return Result.success("ok")
    }

    override suspend fun putBytesWithSession(
        url: String,
        content: ByteArray,
        sessionSecret: String,
    ): Result<String> {
        consumeInjectedFailure()?.let { return Result.failure(it) }
        store[url] = Base64.encode(content)
        bytePuts.add(url to content)
        return Result.success("ok")
    }

    /**
     * Paths that answer 404 to a delete but stay in [store] — a record the sweep never actually
     * removed. Models the case that makes a delete-count useless as a completeness proof.
     */
    val undeletablePaths = mutableSetOf<String>()

    /**
     * Deleting a path that is not there is a 404, as on a real homeserver. This used to succeed
     * silently, which is how a deck whose manifest listed chunk records that were never written — a
     * half-finished import — could be undeletable on device while every delete test passed.
     */
    override suspend fun deleteWithSession(url: String, sessionSecret: String): Result<String> {
        consumeInjectedFailure()?.let { return Result.failure(it) }
        deletes.add(url)
        if (url in undeletablePaths) return Result.failure(PubkyError("not found: $url"))
        if (store.remove(url) == null) return Result.failure(PubkyError("not found: $url"))
        return Result.success("ok")
    }

    override suspend fun get(url: String): Result<String> {
        gets.add(url)
        failGetWith?.let { return Result.failure(it) }
        failGetWhenUrlContains?.let { needle ->
            if (needle in url) {
                return Result.failure(PubkyError("HTTP transport error: error sending request for url ($url)"))
            }
        }
        return store[url]?.let { Result.success(it) }
            ?: Result.failure(PubkyError("not found: $url"))
    }

    override suspend fun getBytes(url: String): Result<String> = get(url)

    override suspend fun list(
        url: String,
        cursor: String?,
        reverse: Boolean?,
        limit: UShort?,
        shallow: Boolean?,
    ): Result<String> {
        listedPrefixes.add(url)
        listCalls.add(ListCall(url, cursor, limit, shallow))
        failListWith?.let { return Result.failure(it) }
        failListWhenUrlContains?.let { needle ->
            if (needle in url) return Result.failure(PubkyError("HTTP transport error: error sending request for url ($url)"))
        }
        failListAfterPages?.let { allowed ->
            if (listCalls.size > allowed) return Result.failure(PubkyError("HTTP transport error: list page failed"))
        }
        // Mirrors the homeserver's own order: collapse first, then cursor, then limit.
        var matches = store.keys.filter { it.startsWith(url) }
        matches = if (shallow == true && honoursShallow) collapseToChildren(url, matches) else matches
        matches = matches.distinct().sorted()
        if (cursor != null) matches = matches.filter { it > cursor }
        // `limit.unwrap_or(DEFAULT_LIST_LIMIT).min(DEFAULT_MAX_LIST_LIMIT)`, as the server does.
        val cap = listPageSize ?: (limit?.toInt() ?: defaultListLimit).coerceAtMost(maxListLimit)
        matches = matches.take(cap)
        return Result.success(matches.joinToString(",", "[", "]") { "\"$it\"" })
    }

    /**
     * One entry per first-level child of [prefix], as the homeserver's `list_shallow` does: a
     * directory keeps its trailing slash, a file directly under the prefix is returned as-is.
     */
    private fun collapseToChildren(prefix: String, paths: List<String>): List<String> =
        paths.map { path ->
            val rest = path.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash == -1) path else prefix + rest.substring(0, slash + 1)
        }

    /** One [list] call, so a test can assert what was asked for and not only which prefix. */
    data class ListCall(
        val url: String,
        val cursor: String?,
        val limit: UShort?,
        val shallow: Boolean?,
    )

    override fun createTagId(uri: String, label: String): Result<String> =
        Result.success("TAGID-" + (uri + label).hashCode().toUInt().toString(16))

    private fun consumeInjectedFailure(): Throwable? {
        failAllSessionCallsWith?.let { return it }
        if (rateLimitNextCalls > 0) {
            rateLimitNextCalls--
            return PubkyError("Request failed: Server responded with an error: 429 Too Many Requests")
        }
        return failNextSessionCallWith?.also { failNextSessionCallWith = null }
    }

    // --- Keys, mnemonics & recovery files --------------------------------------
    //
    // Modelled on the fork rather than invented: derivation is deterministic and one-way,
    // `validateMnemonicPhrase` answers through its *payload* and never fails, and `signUp`/`signIn`
    // return a grant-flow session carrying `grant_secret` and **no** `homeserver` — which is what
    // the real FFI does, and what makes the "fill the homeserver in yourself" bug reproducible.

    /** Phrase handed back by [generateMnemonicPhraseAndKeypair]. Deterministic per instance. */
    var mintedMnemonic: String = VALID_TEST_MNEMONIC

    /** When set, minting fails — the terminal, never-retried entropy failure. */
    var mintFailure: Throwable? = null

    /**
     * Phrases [validateMnemonicPhrase] answers `"true"` for. Anything else answers `"false"`,
     * still as a *success*, exactly as the FFI does.
     */
    val validMnemonics: MutableSet<String> = mutableSetOf(VALID_TEST_MNEMONIC, SECOND_TEST_MNEMONIC)

    /**
     * Forces [generateMnemonicPhraseAndKeypair] *alone* to hand back a key the phrase does not
     * derive, modelling the one failure a user cannot detect: valid-looking material that is wrong.
     */
    var mintedSecretKeyOverride: String? = null

    /**
     * Forces **both** generation and derivation to return this key.
     *
     * This is what a broken seed derivation actually looks like: the fork derives the keypair
     * *from* the mnemonic in both directions, so the two agree with each other and only the bytes
     * themselves give it away. Overriding generation alone would trip the round-trip check first
     * and never reach the degeneracy check at all.
     */
    var secretKeyOverride: String? = null

    /** Per-pubky answers for [getHomeserver]. Absent pubkys fall through to [defaultHomeserverLookup]. */
    val homeserverLookups: MutableMap<String, Result<String>> = mutableMapOf()

    /** Answer for a pubky with no entry in [homeserverLookups]. Defaults to "never registered". */
    var defaultHomeserverLookup: Result<String> = Result.failure(noHomeserverRecord())

    /** Counts DHT lookups, so a test can assert a caller does not ask twice. */
    var homeserverLookupCount = 0

    val signUpCalls = mutableListOf<SignUpCall>()
    val signInCalls = mutableListOf<String>()

    /** When set, [signUp] fails with this instead of registering. */
    var signUpFailure: Throwable? = null

    /**
     * Makes [signUp] return a session for a *different* pubky than the key it was handed —
     * the Pubky Ring behaviour where a failed signup quietly authorises another account.
     */
    var signUpReturnsPubky: String? = null

    /** When set, [signIn] fails with this. */
    var signInFailure: Throwable? = null

    override fun generateSecretKey(): Result<String> =
        Result.success(fakeSecretKeyFor("generated-secret"))

    /**
     * **JSON, like every other key call.** This used to answer a bare pubky, and that single
     * inaccuracy is what let a real bug ship: production read the payload verbatim, so
     * recovery-file sign-in carried `{"public_key":…}` where a pubky belonged and could never
     * succeed, while the suite went green because the fake handed it the one shape that works.
     * A fake that is kinder than the FFI tests nothing.
     */
    override fun getPublicKeyFromSecretKey(secretKey: String): Result<String> =
        Result.success(publicKeyJson(fakePubkyFor(secretKey)))

    override fun generateMnemonicPhrase(): Result<String> =
        mintFailure?.let { Result.failure(it) } ?: Result.success(mintedMnemonic)

    override fun generateMnemonicPhraseAndKeypair(): Result<String> {
        mintFailure?.let { return Result.failure(it) }
        val secret = mintedSecretKeyOverride ?: secretKeyOverride ?: fakeSecretKeyFor(mintedMnemonic)
        return Result.success(keypairJson(secret, mintedMnemonic))
    }

    override fun mnemonicPhraseToKeypair(mnemonicPhrase: String): Result<String> {
        if (mnemonicPhrase !in validMnemonics) {
            return Result.failure(PubkyError("Invalid mnemonic phrase"))
        }
        val secret = secretKeyOverride ?: fakeSecretKeyFor(mnemonicPhrase)
        return Result.success(keypairJson(secret, mnemonic = null))
    }

    // Never a failure, and the answer is the payload — mirroring `validate_mnemonic_phrase`, which
    // returns ["false", "true"] / ["false", "false"]. A caller reading isSuccess validates nothing.
    override fun validateMnemonicPhrase(mnemonicPhrase: String): Result<String> =
        Result.success((mnemonicPhrase in validMnemonics).toString())

    override fun createRecoveryFile(secretKey: String, passphrase: String): Result<String> {
        if (secretKey.isEmpty() || passphrase.isEmpty()) {
            return Result.failure(PubkyError("Secret key and passphrase must not be empty"))
        }
        // Base64 out, as the FFI does — the caller has to decode before writing a file that
        // pubky-app or Pubky Ring can read.
        return Result.success(Base64.encode("$RECOVERY_SPEC_LINE\n$passphrase:$secretKey".encodeToByteArray()))
    }

    override fun decryptRecoveryFile(recoveryFile: String, passphrase: String): Result<String> {
        if (recoveryFile.isEmpty() || passphrase.isEmpty()) {
            return Result.failure(PubkyError("Recovery file and passphrase must not be empty"))
        }
        val decoded = runCatching { Base64.decode(recoveryFile).decodeToString() }.getOrNull()
            ?: return Result.failure(PubkyError("Failed to decode recovery file: invalid base64"))
        val body = decoded.substringAfter('\n', missingDelimiterValue = "")
        val storedPassphrase = body.substringBefore(':', missingDelimiterValue = "")
        if (!decoded.startsWith(RECOVERY_SPEC_LINE) || storedPassphrase != passphrase) {
            return Result.failure(PubkyError("Failed to decrypt recovery file"))
        }
        return Result.success(body.substringAfter(':'))
    }

    override suspend fun signUp(
        secretKey: String,
        homeserver: String,
        signupToken: String?,
    ): Result<String> {
        signUpCalls.add(SignUpCall(secretKey, homeserver, signupToken))
        signUpFailure?.let { return Result.failure(it) }
        val pubky = signUpReturnsPubky ?: fakePubkyFor(secretKey)
        return Result.success(grantSessionJson(pubky))
    }

    override suspend fun getSignupToken(
        homeserverPubky: String,
        adminPassword: String,
    ): Result<String> = unused()

    override suspend fun signIn(secretKey: String): Result<String> {
        signInCalls.add(secretKey)
        signInFailure?.let { return Result.failure(it) }
        return Result.success(grantSessionJson(fakePubkyFor(secretKey)))
    }

    /** What [signOut] answers. A failure models a revoke the homeserver never confirmed. */
    var signOutResult: Result<String> = Result.success("ok")

    override suspend fun signOut(sessionSecret: String): Result<String> {
        signOuts.add(sessionSecret)
        return signOutResult
    }

    /**
     * What [revalidateSession] answers. The session payload, exactly as the FFI returns it — which
     * notably carries **no `homeserver` field**, so a test that hardcodes one here is not testing
     * the shape the real flow produces.
     */
    var revalidateResult: Result<String>? = null
    val revalidatedSecrets = mutableListOf<String>()

    override suspend fun revalidateSession(sessionSecret: String): Result<String> {
        revalidatedSecrets += sessionSecret
        return revalidateResult ?: unused()
    }

    override suspend fun startAuthFlow(capabilities: String): Result<String> {
        authFlowCapabilities.add(capabilities)
        return authFlowResult
    }

    override suspend fun awaitAuthApproval(): Result<String> {
        awaitApprovalCalls++
        return approvalResult
    }

    override fun parseAuthUrl(url: String): Result<String> = unused()
    override suspend fun auth(url: String, secretKey: String): Result<String> = unused()
    override suspend fun publish(
        recordName: String,
        recordContent: String,
        secretKey: String,
    ): Result<String> = unused()

    override suspend fun publishHttps(
        recordName: String,
        target: String,
        secretKey: String,
    ): Result<String> = unused()

    override suspend fun put(url: String, content: String, secretKey: String): Result<String> = unused()
    override suspend fun putBytes(url: String, content: ByteArray, secretKey: String): Result<String> = unused()
    override suspend fun deleteFile(url: String, secretKey: String): Result<String> = unused()
    override suspend fun republishHomeserver(secretKey: String, homeserver: String): Result<String> = unused()
    override suspend fun resolve(publicKey: String): Result<String> = unused()
    override suspend fun resolveHttps(publicKey: String): Result<String> = unused()
    override suspend fun getHomeserver(pubky: String): Result<String> {
        homeserverLookupCount++
        return homeserverLookups[pubky] ?: defaultHomeserverLookup
    }
    override fun switchNetwork(useTestnet: Boolean): Result<String> = unused()

    /** `get_public_key_from_secret_key`'s shape: the public half only, never a `secret_key`. */
    private fun publicKeyJson(pubky: String): String =
        "{\"public_key\":\"$pubky\",\"uri\":\"pubky://$pubky\"}"

    private fun keypairJson(secretKeyHex: String, mnemonic: String?): String {
        val pubky = fakePubkyFor(secretKeyHex)
        val mnemonicField = mnemonic?.let { ",\"mnemonic\":\"$it\"" }.orEmpty()
        return "{\"secret_key\":\"$secretKeyHex\",\"public_key\":\"$pubky\"," +
            "\"uri\":\"pubky://$pubky\"$mnemonicField}"
    }

    /**
     * A grant-flow session, shaped exactly as `session_to_json_with_grant_secret` builds it:
     * `grant_secret` rather than `session_secret`, and **no `homeserver` field at all**. Callers
     * that rely on the payload to tell them which homeserver they landed on get an empty string,
     * which is the real behaviour and the reason local signup has to fill it in itself.
     */
    private fun grantSessionJson(pubky: String): String =
        """{"pubky":"$pubky","capabilities":["/pub/loopky/:rw"],"grant_secret":"grant-${pubky.take(8)}"}"""

    private fun unused(): Nothing =
        throw UnsupportedOperationException("Not used by the code under test")
}

/** A recorded [FakePubkyClient.signUp] call. */
data class SignUpCall(val secretKey: String, val homeserver: String, val signupToken: String?)

/**
 * The error the fork returns for `Ok(None)` — a pubky that has never published a homeserver
 * record. Verbatim, because the classifier that reads it matches on this wording.
 */
fun noHomeserverRecord(): Throwable = PubkyError("No homeserver found for this public key")

/**
 * The error the fork returns when the DHT itself could not be reached.
 *
 * Contains "failed to resolve" on purpose: that substring is in `isNetworkFailure`'s list, so this
 * is the string that proves the ordering in `toErrorReason` puts the specific classifier first.
 */
fun homeserverLookupUnreachable(): Throwable =
    PubkyError("Failed to get homeserver: pkarr: failed to resolve packet for key")

/** Deterministic stand-in for BIP-39 derivation: same phrase in, same 32-byte key out. */
fun fakeSecretKeyFor(seed: String): String {
    var acc = FNV_OFFSET
    return buildString(SECRET_KEY_HEX_LENGTH) {
        repeat(SECRET_KEY_BYTES) { i ->
            for (c in seed) {
                acc = (acc xor c.code.toUInt()) * FNV_PRIME
            }
            acc = (acc xor (i.toUInt() + 1u)) * FNV_PRIME
            append(((acc shr 16) and 0xFFu).toString(16).padStart(2, '0'))
        }
    }
}

/** Deterministic stand-in for the z32 public key. One-way and unique per secret, like the real one. */
fun fakePubkyFor(secretKeyHex: String): String = "pk" + fakeSecretKeyFor("public:$secretKeyHex").take(50)

/** Twelve real BIP-39 words, so tests read like the thing they model. */
const val VALID_TEST_MNEMONIC =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

const val SECOND_TEST_MNEMONIC =
    "legal winner thank year wave sausage worth useful legal winner thank yellow"

private const val RECOVERY_SPEC_LINE = "pubky.org/recovery"
private const val SECRET_KEY_BYTES = 32
private const val SECRET_KEY_HEX_LENGTH = 64
private const val FNV_OFFSET = 2166136261u
private const val FNV_PRIME = 16777619u
