package com.github.jvsena42.loopky.data.unsplash

import kotlin.io.encoding.Base64

/**
 * Turns the build-time Unsplash fallback key back into a key.
 *
 * **This is not protection, and must not be described as any.** The app sends the key in a live
 * `Authorization: Client-ID …` header, so the plaintext exists in memory at request time and
 * anyone willing to point a proxy at the app has it in a minute. No client-side transform changes
 * that — not this one, not JNI, not R8, which renames symbols and leaves string constants alone.
 *
 * What it does buy is the removal of the literal from `classes*.dex`, which is what stops the cheap
 * attack: automated APK scanners and `strings | grep`, which is how leaked keys are actually found.
 * The key it protects is a shared 50 req/hr demo key with no account behind it, so the right
 * posture is public-but-not-advertised: keep it on a throwaway Unsplash app and expect to rotate
 * it. A user who wants real headroom saves their own key in Settings, which wins over this one.
 *
 * The transform is deliberately trivial and its inverse lives in `androidApp/build.gradle.kts`,
 * which cannot import from `commonMain`. [OBFUSCATION_SALT] is therefore written out twice, and
 * `UnsplashKeyObfuscationTest` is what stops the two copies drifting apart.
 */
fun deobfuscateUnsplashKey(encoded: String): String {
    if (encoded.isBlank()) return ""
    // Blank on any failure, never a throw: no key is a state UnsplashClient already models
    // (`hasFallbackKey`, `isConfigured`, `UnsplashError.MissingKey`), and image search going quiet
    // is a far better outcome than the app failing to start over a build-time constant.
    return runCatching { Base64.decode(encoded).unsaltedString() }.getOrDefault("")
}

private fun ByteArray.unsaltedString(): String =
    ByteArray(size) { i -> (this[i].toInt() xor OBFUSCATION_SALT[i % OBFUSCATION_SALT.size].toInt()).toByte() }
        .decodeToString()

/**
 * Must stay byte-for-byte identical to `UNSPLASH_OBFUSCATION_SALT` in `androidApp/build.gradle.kts`.
 * Not a secret — it ships in the same APK as the thing it scrambles.
 */
private val OBFUSCATION_SALT = "loopky.unsplash.v1".encodeToByteArray()
