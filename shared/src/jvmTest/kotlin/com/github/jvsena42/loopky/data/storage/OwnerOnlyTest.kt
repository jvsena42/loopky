package com.github.jvsena42.loopky.data.storage

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That "owner-only" is a property this host actually got, rather than one it was asked for (#301):
 * the callers hold a session secret, a live `pubkyauth://` credential, a downloaded executable and
 * the config directory, and `login` prints "owner-readable only" off the answer.
 */
class OwnerOnlyTest {

    private val dir = Files.createTempDirectory("loopky-owner-only")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    /**
     * The one assertion that fails on a host nobody thought about. Both shipped desktop rows and
     * every CI runner have POSIX modes, so a false here means a new row arrived with no way to
     * protect a credential.
     */
    @Test
    fun `this host can express owner-only at all`() {
        assertTrue(
            OwnerOnly.supported,
            "no POSIX mode on ${FileSystems.getDefault().supportedFileAttributeViews()}",
        )
    }

    @Test
    fun `a file created through the helper is readable only by its owner`() {
        val created = OwnerOnly.createFile(dir.resolve("secret.json"))

        // The reported answer and the actual state have to agree: `--qr-out` prints a promise off
        // this boolean, so a true here that the mode does not back is a false assurance.
        assertTrue(created.ownerOnly, "createFile reported it could not restrict on a host that supports it")
        assertOwnerOnly(created.path)
    }

    /** The shape `JsonFileStore`, `UpdateCheck` and `update` all write through. */
    @Test
    fun `a temp file is restricted before it can hold anything`() {
        val temp = OwnerOnly.createTempFile(dir, "secrets.json", ".tmp")

        assertOwnerOnly(temp)
    }

    /**
     * Starts world-readable, so passing proves [OwnerOnly.restrict] narrowed it rather than
     * inheriting a strict umask from whatever ran the test — the same trick `QrCredentialTest`
     * uses, and for the same reason.
     */
    @Test
    fun `restrict narrows a file somebody else left open`() {
        val file = dir.resolve("was-open.json")
        Files.createFile(file)
        if (posix) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"))
        }

        assertTrue(OwnerOnly.restrict(file), "restrict reported failure on a host that supports it")

        assertOwnerOnly(file)
    }

    @Test
    fun `a directory gets the executable bit an owner needs to enter it`() {
        val child = OwnerOnly.createDirectories(dir.resolve("nested"))

        if (posix) {
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(child),
            )
        }
        assertTrue(Files.isReadable(child) && Files.isWritable(child) && Files.isExecutable(child))
    }

    /** `ConfigHome.prepare` is the caller that creates the directory every other file lands in. */
    @Test
    fun `prepare leaves the config directory owner-only`() {
        val home = dir.resolve("config-home")
        ConfigHome.prepare(home)

        assertTrue(Files.isDirectory(home))
        if (posix) {
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(home),
            )
        }
    }

    private fun assertOwnerOnly(file: Path) {
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file),
            "left at ${Files.getPosixFilePermissions(file)}",
        )
    }

    private val posix: Boolean =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
}
