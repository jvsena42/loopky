package com.github.jvsena42.loopky.data.storage

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The DPAPI item's decisions, with the two native calls faked (#301).
 *
 * **What this can and cannot prove.** Everything around `CryptProtectData` is ordinary file work —
 * where the blob goes, that a half-written one cannot be read, that a blob which will not decrypt is
 * reported as a failure rather than as an absence — and all of it is host-independent, so it is
 * tested here and runs on every row. The two native calls themselves are exercised only on
 * `windows-latest`, by the round trip below — and **only on the JVM**. The binary's smoke steps run
 * `--version`, `whoami`, `login --url-only` and an `.apkg` dry run, none of which stores a session,
 * so the *native image* never reaches `CryptProtectData`. That `Function.getFunction("crypt32", …)`
 * resolves inside a closed-world image is therefore inferred from `RustLog.kt` doing the same thing
 * with `SetEnvironmentVariableW`, not observed. Observing it needs a session write in the image,
 * which today means a real sign-in — the `journeys/RESULTS.md` item on #301.
 *
 * That split is deliberate rather than a limitation accepted quietly: a fake that round-trips
 * proves the *store* is right, and a fake can never say anything about whether the blob is actually
 * encrypted — which is why the round trip is a separate test that refuses to pass by being skipped.
 */
class DpapiSecureItemTest {

    private val home: Path = Files.createTempDirectory("loopky-dpapi")
    private val blob: Path get() = home.resolve(SESSION_BLOB_FILE)

    @AfterTest
    fun cleanUp() {
        home.toFile().deleteRecursively()
    }

    @Test
    fun `a written value comes back`() {
        val item = DpapiSecureItem(blob, ReversingCrypto())

        assertTrue(item.write("c2Vzc2lvbg==").isSuccess)

        assertEquals(SecureItemRead.Found("c2Vzc2lvbg=="), item.read())
    }

    @Test
    fun `no blob is Missing, not a failure`() {
        assertIs<SecureItemRead.Missing>(DpapiSecureItem(blob, ReversingCrypto()).read())
    }

    /**
     * The distinction that decides what a person is told to do. A blob written by another account —
     * or carried over from a reimaged machine — never decrypts, and reporting it as "there is no
     * session" sends them to `loopky login`, which writes a new blob and leaves the old failure
     * exactly where it was on the next profile.
     */
    @Test
    fun `a blob that will not decrypt is Failed, never Missing`() {
        DpapiSecureItem(blob, ReversingCrypto()).write("c2Vzc2lvbg==")

        val read = DpapiSecureItem(blob, RefusingCrypto()).read()

        val failed = assertIs<SecureItemRead.Failed>(read)
        assertContains(failed.message, "another account")
    }

    @Test
    fun `a refused encryption is a failed Result and leaves nothing behind`() {
        val item = DpapiSecureItem(blob, RefusingCrypto())

        assertTrue(item.write("c2Vzc2lvbg==").isFailure)

        assertFalse(Files.exists(blob), "a refused write must not leave a blob")
        assertEquals(emptyList(), Files.list(home).use { it.toList() }, "and no temp file either")
    }

    /**
     * **The cleanup path, which the refusal test above cannot reach.** There, `protect` returns null
     * and `write` fails before a temp file exists, so its "no temp file either" assertion passes
     * trivially. This one gets past `protect`, writes the temp blob, and then fails the rename —
     * which is the only way to exercise the `finally` that removes it. Without that cleanup an
     * *encrypted* temp file stays in the config home for good, invisible because nothing reads it.
     *
     * A non-empty directory standing in for the destination is the portable way to make
     * `ATOMIC_MOVE` fail: no permissions games, and it fails the same way on every row.
     */
    @Test
    fun `a failed rename leaves no temp blob behind`() {
        Files.createDirectory(blob)
        Files.createFile(blob.resolve("occupied"))
        val item = DpapiSecureItem(blob, ReversingCrypto())

        assertTrue(item.write("c2Vzc2lvbg==").isFailure)

        val left = Files.list(home).use { it.toList() }
        assertEquals(listOf(blob), left, "a temp blob was left behind: $left")
    }

    /**
     * [SecureItem.exists] must answer without moving the secret. On this row that is free — the
     * question is whether a file is there — and pinning it stops someone "simplifying" it into a
     * `read() is Found`, which would decrypt the session to answer a question about presence.
     */
    @Test
    fun `exists does not decrypt`() {
        val crypto = ReversingCrypto()
        val item = DpapiSecureItem(blob, crypto)
        item.write("c2Vzc2lvbg==")
        val unprotectsAfterWrite = crypto.unprotects

        assertTrue(item.exists() == true)

        assertEquals(unprotectsAfterWrite, crypto.unprotects, "exists() decrypted the blob")
    }

    @Test
    fun `deleting removes the blob, and deleting nothing is still a success`() {
        val item = DpapiSecureItem(blob, ReversingCrypto())
        item.write("c2Vzc2lvbg==")

        assertTrue(item.delete().isSuccess)
        assertFalse(Files.exists(blob))
        assertTrue(item.delete().isSuccess, "an item that was already gone is not a failure")
        assertIs<SecureItemRead.Missing>(item.read())
    }

    /** A second sign-in replaces the blob rather than appending to it or leaving a temp beside it. */
    @Test
    fun `a second write replaces the first and leaves one file`() {
        val item = DpapiSecureItem(blob, ReversingCrypto())

        item.write("Zmlyc3Q=")
        item.write("c2Vjb25k")

        assertEquals(SecureItemRead.Found("c2Vjb25k"), item.read())
        assertEquals(listOf(blob), Files.list(home).use { it.toList() })
    }

    /**
     * The blob holds a live credential, so it gets the same treatment as every other secret this
     * project writes. Asserted only where the host can express it — see [OwnerOnly], which returns
     * false rather than pretending on a filesystem that cannot.
     */
    @Test
    fun `the blob is owner-only where the host can say so`() {
        DpapiSecureItem(blob, ReversingCrypto()).write("c2Vzc2lvbg==")

        val posix = Files.getFileAttributeView(blob, PosixFileAttributeView::class.java)
        if (posix == null) {
            // Windows spells this with an ACL, which [OwnerOnly] covers in its own tests. The
            // precondition is positive on purpose: without it this test would pass by asserting
            // nothing on a host with neither view, which is the shape it exists to rule out.
            assertTrue(OwnerOnly.supported, "neither view here, so this test checks nothing")
            return
        }
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(posix.readAttributes().permissions()),
        )
    }

    /** The ciphertext is what lands on disk — not the value, however the fake transforms it. */
    @Test
    fun `what is on disk is not the plaintext`() {
        DpapiSecureItem(blob, ReversingCrypto()).write("c2Vzc2lvbg==")

        assertFalse(Files.readAllBytes(blob).toString(Charsets.UTF_8).contains("c2Vzc2lvbg=="))
    }

    /**
     * **The only test that calls `CryptProtectData`, and it runs on one host.** The fakes above
     * prove the store's decisions; this proves the binding — the `DATA_BLOB` layout, the flags, and
     * that the buffer Windows hands back is read before it is freed. A wrong pointer offset here is
     * a segfault rather than a wrong answer, which is precisely what a fake cannot tell you.
     */
    @Test
    fun `a real DPAPI round trip, on Windows`() {
        // `assumeTrue`, not an early `return`: a return records this as **passed** on Linux and
        // macOS, which is exactly what made a green `:shared:jvmTest` on Windows indistinguishable
        // from one where crypt32 was never called. Skipped is the honest record off Windows, and it
        // is what the CI step asserting this case ran reads to tell the two apart.
        // JUnit 4's `Assume`, like the sibling test in this source set — `:shared`'s jvmTest has no
        // Jupiter on it, and this overload takes the message first.
        assumeTrue("the real DPAPI round trip needs Windows", dpapiEligible())
        val item = DpapiSecureItem(blob)
        assertTrue(item.write("c2Vzc2lvbg==").isSuccess, "CryptProtectData refused")
        assertEquals(SecureItemRead.Found("c2Vzc2lvbg=="), item.read())
        // Encrypted rather than merely stored: the point of the row.
        assertFalse(Files.readAllBytes(blob).toString(Charsets.UTF_8).contains("c2Vzc2lvbg=="))
        assertTrue(item.delete().isSuccess)
        assertIs<SecureItemRead.Missing>(item.read())
    }

    /** Reversible and obviously not encryption, so a test asserting "not plaintext" still means it. */
    private class ReversingCrypto : DpapiCrypto {
        var unprotects = 0
            private set

        override fun protect(plaintext: ByteArray): ByteArray? = plaintext.reversedArray()

        override fun unprotect(ciphertext: ByteArray): ByteArray? {
            unprotects++
            return ciphertext.reversedArray()
        }
    }

    /** A host where DPAPI answers nothing — another account's blob, or a missing master key. */
    private class RefusingCrypto : DpapiCrypto {
        override fun protect(plaintext: ByteArray): ByteArray? = null
        override fun unprotect(ciphertext: ByteArray): ByteArray? = null
    }

    @Test
    fun `windows gets dpapi and nothing else does`() {
        assertTrue(dpapiEligible("Windows 11"))
        assertTrue(dpapiEligible("Windows Server 2022"))
        assertFalse(dpapiEligible("Linux"))
        assertFalse(dpapiEligible("Mac OS X"))
    }

    /**
     * The asymmetry with [keychainEligible], pinned so it is not "fixed" into consistency later.
     *
     * A Keychain item is addressed by service name and shared by every config home, so an explicit
     * `LOOPKY_CONFIG_HOME` has to disable it. This blob lives *inside* the config home, so a
     * disposable home already gets a disposable blob — and gating it would silently bypass DPAPI in
     * CI, where every smoke step sets that variable.
     *
     * [home] is a temp directory and therefore never the platform default, which is exactly the
     * condition that switches the Keychain off. So a DPAPI branch that grew the same gate would
     * return null here and fail this test, rather than being caught by nobody.
     */
    @Test
    fun `dpapi is not gated on the config home, unlike the keychain`() {
        assertIs<DpapiSecureItem>(defaultSecureItem(home, osName = "Windows 11"))
        assertNull(defaultSecureItem(home, osName = "Linux"))
    }
}
