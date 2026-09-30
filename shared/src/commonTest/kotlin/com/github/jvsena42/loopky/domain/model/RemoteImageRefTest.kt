package com.github.jvsena42.loopky.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteImageRefTest {

    @Test
    fun `https urls are renderable`() {
        assertTrue("https://example.test/cat.jpg".isRenderableImageUrl())
        assertTrue("HTTPS://EXAMPLE.TEST/CAT.JPG".isRenderableImageUrl())
        assertTrue("https://x.test/a.jpg?q=1&r=2#frag".isRenderableImageUrl())
    }

    /**
     * The whole point of the check. Both clients block cleartext, so this is not a preference
     * about tidiness — it is a card whose picture can never appear on any device Loopky ships for.
     */
    @Test
    fun `http urls are not renderable`() {
        assertFalse("http://example.test/cat.jpg".isRenderableImageUrl())
        assertFalse("HTTP://EXAMPLE.TEST/CAT.JPG".isRenderableImageUrl())
    }

    @Test
    fun `a scheme alone is not a url`() {
        assertFalse("https://".isRenderableImageUrl())
    }

    @Test
    fun `prose and bare hosts are not renderable`() {
        assertFalse("una manzana roja".isRenderableImageUrl())
        assertFalse("".isRenderableImageUrl())
        assertFalse("example.test/cat.jpg".isRenderableImageUrl())
    }

    /** A space cannot be fetched, and stored unescaped it is a blank card rather than an error. */
    @Test
    fun `whitespace is not renderable`() {
        assertFalse("https://x.test/a b.jpg".isRenderableImageUrl())
        assertFalse("https://x.test/a.jpg\n".isRenderableImageUrl())
    }

    @Test
    fun `the factory builds the shape both clients read`() {
        val ref = remoteImageRef("https://example.test/cat.jpg")
        assertEquals("https://example.test/cat.jpg", ref?.url)
        assertEquals("", ref?.path)
        assertEquals("", ref?.sha256)
        assertEquals("image/jpeg", ref?.mime)
        assertNull(ref?.uri)
        assertTrue(ref?.isRemote == true)
    }

    @Test
    fun `the factory refuses what cannot render`() {
        assertNull(remoteImageRef("http://example.test/cat.jpg"))
        assertNull(remoteImageRef("una manzana roja"))
        assertNull(remoteImageRef(""))
    }
}
