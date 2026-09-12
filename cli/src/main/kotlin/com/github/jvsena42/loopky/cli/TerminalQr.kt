package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.data.storage.OwnerOnly
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * A `pubkyauth://` URL as something a phone camera can read off a terminal.
 *
 * Half-blocks, so one text row carries two QR rows and the code stays square-ish in a font whose
 * cells are twice as tall as they are wide. A full block per module is either twice as wide as it
 * is tall — which scanners handle badly — or twice as many lines as most terminals show at once.
 *
 * **Every module is painted as a colour, never as glyph ink.** The cell is always `▀`, with the
 * top module as the foreground and the bottom module as the background — so a `█`/`▄`/space
 * chosen against a fixed background is wrong, however obvious it looks. A terminal fills the whole
 * cell with the background, leading included, but draws a block glyph at the font's own ink height:
 * measured on Terminal.app, a 28px line box against a 22px glyph, leaving a 6px stripe of
 * background across every text-row boundary. That severed the top-left finder's 98px bar into
 * three 22px pieces, and finder detection is run-length ratios — so Pubky Ring could not see the
 * code at all. Under this shape a run of dark modules is a run of dark *backgrounds* and has no
 * seam; only a light/dark boundary within one cell still rides on the glyph, off by the 3px the
 * ink box is short. Terminals that special-case U+2580 (iTerm2, Kitty, WezTerm) never showed this.
 *
 * **The colours are not decoration.** A QR code has to be dark-on-light, and a terminal's own
 * theme is unknown and frequently dark, so drawing modules in the default foreground produces an
 * inverted code no scanner will read. The 256-colour cube rather than ANSI 0/7 for the same
 * reason: indices 0–15 are exactly the ones themes remap, and this one rendered "white" at 78%
 * grey. 16 and 231 are black and white in every palette that has them.
 */
object TerminalQr {

    fun render(text: String): String {
        val matrix = encode(text, MODULE_SIZE)
        val builder = StringBuilder()
        // Two matrix rows per text row; a matrix with an odd height leaves the last bottom half
        // blank, which the quiet zone already accounts for.
        var y = 0
        while (y < matrix.height) {
            // Only on a change: a re-stated colour per cell triples the line for nothing, and a
            // long enough line is one more thing a terminal can wrap.
            var painted: String? = null
            for (x in 0 until matrix.width) {
                val top = matrix.get(x, y)
                val bottom = y + 1 < matrix.height && matrix.get(x, y + 1)
                val pair = pairColours(top, bottom)
                if (pair != painted) {
                    builder.append(pair)
                    painted = pair
                }
                builder.append(UPPER_HALF_BLOCK)
            }
            builder.append(ANSI_RESET).append('\n')
            y += 2
        }
        return builder.toString()
    }

    /** Foreground is the top module, background the bottom one — see the note on [TerminalQr]. */
    private fun pairColours(top: Boolean, bottom: Boolean): String = when {
        top && bottom -> DARK_ON_DARK
        top -> DARK_ON_LIGHT
        bottom -> LIGHT_ON_DARK
        else -> LIGHT_ON_LIGHT
    }

    /**
     * The same code as a PNG, for a box with no terminal anyone is looking at.
     *
     * **Created 0600 before a byte is written.** A QR code is an encoding, not a protection: this
     * file *is* the `pubkyauth://` URL, `secret=` included, and that secret is what the auth
     * token is encrypted to — anyone who reads it before the user approves in Ring can poll the
     * relay and take the session instead of the legitimate client. Written at the ambient umask on
     * a shared host, `--qr-out /tmp/qr.png` publishes a live credential to every uid on the machine
     * for the length of the approval window.
     *
     * Same order as `JsonFileStore.persist` and for the same reason: permissions first, content
     * second, so the readable window never exists. The caller deletes it once approval lands.
     */
    fun writePng(text: String, file: File) {
        val png = encode(text, PNG_SIZE).toPng()
        val target = file.absoluteFile
        target.parentFile?.mkdirs()
        val path = createOwnerOnly(target.toPath())
        // A stream on the file we just created, **not** a writer that takes a `File`: the
        // `ImageIO.write(…, File)` overload this used to call deletes the file and recreates it,
        // which throws away the mode set above and puts the credential back at the ambient umask.
        // Caught by QrCredentialTest, not by reading the API.
        Files.newOutputStream(path).use { output -> output.write(png) }
    }

    /**
     * An empty file only its owner can read.
     *
     * Best-effort on the restriction, like the session store: a host that can express neither a
     * POSIX mode nor an ACL is real, and refusing to write there would cost a capability it was
     * never going to give. [OwnerOnly] decides which spelling this host uses (#301) — before, this
     * asked only for a mode, so on Windows the file holding a live `pubkyauth://` credential landed
     * at whatever the parent directory happened to allow while `login` printed "owner-readable
     * only".
     */
    private fun createOwnerOnly(path: Path): Path {
        Files.deleteIfExists(path)
        return OwnerOnly.createFile(path)
    }

    private fun encode(text: String, size: Int): BitMatrix =
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                // A `pubkyauth://` URL carries a relay address and a secret and runs long, so the
                // code is dense. L keeps the module count down; the scanner is 20cm from a screen,
                // not reading a smudged label.
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )

    /**
     * Requested size in modules. ZXing treats this as a floor and rounds up to whatever the
     * content needs, so a long URL produces a bigger matrix rather than a failure.
     */
    private const val MODULE_SIZE = 1

    private const val PNG_SIZE = 512

    /** Four modules is the QR spec's minimum quiet zone; below it scanners start missing the code. */
    private const val QUIET_ZONE_MODULES = 4

    /**
     * The one glyph rendered, and it carries only the boundaries: where both modules match it is
     * invisible under a background of its own colour, so a terminal that cannot draw it at all
     * still puts a readable code on screen.
     */
    private const val UPPER_HALF_BLOCK = '\u2580'

    private const val DARK_ON_DARK = "\u001B[38;5;16;48;5;16m"
    private const val DARK_ON_LIGHT = "\u001B[38;5;16;48;5;231m"
    private const val LIGHT_ON_DARK = "\u001B[38;5;231;48;5;16m"
    private const val LIGHT_ON_LIGHT = "\u001B[38;5;231;48;5;231m"

    private const val ANSI_RESET = "\u001B[0m"

    /**
     * The matrix as a 1-bit greyscale PNG, encoded here in about thirty lines rather than by
     * `ImageIO`.
     *
     * Not a preference — it is what keeps `loopky` a **single file** (#210). `ImageIO` reaches
     * `java.awt`, and `native-image` cannot fold AWT into the executable on Linux: it ships it
     * beside the binary as `libawt.so`, `libawt_headless.so`, `libawt_xawt.so`, `libjavajpeg.so`
     * and `liblcms.so`, one of them an X11 library, in a sandbox with no display. This one call
     * site was the whole of the cost, and a QR code is the one image that needs no image library:
     * two colours, no palette, no filtering worth the name.
     *
     * `Deflater` writes a zlib stream by default, which is exactly what an `IDAT` holds, so the
     * whole of the format here is three chunks and a CRC. Bit depth 1, colour type 0 (greyscale):
     * a sample is one bit, `0` is black, and every row is preceded by a filter byte.
     */
    private fun BitMatrix.toPng(): ByteArray {
        val stride = (width + BITS_PER_BYTE - 1) / BITS_PER_BYTE
        // Zero-filled, so every module starts black and the *light* ones are the bits written in.
        val raw = ByteArray((stride + 1) * height)
        for (y in 0 until height) {
            val row = y * (stride + 1) + 1
            for (x in 0 until width) {
                if (get(x, y)) continue
                val index = row + x / BITS_PER_BYTE
                raw[index] = (raw[index].toInt() or (HIGH_BIT ushr (x % BITS_PER_BYTE))).toByte()
            }
        }

        val header = ByteArrayOutputStream().apply {
            writeBigEndian(width)
            writeBigEndian(height)
            write(byteArrayOf(BIT_DEPTH, COLOR_TYPE_GREYSCALE, 0, 0, 0))
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(PNG_SIGNATURE)
            writeChunk("IHDR", header)
            writeChunk("IDAT", deflate(raw))
            writeChunk("IEND", ByteArray(0))
        }.toByteArray()
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            ByteArrayOutputStream().also { sink ->
                DeflaterOutputStream(sink, deflater).use { it.write(bytes) }
            }.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Length, four-letter type, payload, and a CRC over the type **and** the payload. */
    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        writeBigEndian(data.size)
        write(name)
        write(data)
        val crc = CRC32().apply {
            update(name)
            update(data)
        }
        writeBigEndian(crc.value.toInt())
    }

    /** A 32-bit value, most significant byte first, which is how every PNG field is written. */
    private fun ByteArrayOutputStream.writeBigEndian(value: Int) {
        for (shift in BIG_ENDIAN_SHIFTS) write(value ushr shift)
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        '\r'.code.toByte(), '\n'.code.toByte(), 0x1A, '\n'.code.toByte(),
    )

    private val BIG_ENDIAN_SHIFTS = intArrayOf(24, 16, 8, 0)

    private const val BITS_PER_BYTE = 8
    private const val HIGH_BIT = 0x80
    private const val BIT_DEPTH: Byte = 1
    private const val COLOR_TYPE_GREYSCALE: Byte = 0
}
