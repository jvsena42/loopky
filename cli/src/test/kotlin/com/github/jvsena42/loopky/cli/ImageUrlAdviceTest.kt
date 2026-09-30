package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.checkedImageUrl
import com.github.jvsena42.loopky.cli.commands.imageUrlAdvice
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ImageUrlAdviceTest {

    private val gull = "https://upload.wikimedia.org/wikipedia/commons/thumb/9/9a/Gull.jpg"

    @Test
    fun `a disallowed wikimedia thumbnail width is called out`() {
        val advice = imageUrlAdvice("$gull/320px-Gull.jpg")
        assertTrue(advice != null)
        assertContains(advice, "320px")
        assertContains(advice, "blank")
    }

    /**
     * The inversion, and the reason the two rules are one function. For a raster the original is
     * the answer; for a vector it is the problem, and advising someone to reach for it is how a
     * whole deck of flags became blank cards.
     */
    @Test
    fun `a wikimedia svg original is pointed at its raster thumbnail`() {
        val advice = imageUrlAdvice("https://upload.wikimedia.org/wikipedia/commons/0/03/Flag_of_Italy.svg")
        assertTrue(advice != null)
        assertContains(
            advice,
            "https://upload.wikimedia.org/wikipedia/commons/thumb/0/03/Flag_of_Italy.svg/" +
                "500px-Flag_of_Italy.svg.png",
        )
    }

    @Test
    fun `an svg thumbnail at a served width is what the advice asked for and is left alone`() {
        assertNull(
            imageUrlAdvice(
                "https://upload.wikimedia.org/wikipedia/commons/thumb/0/03/Flag_of_Italy.svg/" +
                    "500px-Flag_of_Italy.svg.png",
            ),
        )
    }

    /** The width is still wrong here, but "drop /thumb/" would hand back the vector. */
    @Test
    fun `a bad width over an svg source withholds the drop-the-thumb advice`() {
        val advice = imageUrlAdvice(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/0/03/Flag_of_Italy.svg/" +
                "800px-Flag_of_Italy.svg.png",
        )
        assertTrue(advice != null)
        assertContains(advice, "800px")
        assertContains(advice, "Do not drop the /thumb/ segment")
    }

    /** The other four the deck hit: a Wikipedia lead image is not necessarily a picture. */
    @Test
    fun `the file types neither app decodes are called out wherever they are hosted`() {
        listOf("tiff", "webm", "ogv", "stl").forEach { extension ->
            val advice = imageUrlAdvice("https://example.test/Tostapane.$extension")
            assertTrue(advice != null, ".$extension should be called out")
            assertContains(advice, "blank")
        }
    }

    @Test
    fun `an ordinary raster keeps the advice that the original is always served`() {
        val advice = imageUrlAdvice("$gull/800px-Gull.jpg")
        assertTrue(advice != null)
        assertContains(advice, "drop the /thumb/ segment")
    }

    @Test
    fun `the widths wikimedia actually serves are left alone`() {
        // 1920 is served and was missing from the list, so an agent taking the list as
        // authoritative rewrote a working URL (#257, item 4). Measured 2026-09-06: 800, 1600 and
        // 2560 answer 400 while 1920 answers 200 image/png.
        for (width in listOf(120, 250, 330, 500, 960, 1280, 1920)) {
            assertNull(imageUrlAdvice("$gull/${width}px-Gull.jpg"), "$width should be accepted")
        }
        for (width in listOf(800, 1600, 2560)) {
            assertTrue(imageUrlAdvice("$gull/${width}px-Gull.jpg") != null, "$width should be noted")
        }
    }

    /**
     * Only the **final** extension decides whether a picture can be decoded.
     *
     * Commons renders a TIFF or a PDF source to a raster thumbnail and keeps the source name in
     * both a directory segment and the file stem, so the same address contains `.tif` twice and
     * serves `image/jpeg`. Matching on any occurrence flagged that and left the exact SVG
     * analogue alone — a rule with no reading anyone could act on (#257, item 3).
     */
    @Test
    fun `a rendered thumbnail of a source neither app decodes is not a finding`() {
        assertNull(
            imageUrlAdvice(
                "https://upload.wikimedia.org/wikipedia/commons/thumb/0/02/Typical_human_cell.tif/" +
                    "lossy-page1-500px-Typical_human_cell.tif.jpg",
            ),
        )
        assertNull(
            imageUrlAdvice(
                "https://upload.wikimedia.org/wikipedia/commons/thumb/0/0f/First_aid_sign.svg/" +
                    "500px-ISO_7010_E003_-_First_aid_sign.svg.png",
            ),
        )
    }

    /** And the same file *without* the render is still the blank card it always was. */
    @Test
    fun `the undecodable source itself is still a finding`() {
        val tiff = imageUrlAdvice("https://upload.wikimedia.org/wikipedia/commons/0/02/Typical_human_cell.tif")
        assertTrue(tiff != null)
        assertContains(tiff, ".tif is not something either app can decode")
    }

    /**
     * A rendered thumbnail carries a marker in front of its width — `lossy-page1-`, a frame
     * number — so parsing the whole prefix as a number gave those URLs no width at all and the
     * width rule silently never applied to one.
     */
    @Test
    fun `a width behind a render marker is still read`() {
        val advice = imageUrlAdvice(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/0/02/Cell.tif/" +
                "lossy-page1-800px-Cell.tif.jpg",
        )
        assertTrue(advice != null)
        assertContains(advice, "800px")
    }

    /**
     * `thumb.wikimedia.org` is what the imageinfo API hands back, so it is what an agent has, and
     * a rule written for `upload.` alone applied to none of them.
     */
    @Test
    fun `the width rule reaches the host the imageinfo API answers with`() {
        val advice = imageUrlAdvice(
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/9/9a/Gull.jpg/320px-Gull.jpg",
        )
        assertTrue(advice != null)
        assertContains(advice, "320px")
    }

    /** The original file has no width to get wrong, which is why the advice recommends it. */
    @Test
    fun `a non-thumbnail wikimedia url has nothing to say about it`() {
        assertNull(imageUrlAdvice("https://upload.wikimedia.org/wikipedia/commons/9/9a/Gull.jpg"))
    }

    @Test
    fun `other hosts are not second-guessed`() {
        assertNull(imageUrlAdvice("https://example.test/thumb/320px-cat.jpg"))
        assertNull(imageUrlAdvice("https://example.test/cat.jpg"))
    }

    /**
     * The width is the *last* segment's own prefix, not the first `NNNpx-` anywhere in the URL.
     * A Wikimedia thumbnail repeats the file name, so an earlier segment can carry one too — and
     * reading that one would warn about a URL whose actual thumbnail width is fine.
     *
     * The converse is a knowing false positive: a file genuinely named `100px-art.jpg` sitting
     * under /thumb/ is indistinguishable from a thumbnail and gets the note. That costs a line of
     * stderr on a URL that works, which is the right way round for something advisory.
     */
    @Test
    fun `the width comes from the last segment, not an earlier one`() {
        assertNull(imageUrlAdvice("$gull/100px-art/250px-Gull.jpg"))
    }

    /**
     * The digits before `px-` are a width only where a width can be: at the start of the segment,
     * or after a render marker's hyphen. Reading any trailing digits invented a 2px thumbnail out
     * of an ordinary file name and warned about it (#261 review, finding 6).
     */
    @Test
    fun `a digit in the file name is not read as a thumbnail width`() {
        assertNull(imageUrlAdvice("$gull/Foo2px-bar.jpg"))
        assertNull(imageUrlAdvice("$gull/holiday2px-cat.jpg"))
        // The two shapes that really are widths still are.
        assertTrue(imageUrlAdvice("$gull/800px-Gull.jpg") != null)
        assertTrue(imageUrlAdvice("$gull/lossy-page1-800px-Gull.jpg") != null)
    }

    @Test
    fun `http is refused with a message naming the scheme`() {
        val error = runCatching { "http://example.test/cat.jpg".checkedImageUrl("--front-image") }
            .exceptionOrNull() as? CliError ?: fail("expected a CliError")
        assertEquals(ExitCode.BadInput, error.exitCode)
        assertContains(error.message.orEmpty(), "https://")
        assertContains(error.message.orEmpty(), "--front-image")
    }

    @Test
    fun `a good url passes through and says nothing`() {
        val notes = mutableListOf<String>()
        val url = "https://example.test/cat.jpg".checkedImageUrl("--front-image", notes::add)
        assertEquals("https://example.test/cat.jpg", url)
        assertTrue(notes.isEmpty())
    }

    @Test
    fun `a bad wikimedia width passes through but is noted`() {
        val notes = mutableListOf<String>()
        "$gull/800px-Gull.jpg".checkedImageUrl("--back-image", notes::add)
        assertEquals(1, notes.size)
        assertContains(notes.single(), "--back-image")
    }
}
