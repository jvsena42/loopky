package com.github.jvsena42.loopky.cli

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `--qr-out` writes a **bearer credential**, and has to be created like one.
 *
 * The PNG is the `pubkyauth://` URL with `secret=` in it, and that secret is what the auth token
 * is encrypted to — anyone who reads the file before the user approves in Ring can poll the relay
 * and take the session instead of the legitimate client. A QR code is an encoding, not a
 * protection. The flag exists for a box with no terminal anyone is watching, so
 * `--qr-out /tmp/qr.png` on a shared host is the *intended* use, which is exactly where the
 * ambient umask publishes it to every uid on the machine.
 */
class QrCredentialTest {

    private val authUrl =
        "pubkyauth://signin?caps=%2Fpub%2Floopky%2F%3Arw&relay=https%3A%2F%2Fhttprelay.pubky.app" +
            "%2Finbox&secret=zSFFp0nyJ_kZINkVgxnC2tTUc02n9oDxBm_KdUP9SQY"

    /**
     * Skipped rather than passed where there are no POSIX modes (#301). A green test that asserted
     * nothing is how the four `setPosixFilePermissions` sites came to be silent no-ops on Windows
     * in the first place; the ACL that replaces them there gets its own assertion.
     */
    @Test
    fun `the png is owner-only`() {
        assumeTrue(
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
            "needs POSIX file permissions",
        )
        val file = File.createTempFile("loopky-qr", ".png")
        // Start it world-readable, so passing proves the writer set the mode rather than inherited
        // a strict umask from whatever ran the test.
        Files.setPosixFilePermissions(
            file.toPath(),
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            ),
        )

        TerminalQr.writePng(authUrl, file)

        val mode = Files.getPosixFilePermissions(file.toPath())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            mode,
            "the QR file is a live credential and was left at $mode",
        )
        file.delete()
    }

    @Test
    fun `and it is a real png`() {
        val file = File.createTempFile("loopky-qr", ".png")
        TerminalQr.writePng(authUrl, file)

        // PNG magic, so the permission work above did not just produce an empty file.
        val header = file.readBytes().take(PNG_MAGIC.size)
        assertEquals(PNG_MAGIC, header)
        file.delete()
    }

    /**
     * The PNG is written by ~30 lines of chunk-and-CRC in [TerminalQr] rather than by `ImageIO`,
     * because AWT is what turns the native binary into eight files (#210). That trade is only
     * sound if the bytes are a real PNG, so this decodes them with the library that was dropped —
     * `ImageIO` is on the *test* classpath and nowhere near the shipped image — and checks the
     * one thing a scanner cares about: a dark finder pattern on a light quiet zone, the right way
     * round.
     */
    @Test
    fun `the hand-rolled png decodes, and is dark-on-light`() {
        val file = File.createTempFile("loopky-qr", ".png")
        TerminalQr.writePng(authUrl, file)

        val image = requireNotNull(ImageIO.read(file)) { "the bytes did not decode as an image" }
        assertTrue(image.width >= QR_MIN_SIZE && image.height == image.width, "${image.width}x${image.height}")

        // The quiet zone is light, and the finder pattern's outer ring — four modules in from the
        // corner — is dark. Inverted output is the failure a QR encoder actually has, and it is
        // invisible in a hex dump.
        val module = image.width / MODULES_ACROSS_A_VERSION_1_CODE
        assertEquals(WHITE, image.getRGB(1, 1) and RGB_MASK, "the quiet zone came out dark")
        assertEquals(
            BLACK,
            image.getRGB(module * QUIET_ZONE_MODULES + module / 2, module * QUIET_ZONE_MODULES + module / 2) and RGB_MASK,
            "the finder pattern came out light",
        )
        file.delete()
    }

    @Test
    fun `a missing parent directory is created`() {
        val dir = Files.createTempDirectory("loopky-qr-dir").resolve("nested").toFile()
        val file = File(dir, "qr.png")

        TerminalQr.writePng(authUrl, file)

        assertTrue(file.isFile)
        file.delete()
    }

    private companion object {
        val PNG_MAGIC = listOf<Byte>(0x89.toByte(), 0x50, 0x4E, 0x47)

        /** ZXing treats the requested 512 as a floor, so the code is at least that wide. */
        const val QR_MIN_SIZE = 512

        /**
         * A version-1 code is 21 modules plus two quiet zones. The real code is denser than
         * version 1, so this over-estimates the module size — which is the safe direction: it
         * lands the probe *inside* the finder pattern rather than past it.
         */
        const val MODULES_ACROSS_A_VERSION_1_CODE = 29
        const val QUIET_ZONE_MODULES = 4

        const val RGB_MASK = 0xFFFFFF
        const val BLACK = 0x000000
        const val WHITE = 0xFFFFFF
    }
}
