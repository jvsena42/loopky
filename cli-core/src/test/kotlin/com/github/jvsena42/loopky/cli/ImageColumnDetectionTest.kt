package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.looksLikeImageUrl
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What separates "this file has image columns" from "this file has a third column of prose".
 *
 * The import path falls through to the shared parser when a file fails this, rather than erroring
 * as `card add --from-file` does — a three-column Anki export is ordinary text somebody wants
 * imported, not a malformed image file.
 */
class ImageColumnDetectionTest {

    @Test
    fun `an http address is an image url`() {
        assertTrue("https://example.test/a.jpg".looksLikeImageUrl())
        assertTrue("http://example.test/a.jpg".looksLikeImageUrl())
        assertTrue("HTTPS://EXAMPLE.TEST/A.JPG".looksLikeImageUrl())
    }

    /** The shape a third Anki column actually holds — an example sentence, not an address. */
    @Test
    fun `prose is not`() {
        assertFalse("una manzana roja".looksLikeImageUrl())
        assertFalse("".looksLikeImageUrl())
        assertFalse("example.test/a.jpg".looksLikeImageUrl())
    }

    /**
     * Scheme only, deliberately. This decides "URL or prose", not "does this resolve", and
     * anything stricter starts rejecting addresses that work.
     */
    @Test
    fun `an odd but real address still passes`() {
        assertTrue("https://x.test/a b.jpg?q=1&r=2#frag".looksLikeImageUrl())
    }
}
