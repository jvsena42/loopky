package com.github.jvsena42.loopky.data.pubky

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minting and deriving key material, with the validation that has to run on **our** side of the FFI
 * boundary.
 *
 * Where the entropy comes from is already right: `generate_mnemonic` reaches bip39's
 * `Mnemonic::generate_in`, drawing from `rand::thread_rng()` reseeded from the OS, and `rand` panics
 * rather than degrading if the OS refuses entropy — there is no silent userspace fallback.
 *
 * What this file exists for is the one failure nobody can see: key material that is *valid-looking*
 * but wrong. The user cannot detect it, the homeserver accepts it, and the account is orphaned the
 * moment they restore from the phrase they were shown. So every generated key is round-tripped and
 * asserted before it reaches a screen.
 *
 * Four rules, none of which may be relaxed:
 *
 * 1. **Never mint key material outside the FFI.** A scoped `ForbiddenImport` in the detekt config
 *    keeps it that way, and `maxIssues: 0` makes it a build failure.
 * 2. **Fail loudly, never fall back.** A failure is terminal and the screen offers Pubky Ring.
 * 3. **Validate in release too.** Plain `if`s returning [Result.failure], not `require`/`assert` and
 *    not behind a debug flag — release is the only build where a weak key costs someone their account.
 * 4. **Check the payload, not the `Result`.** See [isValidMnemonic].
 */

/** A keypair and, when there is one, the phrase that derives it. Never logged. */
internal data class MintedKeypair(
    val secretKeyHex: String,
    val pubky: String,
    val mnemonic: String?,
)

/**
 * Generated key material failed its own round-trip. [stage] names which check, never a value.
 * Terminal by construction — a second draw from a source that just produced this is not evidence.
 */
internal class WeakKeyMaterial(val stage: String) :
    RuntimeException("Generated key material failed validation at $stage")

/**
 * Mint a new keypair inside the FFI and prove it before returning it. Generation and derivation are
 * two different code paths in the fork, and the phrase we show the user is only worth anything if the
 * second reproduces the first exactly.
 */
internal fun PubkyClient.mintValidatedKeypair(): Result<MintedKeypair> = runCatching {
    // Plain `runCatching`, not `runSuspendCatching`: every call in here is synchronous, so there is
    // no cancellation to swallow.
    val minted = generateMnemonicPhraseAndKeypair().getOrThrow().parseKeypairJson()

    val mnemonic = minted.mnemonic
        ?: throw WeakKeyMaterial("generation returned no mnemonic")

    if (!isValidMnemonic(mnemonic)) {
        throw WeakKeyMaterial("the generated phrase does not validate")
    }

    val derived = mnemonicPhraseToKeypair(mnemonic).getOrThrow().parseKeypairJson()

    // Both halves, not just the pubky. A public key that matches while the secret does not is the
    // shape that would sign in today and fail to restore forever.
    if (derived.secretKeyHex != minted.secretKeyHex) {
        throw WeakKeyMaterial("the phrase derives a different secret key")
    }
    if (derived.pubky != minted.pubky) {
        throw WeakKeyMaterial("the phrase derives a different pubky")
    }

    degenerateSecretReason(minted.secretKeyHex)?.let { throw WeakKeyMaterial(it) }

    minted.copy(mnemonic = mnemonic)
}

/**
 * Derive a keypair from a user-supplied recovery phrase. No degeneracy check, unlike
 * [mintValidatedKeypair]: that catches a broken RNG, and this key did not come from ours — a real key
 * that were somehow degenerate would still be theirs, and refusing it would lock them out to no purpose.
 */
internal fun PubkyClient.keypairFromMnemonic(mnemonic: String): Result<MintedKeypair> {
    val normalised = mnemonic.normaliseMnemonic()
    if (!isValidMnemonic(normalised)) {
        return Result.failure(InvalidMnemonic())
    }
    return mnemonicPhraseToKeypair(normalised)
        .mapCatching { it.parseKeypairJson() }
        .map { it.copy(mnemonic = normalised) }
}

/**
 * Derive the pubky for a bare secret key — the recovery-file path, which has no phrase.
 *
 * **The FFI answers with JSON here**, exactly as for the two mnemonic calls:
 * `get_public_key_from_secret_key` returns `{"public_key":…,"uri":…}`, so the payload has to be parsed
 * rather than trimmed. Taking it verbatim broke recovery-file sign-in outright — the whole JSON object
 * travelled on as the pubky, and the user got "Something went wrong" for a file that decrypted fine.
 *
 * [parseKeypairJson] is not reusable: it demands a `secret_key` field, and this response carries only
 * the public half.
 */
internal fun PubkyClient.keypairFromSecretKey(secretKeyHex: String): Result<MintedKeypair> =
    getPublicKeyFromSecretKey(secretKeyHex).mapCatching { payload ->
        MintedKeypair(secretKeyHex = secretKeyHex, pubky = payload.parsePublicKey(), mnemonic = null)
    }

/** The phrase is not twelve valid BIP-39 words. Distinct from "valid, but no account exists". */
internal class InvalidMnemonic : RuntimeException("Invalid recovery phrase")

/**
 * Whether the FFI considers [mnemonic] a valid BIP-39 phrase.
 *
 * **Reads the payload, not `isSuccess`.** `validate_mnemonic_phrase` never returns an error — it
 * answers `["false", "true"]` or `["false", "false"]`, so `Result.isSuccess` is true for an invalid
 * phrase just as much as a valid one, and a caller checking it would validate nothing while looking
 * like it did.
 */
private fun PubkyClient.isValidMnemonic(mnemonic: String): Boolean =
    validateMnemonicPhrase(mnemonic).getOrNull()?.trim()?.equals("true", ignoreCase = true) == true

/**
 * Collapse the whitespace a pasted or hand-typed phrase arrives with. Also accepts `-`, `_` and `+`,
 * matching how Pubky Ring normalises an imported phrase, so a phrase exported from Ring comes back in.
 */
internal fun String.normaliseMnemonic(): String =
    trim().lowercase().replace(MNEMONIC_SEPARATORS, " ")

private val MNEMONIC_SEPARATORS = Regex("[\\s\\-_+]+")

/**
 * Why [secretKeyHex] cannot have come from a working CSPRNG, or null if it looks fine.
 *
 * Not a strength test — 32 bytes of real entropy cannot be judged by looking at them. These are the
 * two patterns that mean the source returned nothing at all: a zero-filled buffer, and one repeated
 * byte. Both are what a broken or stubbed RNG produces, and both sail through every other check here.
 */
private fun degenerateSecretReason(secretKeyHex: String): String? {
    val hex = secretKeyHex.trim().lowercase()
    if (hex.length != SECRET_KEY_HEX_LENGTH) return "the secret key is not 32 bytes"
    val bytes = hex.chunked(2)
    if (bytes.all { it == "00" }) return "the secret key is all zeroes"
    if (bytes.distinct().size == 1) return "the secret key is a single repeated byte"
    return null
}

private const val SECRET_KEY_HEX_LENGTH = 64

/**
 * Parse the fork's keypair JSON: `{"secret_key", "public_key", "uri", "mnemonic"?}`
 * (`pubky-core-ffi-fork/src/utils.rs::keypair_to_json_string`). `mnemonic` is present only for
 * `generate_mnemonic_phrase_and_keypair`; derivation returns the same object without it.
 */
private fun String.parseKeypairJson(): MintedKeypair {
    val obj = keypairJson.parseToJsonElement(this).jsonObject
    fun field(name: String): String? = obj[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    val secret = field("secret_key") ?: throw WeakKeyMaterial("the FFI returned no secret_key")
    val pubky = field("public_key") ?: throw WeakKeyMaterial("the FFI returned no public_key")
    return MintedKeypair(secretKeyHex = secret, pubky = pubky, mnemonic = field("mnemonic"))
}

/**
 * The `public_key` out of a `{"public_key":…,"uri":…}` FFI response. Falls back to the trimmed payload
 * when it does not parse as an object, so a build against an FFI answering with a bare key still signs
 * in. What must never happen again is the reverse — JSON travelling on as if it were a pubky.
 */
private fun String.parsePublicKey(): String {
    val parsed = runCatching { keypairJson.parseToJsonElement(this).jsonObject }.getOrNull()
        ?: return trim().also {
            if (it.startsWith("{")) throw WeakKeyMaterial("the FFI returned an unreadable public key")
        }
    return parsed["public_key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        ?: throw WeakKeyMaterial("the FFI returned no public_key")
}

private val keypairJson: Json = Json { ignoreUnknownKeys = true }
