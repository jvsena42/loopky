package com.github.jvsena42.loopky.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The terminal code has to be **seamless**, which is a stronger property than being the right
 * modules.
 *
 * A `█` against a fixed background reproduces the matrix perfectly and is still unscannable: the
 * terminal fills a cell's background over the whole line box but draws the glyph at the font's ink
 * height, so on Terminal.app a 6px stripe of background crossed every text-row boundary and cut
 * the top-left finder's 98px bar into three 22px pieces. Finder detection is run-length ratios, so
 * Ring saw nothing. Nothing about the module values changed, which is why the assertions here are
 * about the *background* a cell is painted with rather than about the glyph.
 */
class TerminalQrRenderTest {

    private val authUrl =
        "pubkyauth://signin?caps=%2Fpub%2Floopky%2F%3Arw&relay=https%3A%2F%2Fhttprelay.pubky.app" +
            "%2Finbox&secret=zSFFp0nyJ_kZINkVgxnC2tTUc02n9oDxBm_KdUP9SQY"

    /** A cell as rendered: the background index it sits on, and the glyph drawn over it. */
    private data class Cell(val background: Int, val foreground: Int, val glyph: Char)

    /** Re-read the rendered text the way a terminal would, carrying colour across cells. */
    private fun cells(rendered: String): List<List<Cell>> =
        rendered.trimEnd('\n').lines().map { line ->
            val row = mutableListOf<Cell>()
            var foreground = -1
            var background = -1
            var index = 0
            while (index < line.length) {
                if (line[index] == '') {
                    val end = line.indexOf('m', index)
                    val codes = line.substring(index + 2, end).split(';').map(String::toInt)
                    // "0" resets; otherwise 38;5;n sets the foreground and 48;5;n the background.
                    if (codes == listOf(0)) {
                        foreground = -1
                        background = -1
                    } else {
                        codes.chunked(3).forEach { (kind, _, value) ->
                            if (kind == FOREGROUND) foreground = value else background = value
                        }
                    }
                    index = end + 1
                } else {
                    row += Cell(background, foreground, line[index])
                    index++
                }
            }
            row
        }

    @Test
    fun `a dark module is a dark background, never glyph ink alone`() {
        val grid = cells(TerminalQr.render(authUrl))
        // The top-left finder is 7 solid modules square, inside the 4-module quiet zone. Its left
        // bar spans rows 4..10 of the matrix, so every text row it fully covers must be painted
        // dark rather than drawn — that is the seam this exists to prevent.
        val fullyInsideFinder = listOf(3, 4)
        fullyInsideFinder.forEach { row ->
            val cell = grid[row][QUIET_ZONE]
            assertEquals(
                BLACK,
                cell.background,
                "text row $row of the finder's left bar is drawn, not painted — it will carry a seam",
            )
        }
    }

    @Test
    fun `the quiet zone is painted white on every side`() {
        val grid = cells(TerminalQr.render(authUrl))
        // A quiet zone left at the terminal's own background is the other way to lose the code.
        grid.take(2).forEach { row ->
            assertTrue(row.all { it.background == WHITE }, "a quiet-zone row is not white")
        }
        grid.forEach { row ->
            assertEquals(WHITE, row.first().background, "the left quiet zone is not white")
            assertEquals(WHITE, row.last().background, "the right quiet zone is not white")
        }
    }

    @Test
    fun `every cell is the upper half block, so only boundaries depend on the font`() {
        val glyphs = cells(TerminalQr.render(authUrl)).flatten().map { it.glyph }.toSet()
        assertEquals(setOf('▀'), glyphs)
    }

    @Test
    fun `each line resets, so the shell prompt is not left inverted`() {
        TerminalQr.render(authUrl).trimEnd('\n').lines().forEach {
            assertTrue(it.endsWith("[0m"), "a line does not reset its colours")
        }
    }

    @Test
    fun `the code is square in modules`() {
        val grid = cells(TerminalQr.render(authUrl))
        val width = grid.first().size
        assertTrue(grid.all { it.size == width }, "the rendered rows are ragged")
        // Two modules per text row, and an odd matrix height rounds up into the quiet zone.
        assertTrue(grid.size * 2 - width in 0..1, "the code is ${width}x${grid.size * 2} modules")
    }

    private companion object {
        const val FOREGROUND = 38
        const val BLACK = 16
        const val WHITE = 231
        const val QUIET_ZONE = 4
    }
}
