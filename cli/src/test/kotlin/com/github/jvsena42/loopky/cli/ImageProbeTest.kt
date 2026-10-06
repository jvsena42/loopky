package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.ImageCheck
import com.github.jvsena42.loopky.cli.commands.PacedProbe
import com.github.jvsena42.loopky.cli.commands.ProbeAnswer
import com.github.jvsena42.loopky.cli.commands.RATE_LIMIT_BUDGET_MS
import com.github.jvsena42.loopky.cli.commands.checkImageUrls
import com.github.jvsena42.loopky.cli.commands.classified
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    private val jpeg = ProbeAnswer(status = 200, contentType = "image/jpeg")
    private val limited = ProbeAnswer(status = 429, contentType = "text/html")

    /**
     * #454: 650 URLs on one host at eight in flight, and half ended unverified. A host that
     * refuses the burst and serves whatever comes after a wait is the shape of that run.
     */
    @Test
    fun `a host that rate-limits the burst is waited on until every url is answered`() = runBlocking {
        val calm = AtomicBoolean()
        val pauses = ConcurrentLinkedQueue<Long>()
        val notes = ConcurrentLinkedQueue<String>()
        val probe = PacedProbe(
            concurrency = 8,
            attempt = { if (calm.get()) jpeg else limited },
            pause = {
                pauses += it
                calm.set(true)
            },
            onNote = notes::add,
        )

        val problems = checkImageUrls(List(60) { "https://x.test/$it.jpg" }, notes::add, probe = probe::probe)

        assertEquals(emptyList(), problems)
        assertTrue(pauses.isNotEmpty(), "the 429 was waited on rather than retried at once")
        assertEquals(1, notes.count { "one URL at a time" in it }, "said once per host, not once per URL")
    }

    /**
     * The host is limited before anything concurrent starts, so every request below goes through
     * its lane and any two in flight together are the pacing's fault and nobody else's.
     */
    @Test
    fun `a limited host is asked one url at a time, whatever the concurrency`() = runBlocking {
        val inFlight = AtomicInteger()
        val overlaps = AtomicInteger()
        val refuseNext = AtomicBoolean(true)
        val probe = PacedProbe(
            concurrency = 8,
            attempt = {
                if (inFlight.incrementAndGet() > 1) overlaps.incrementAndGet()
                Thread.sleep(1)
                inFlight.decrementAndGet()
                if (refuseNext.getAndSet(false)) limited else jpeg
            },
            pause = {},
        )
        assertTrue(probe.probe("https://x.test/first.jpg").ok)

        val problems = checkImageUrls(List(40) { "https://x.test/$it.jpg" }, {}, probe = probe::probe)

        assertEquals(emptyList(), problems)
        assertEquals(0, overlaps.get())
    }

    /** The reviewer's case on #456: a long list on a host that keeps answering between 429s. */
    @Test
    fun `a host that is slow but answering is never cut off by the budget`() = runBlocking {
        val calls = AtomicInteger()
        var waited = 0L
        val probe = PacedProbe(
            attempt = { if (calls.getAndIncrement() % 2 == 0) ProbeAnswer(429, null, retryAfterMs = 30_000) else jpeg },
            pause = { waited += it },
        )

        val checks = List(50) { probe.probe("https://x.test/$it.jpg") }

        assertTrue(waited > RATE_LIMIT_BUDGET_MS, "the run waited ${waited}ms in all")
        assertTrue(checks.all { it.ok })
    }

    /** A permit held while queued on a limited host's lane is one a healthy host cannot use. */
    @Test
    fun `urls queued behind a limited host do not hold up another host`() = runBlocking {
        val fastHostDone = CompletableDeferred<Unit>()
        val fastAnswered = AtomicInteger()
        val probe = PacedProbe(
            concurrency = 2,
            attempt = { url ->
                when {
                    "fast.test" in url -> jpeg.also { if (fastAnswered.incrementAndGet() == 5) fastHostDone.complete(Unit) }
                    fastHostDone.isCompleted -> jpeg
                    else -> limited
                }
            },
            // The slow host's lane stays shut until the other host has had every answer.
            pause = { fastHostDone.await() },
        )
        val urls = List(6) { "https://slow.test/$it.jpg" } + List(5) { "https://fast.test/$it.jpg" }

        val problems = withTimeout(10_000) { checkImageUrls(urls, {}, probe = probe::probe) }

        assertEquals(emptyList(), problems)
    }

    @Test
    fun `the wait is the host's Retry-After when that is longer than the back-off`() = runBlocking {
        val pauses = mutableListOf<Long>()
        val answers = ArrayDeque(listOf(ProbeAnswer(429, null, retryAfterMs = 7_000), jpeg))

        val check = PacedProbe(attempt = { answers.removeFirst() }, pause = { pauses += it }).probe("https://x.test/a.jpg")

        assertTrue(check.ok)
        assertEquals(listOf(7_000L), pauses)
    }

    @Test
    fun `a host that keeps refusing is waited on for longer each time`() = runBlocking {
        val pauses = mutableListOf<Long>()

        val check = PacedProbe(attempt = { limited }, pause = { pauses += it }).probe("https://x.test/a.jpg")

        assertTrue(check.unverified)
        assertEquals(pauses.sorted(), pauses)
        assertTrue(pauses.last() > pauses.first())
    }

    /** A pre-flight check in front of a write cannot wait on one host forever. */
    @Test
    fun `a host that never relents stops being asked once its waiting budget is spent`() = runBlocking {
        val asked = AtomicInteger()
        var waited = 0L
        val probe = PacedProbe(attempt = { limited.also { asked.incrementAndGet() } }, pause = { waited += it })

        val checks = List(200) { probe.probe("https://x.test/$it.jpg") }

        assertTrue(checks.all { it.unverified }, "a 429 is never a finding about the picture")
        assertTrue(waited < RATE_LIMIT_BUDGET_MS + 30_000, "waited ${waited}ms")
        assertTrue(asked.get() < 200, "the URLs after the budget ran out are not asked at all")
        assertContains(checks.last().reason.orEmpty(), "stopped asking")
    }

    @Test
    fun `one host's rate limit does not slow another`() = runBlocking {
        val pauses = mutableListOf<Long>()
        val probe = PacedProbe(
            attempt = { if ("slow.test" in it) limited else jpeg },
            pause = { pauses += it },
        )
        probe.probe("https://slow.test/a.jpg")
        val waitedForSlow = pauses.size

        assertTrue(probe.probe("https://fast.test/a.jpg").ok)
        assertEquals(waitedForSlow, pauses.size)
    }
}
