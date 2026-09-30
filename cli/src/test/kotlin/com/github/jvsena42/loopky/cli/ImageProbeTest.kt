package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.ImageCheck
import com.github.jvsena42.loopky.cli.commands.ProbeAnswer
import com.github.jvsena42.loopky.cli.commands.checkImageUrls
import com.github.jvsena42.loopky.cli.commands.classified
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `--check-images`, driven against a fake host.
 *
 * What it has to get right is what it *does not* report. The static checks already refuse the
 * knowably-broken; this one exists for the three cases a string cannot show — a `.stl` behind an
 * ordinary-looking address, a renamed file, a host refusing an unfamiliar client — so a run that
 * buried those in 900 lines of "this one is fine" would be no better than the hand-written check
 * it replaces.
 */
class ImageProbeTest {

    private fun ok(url: String) = ImageCheck(url, status = 200, contentType = "image/jpeg", ok = true)

    @Test
    fun `a picture that is a picture is reported nowhere`() = runBlocking {
        val notes = mutableListOf<String>()

        val problems = checkImageUrls(listOf("https://x.test/a.jpg"), notes::add) { ok(it) }

        assertEquals(emptyList(), problems)
        assertTrue(notes.none { "a.jpg" in it }, "a working URL earns no line of its own")
    }

    @Test
    fun `something that is not an image is reported with its content type`() = runBlocking {
        val notes = mutableListOf<String>()

        val problems = checkImageUrls(listOf("https://x.test/Rotonda.webm"), notes::add) {
            ImageCheck(it, status = 200, contentType = "video/webm", reason = "this is not an image")
        }

        assertEquals(1, problems.size)
        assertEquals("video/webm", problems.single().contentType)
        assertTrue(notes.any { "Rotonda.webm" in it && "video/webm" in it })
    }

    @Test
    fun `a host that does not answer is reported without a status`() = runBlocking {
        val notes = mutableListOf<String>()

        val problems = checkImageUrls(listOf("https://gone.test/a.jpg"), notes::add) {
            ImageCheck(it, reason = "could not be reached: connect timed out")
        }

        assertEquals(null, problems.single().status)
        assertTrue(notes.any { "could not be reached" in it })
    }

    /** A picture on forty cards is one question. The whole point is that this is cheap enough to run. */
    @Test
    fun `each distinct url is asked about once`() = runBlocking {
        val asked = mutableListOf<String>()

        checkImageUrls(List(40) { "https://x.test/same.jpg" } + "https://x.test/other.jpg", {}) {
            asked += it
            ok(it)
        }

        assertEquals(listOf("https://x.test/same.jpg", "https://x.test/other.jpg"), asked)
    }

    /** The write goes ahead either way, and the note has to say so rather than read like a refusal. */
    @Test
    fun `a run with problems says the write is happening anyway`() = runBlocking {
        val notes = mutableListOf<String>()

        checkImageUrls(listOf("https://x.test/a.jpg"), notes::add) {
            ImageCheck(it, status = 404, reason = "the host refused it")
        }

        assertContains(notes.last(), "writing anyway")
    }

    /**
     * The classification, which is where the check has an opinion. An `image/` prefix is not the
     * same as a decodable picture: Wikimedia serves an SVG original as `image/svg+xml` with an
     * ordinary 200, so a prefix check calls a whole deck of flags fine.
     */
    @Test
    fun `an image content type neither app decodes is still a finding`() {
        val svg = ProbeAnswer(status = 200, contentType = "image/svg+xml").classified("https://x.test/f.svg")

        assertEquals(false, svg.ok)
        assertContains(svg.reason.orEmpty(), "neither app decodes")
    }

    @Test
    fun `a jpeg with a charset parameter is still a jpeg`() {
        val jpeg = ProbeAnswer(status = 200, contentType = "image/jpeg; charset=binary")
            .classified("https://x.test/a.jpg")

        assertTrue(jpeg.ok)
        assertEquals("image/jpeg", jpeg.contentType)
    }

    @Test
    fun `a 200 that names no type at all is reported rather than assumed fine`() {
        assertEquals(false, ProbeAnswer(status = 200, contentType = null).classified("https://x.test/a").ok)
    }

    /**
     * The finding this file exists to keep: a 429 is what the check provokes in *itself*, so
     * calling it a broken picture turned 432 working Wikimedia URLs into findings and buried the
     * run's one real one (#257, item 1).
     */
    @Test
    fun `a rate-limited host is unverified, not wrong`() {
        val limited = ProbeAnswer(status = 429, contentType = "text/html").classified("https://x.test/a.jpg")

        assertEquals(false, limited.ok)
        assertTrue(limited.unverified, "429 says nothing about the picture")
        assertContains(limited.reason.orEmpty(), "rate-limiting")
    }

    @Test
    fun `a host erroring on its own account is unverified too`() {
        assertTrue(ProbeAnswer(status = 503, contentType = null).classified("https://x.test/a.jpg").unverified)
    }

    @Test
    fun `a host that refused the picture is wrong, not unverified`() {
        val gone = ProbeAnswer(status = 404, contentType = "text/html").classified("https://x.test/a.jpg")

        assertEquals(false, gone.ok)
        assertEquals(false, gone.unverified)
    }

    @Test
    fun `the summary counts the two kinds apart`() = runBlocking {
        val notes = mutableListOf<String>()

        val urls = listOf("https://x.test/ok.jpg", "https://x.test/slow.jpg", "https://x.test/gone.jpg")
        checkImageUrls(urls, notes::add) { url ->
            when {
                url.endsWith("ok.jpg") -> ok(url)
                url.endsWith("slow.jpg") ->
                    ImageCheck(url, status = 429, unverified = true, reason = "rate-limited")

                else -> ImageCheck(url, status = 404, reason = "the host refused it")
            }
        }

        assertContains(notes.last(), "1 ok, 1 wrong, 1 could not be checked")
    }

    /** Every row travels in `--json`; stderr is capped so one real finding is not scrolled away. */
    @Test
    fun `a flood of one kind is capped on stderr and complete in the result`() = runBlocking {
        val notes = mutableListOf<String>()
        val urls = List(50) { "https://x.test/$it.jpg" }

        val problems = checkImageUrls(urls, notes::add) {
            ImageCheck(it, status = 429, unverified = true, reason = "rate-limited")
        }

        assertEquals(50, problems.size)
        assertEquals(20, notes.count { it.startsWith("loopky:   https://") })
        assertTrue(notes.any { "and 30 more" in it })
    }

    @Test
    fun `nothing to check is silent`() = runBlocking {
        val notes = mutableListOf<String>()

        val problems = checkImageUrls(listOf("", "  "), notes::add) { error("nothing should be probed") }

        assertEquals(emptyList(), problems)
        assertEquals(emptyList(), notes)
    }
}
