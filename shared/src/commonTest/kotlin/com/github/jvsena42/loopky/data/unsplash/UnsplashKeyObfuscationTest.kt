package com.github.jvsena42.loopky.data.unsplash

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * The round trip between `androidApp/build.gradle.kts` and [deobfuscateUnsplashKey].
 *
 * A Gradle script cannot import from `commonMain`, so the salt and the XOR are written out twice.
 * Nothing but this test connects them: change one copy and a release build silently ships an
 * Unsplash key that decodes to garbage, which surfaces as "image search is broken" long after the
 * commit that did it. [obfuscateLikeGradle] below is a deliberate transcription of the build
 * script — keep the two edited together.
 */
class UnsplashKeyObfuscationTest {

    /**
     * Byte-for-byte what `obfuscateUnsplashKey` in `androidApp/build.gradle.kts` does. Duplicated
     * on purpose: a helper shared with the production code would pass no matter how wrong both
     * halves were.
     */
    private fun obfuscateLikeGradle(key: String): String {
        if (key.isEmpty()) return ""
        val salt = "loopky.unsplash.v1".encodeToByteArray()
        val raw = key.encodeToByteArray()
        val salted = ByteArray(raw.size) { i -> (raw[i].toInt() xor salt[i % salt.size].toInt()).toByte() }
        return Base64.encode(salted)
    }

    @Test
    fun aRealisticKeySurvivesTheRoundTrip() {
        // Unsplash access keys are 43 characters of URL-safe base64-ish text.
        val key = "AbCdEf0123456789-_ghIjKlMnOpQrStUvWxYz01234"
        assertEquals(43, key.length, "fixture should match a real key's shape")

        assertEquals(key, deobfuscateUnsplashKey(obfuscateLikeGradle(key)))
    }

    @Test
    fun theObfuscatedFormDoesNotContainThePlaintext() {
        // The entire point: the literal must not be greppable in the dex.
        val key = "AbCdEf0123456789-_ghIjKlMnOpQrStUvWxYz01234"

        assertNotEquals(key, obfuscateLikeGradle(key))
        assertFalse(key in obfuscateLikeGradle(key))
    }

    @Test
    fun aBlankKeyStaysBlankInBothDirections() {
        // `UnsplashClient.hasFallbackKey` reads emptiness as "this build ships no key", so a blank
        // that round-tripped into anything non-empty would turn web search on with a broken key.
        assertEquals("", obfuscateLikeGradle(""))
        assertEquals("", deobfuscateUnsplashKey(""))
        assertEquals("", deobfuscateUnsplashKey("   "))
    }

    @Test
    fun garbageDecodesToNothingRatherThanThrowing() {
        // This runs inside Application.onCreate. A throw here is a build that cannot start.
        assertEquals("", deobfuscateUnsplashKey("not valid base64 !!!"))
    }

    @Test
    fun aKeyLongerThanTheSaltIsStillRecovered() {
        // The salt is 18 bytes and repeats; a key shorter than it never exercises the wrap.
        val long = "x".repeat(200)

        assertEquals(long, deobfuscateUnsplashKey(obfuscateLikeGradle(long)))
    }
}
