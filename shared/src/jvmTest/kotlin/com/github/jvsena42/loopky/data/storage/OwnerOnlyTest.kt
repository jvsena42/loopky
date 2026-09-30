package com.github.jvsena42.loopky.data.storage

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That "owner-only" is a property this host actually got, rather than one it was asked for (#301).
 *
 * The four callers — a session secret, a live `pubkyauth://` credential, a downloaded executable
 * and the config directory — each used to request a POSIX mode inside a `runCatching` that logged
 * at debug. Nothing anywhere asserted the request had been honoured, so on Windows, where it never
 * was, every one of them silently held nothing back while `login` printed "owner-readable only".
 *
 * These run on whichever host CI is on and assert against *that* host's spelling, which is the only
 * way a single suite can cover both rows.
 */
class OwnerOnlyTest {

    private val dir = Files.createTempDirectory("loopky-owner-only")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    /**
     * The one assertion that fails on a host nobody thought about. Both shipped desktop rows and
     * every CI runner have one view or the other, so a false here means a new row arrived with no
     * way to protect a credential — which is worth failing a build over rather than discovering
     * from a support thread.
     */
    @Test
    fun `this host can express owner-only at all`() {
        assertTrue(
            OwnerOnly.supported,
            "neither a POSIX mode nor an ACL on ${FileSystems.getDefault().supportedFileAttributeViews()}",
        )
    }

    @Test
    fun `a file created through the helper is readable only by its owner`() {
        val created = OwnerOnly.createFile(dir.resolve("secret.json"))

        // The reported answer and the actual state have to agree: `--qr-out` prints a promise off
        // this boolean, so a true here that the DACL does not back is a false assurance.
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
        // Whatever the spelling, the owner has to still be able to use it.
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

    /**
     * Asserted in the host's own terms: a mode where there are modes, and otherwise that the DACL
     * names exactly one principal — **the current user**.
     *
     * The expected principal is resolved independently rather than read back from
     * [Files.getOwner] (#301). Comparing against `getOwner` was the *same call* `restrictAcl` used
     * to build the ACE, so it asserted "the ACE names whoever getOwner says", which is true by
     * construction for any owner — including `BUILTIN\Administrators`, the one that locks a
     * UAC-filtered token out of its own config. That is how a MEDIUM stayed invisible.
     *
     * **CI cannot prove the case this guards against.** GitHub's Windows runners are documented as
     * running as administrator with UAC disabled, so there is one full token: `getOwner` returns
     * Administrators and a filtered token never exists to be locked out. This is therefore a
     * reasoned fix with a test that pins the *intent*, and the two-token behaviour is settled by an
     * elevated `loopky login` on a real machine followed by a non-elevated `loopky whoami`.
     */
    private fun assertOwnerOnly(file: Path) {
        if (posix) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file),
                "left at ${Files.getPosixFilePermissions(file)}",
            )
            return
        }
        val view = Files.getFileAttributeView(file, AclFileAttributeView::class.java)
        val principals = view.acl.map { it.principal() }.toSet()
        assertEquals(setOf(expectedPrincipal()), principals, "the DACL names something other than this user: $principals")
        // Whatever the DACL says, this process has to still be able to use the file — the failure
        // being guarded is precisely one that reads as correct and denies the user.
        assertTrue(Files.isReadable(file) && Files.isWritable(file), "owner-only locked this user out of $file")
    }

    /** The account the tests run as, resolved the way `OwnerOnly.restrictAcl` resolves it. */
    private fun expectedPrincipal(): UserPrincipal {
        val user = requireNotNull(System.getProperty("user.name")) { "no user.name" }
        val lookup = FileSystems.getDefault().userPrincipalLookupService
        val domain = System.getenv("USERDOMAIN")?.takeIf { it.isNotBlank() }
        return runCatching { lookup.lookupPrincipalByName(if (domain == null) user else "$domain\\$user") }
            .getOrElse { lookup.lookupPrincipalByName(user) }
    }

    private val posix: Boolean =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
}
