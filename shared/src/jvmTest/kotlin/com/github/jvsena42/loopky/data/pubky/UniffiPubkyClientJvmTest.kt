package com.github.jvsena42.loopky.data.pubky

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the desktop JVM target can actually load `libpubkycore` and call through it.
 *
 * This is the one thing the 1,271 shared tests cannot tell us: they run against
 * `FakePubkyClient` and would pass identically on a machine where the native library is missing,
 * the wrong architecture, or in the wrong resource directory for JNA to find. A `linux-x86-64`
 * `.so` that never loads reports itself as an ordinary transport error at the first homeserver
 * call, hours later and nowhere near the cause.
 *
 * Deliberately offline: mnemonic generation and key derivation are pure, so this asserts the
 * *binding* works without asserting anything about a network.
 *
 * It fails on a host outside the shipped matrix (an x86-64 Mac, ARM64 Windows) — which is the intended
 * signal, not a flake. See `shared/src/jvmMain/resources/` for what ships.
 */
class UniffiPubkyClientJvmTest {

    private val client = UniffiPubkyClient()

    @Test
    fun `generates a twelve word mnemonic`() {
        val phrase = client.generateMnemonicPhrase().getOrThrow()
        assertEquals(MNEMONIC_WORDS, phrase.trim().split(" ").size, "phrase was: $phrase")
    }

    @Test
    fun `derives the same pubky from a mnemonic twice`() {
        val phrase = client.generateMnemonicPhrase().getOrThrow()
        val first = client.mnemonicPhraseToKeypair(phrase).getOrThrow()
        val second = client.mnemonicPhraseToKeypair(phrase).getOrThrow()
        assertEquals(first, second)
        assertTrue(first.isNotBlank())
    }

    /**
     * `validate_mnemonic_phrase` answers the *question*, so an invalid phrase is a successful call
     * carrying `"false"` — not a `Result.failure`. Asserted here rather than assumed, because the
     * whole point of this file is that the JVM binding behaves the way the app's does.
     */
    @Test
    fun `answers false for a phrase that is not a valid mnemonic`() {
        assertEquals("false", client.validateMnemonicPhrase("not a real recovery phrase").getOrThrow())
    }

    @Test
    fun `answers true for a phrase it just generated`() {
        val phrase = client.generateMnemonicPhrase().getOrThrow()
        assertEquals("true", client.validateMnemonicPhrase(phrase).getOrThrow())
    }

    private companion object {
        const val MNEMONIC_WORDS = 12
    }
}
