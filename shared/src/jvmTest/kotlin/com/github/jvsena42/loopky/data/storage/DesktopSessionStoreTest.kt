package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.platform.DesktopNativeRow
import com.github.jvsena42.loopky.platform.desktopNativeRow
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSessionStoreTest {

    private val home: Path = Files.createTempDirectory("loopky-session-store")

    @AfterTest
    fun cleanUp() {
        home.toFile().deleteRecursively()
    }

    // --- which store, on which host ------------------------------------------

    @Test
    fun `linux keeps the session in the file, whatever else is installed`() {
        assertFalse(keychainEligible(home, macOs = false, toolPresent = true, default = home))
    }

    @Test
    fun `a mac with no security tool falls back rather than failing`() {
        assertFalse(keychainEligible(home, macOs = true, toolPresent = false, default = home))
    }

    /**
     * `LOOPKY_CONFIG_HOME` exists so a container or a test can point state somewhere disposable. A
     * Keychain item is not disposable and is shared by every config home, so honouring it there
     * would make `LOOPKY_CONFIG_HOME=/tmp/x loopky login` overwrite the caller's real session.
     */
    @Test
    fun `an explicit config home keeps the session in it`() {
        assertTrue(keychainEligible(home, macOs = true, toolPresent = true, default = home))
        assertFalse(
            keychainEligible(home, macOs = true, toolPresent = true, default = Paths.get("/somewhere/else")),
        )
    }

    @Test
    fun `an ineligible host gets the plain file store`() {
        val store = desktopSecureSessionStore(home, secretsStore(home), keychain = null)
        assertEquals(home.resolve("secrets.json").toString(), store.location)
    }

    // --- the keychain wrapper ------------------------------------------------

    @Test
    fun `a saved session comes back, and does not stay in the file`() = runTest {
        val keychain = FakeKeychain()
        val store = desktopSecureSessionStore(home, secretsStore(home), keychain)

        store.save(SESSION)

        assertEquals(SESSION, store.load())
        assertNull(FileSecureSessionStore(secretsStore(home)).load())
    }

    /**
     * Every macOS install predating #213 has a live session in `secrets.json`. An upgrade that
     * silently signed everybody out would be read as a bug, so the first read adopts it — and
     * clears the file, because the one rule here is that a usable credential never sits in two
     * places.
     */
    @Test
    fun `a session stored by an older version is adopted into the keychain`() = runTest {
        val secrets = secretsStore(home)
        FileSecureSessionStore(secrets).save(SESSION)
        val keychain = FakeKeychain()

        val store = desktopSecureSessionStore(home, secrets, keychain)

        assertEquals(SESSION, store.load())
        assertNotNull(keychain.value)
        assertNull(FileSecureSessionStore(secretsStore(home)).load())
    }

    @Test
    fun `a keychain that will not write leaves the session in the file`() = runTest {
        val keychain = FakeKeychain(writable = false)
        val store = desktopSecureSessionStore(home, secretsStore(home), keychain)

        store.save(SESSION)

        assertNull(keychain.value)
        assertEquals(SESSION, store.load())
        assertEquals(SESSION, FileSecureSessionStore(secretsStore(home)).load())
    }

    /**
     * The failure that is worse than not storing the session: storing it *somewhere the reader
     * does not look first*. [MacKeychainSessionStore.load] reads the Keychain before the file, so
     * a second sign-in whose write fails used to leave the previous account winning every command
     * afterwards — with `session_live` true, because that session really is live.
     *
     * Starting from a populated keychain is the whole test. The `writable = false` case above
     * starts from an empty one, falls through to the file, and looks correct.
     */
    @Test
    fun `a failed write does not let the previous account win`() = runTest {
        val secrets = secretsStore(home)
        val keychain = FakeKeychain()
        desktopSecureSessionStore(home, secrets, keychain).save(SESSION)

        val locked = FakeKeychain(value = keychain.value, writable = false)
        val store = desktopSecureSessionStore(home, secretsStore(home), locked)
        store.save(SECOND)

        // The stale item may still be sitting there — nothing could remove it — and that is fine
        // as long as the reader never picks it.
        assertEquals(SECOND, store.load())
    }

    /**
     * The host the fallback was written for: a Keychain that answers neither a write nor a delete,
     * over SSH or under `launchd`. Refusing here — which is what deleting-before-falling-back
     * amounts to, since the two calls fail together — would mean `loopky login` no longer works at
     * all on it, which is a worse outcome than the stale item the refusal was protecting against.
     */
    @Test
    fun `a keychain that answers nothing still signs you in`() = runTest {
        val keychain = FakeKeychain(value = "c3RhbGU=", writable = false, deletable = false, readable = false)
        val store = desktopSecureSessionStore(home, secretsStore(home), keychain)

        store.save(SESSION)

        assertEquals(SESSION, store.load())
        assertEquals(SESSION, FileSecureSessionStore(secretsStore(home)).load())
    }

    /**
     * The cleanup half of the ordering: the stale item is not deleted when the write fails, so
     * something has to remove it once the Keychain answers again. That is the same migration call
     * that moves a pre-#213 file session up.
     */
    @Test
    fun `the stale item is overwritten once the keychain answers again`() = runTest {
        val secrets = secretsStore(home)
        desktopSecureSessionStore(home, secrets, FakeKeychain(writable = false)).save(SESSION)
        val recovered = FakeKeychain(value = "c3RhbGU=")

        val store = desktopSecureSessionStore(home, secretsStore(home), recovered)

        assertEquals(SESSION, store.load())
        assertNull(FileSecureSessionStore(secretsStore(home)).load())
        assertEquals(SESSION, desktopSecureSessionStore(home, secretsStore(home), recovered).load())
    }

    /**
     * A refused delete is only a failure if there is something to fail about. Without this, a
     * clean sign-out on a host whose session was only ever in the file reports a credential that
     * is genuinely gone as one that survived.
     */
    @Test
    fun `clearing succeeds when the keychain holds nothing, whatever it says about deleting`() = runTest {
        val store = desktopSecureSessionStore(home, secretsStore(home), FakeKeychain(deletable = false))
        store.clear()
    }

    /**
     * `clear()` promises the credential is gone. It used to swallow a refused delete, which is how
     * `loopky logout` came to print "Signed out." over an item still in the Keychain.
     */
    @Test
    fun `clearing reports a keychain that will not give the credential up`() = runTest {
        val secrets = secretsStore(home)
        // Still `Found`, so the read-gate above cannot excuse it.
        val keychain = FakeKeychain(value = "c3RpbGwtaGVyZQ==", deletable = false)
        val store = desktopSecureSessionStore(home, secrets, keychain)

        assertFailsWith<IllegalStateException> { store.clear() }
        // The file is emptied first regardless, so a failure never leaves it in two places.
        assertNull(FileSecureSessionStore(secretsStore(home)).load())
    }

    @Test
    fun `a keychain that will not answer reads the file instead, and says so`() = runTest {
        val secrets = secretsStore(home)
        FileSecureSessionStore(secrets).save(SESSION)
        val store = desktopSecureSessionStore(home, secrets, FakeKeychain(readable = false))

        assertEquals(SESSION, store.load())
        assertContains(store.location, "not answering")
    }

    @Test
    fun `clearing empties both places`() = runTest {
        val secrets = secretsStore(home)
        val keychain = FakeKeychain()
        val store = desktopSecureSessionStore(home, secrets, keychain)
        store.save(SESSION)
        // Put a stale copy back to prove `clear` does not trust the invariant it maintains.
        FileSecureSessionStore(secrets).save(SESSION)

        store.clear()

        assertNull(keychain.value)
        assertNull(store.load())
        assertNull(FileSecureSessionStore(secretsStore(home)).load())
    }

    @Test
    fun `an item that is not a session is treated as absent rather than fatal`() = runTest {
        val keychain = FakeKeychain(value = "bm90LWEtc2Vzc2lvbg==")
        val store = desktopSecureSessionStore(home, secretsStore(home), keychain)

        assertNull(store.load())
    }

    /**
     * The value has to survive `security -i`'s shell-like line splitting unquoted, which is what
     * keeps the secret out of `argv` — see [SecurityCliKeychain].
     */
    @Test
    fun `what is handed to the keychain is one argv-safe token`() = runTest {
        val keychain = FakeKeychain()
        val store = desktopSecureSessionStore(home, secretsStore(home), keychain)

        store.save(SESSION.copy(identity = SESSION.identity.copy(displayName = "Ana \"la\" Peña")))

        val written = assertNotNull(keychain.value)
        assertTrue(written.all { it.isLetterOrDigit() || it in "+/=" }, written)
    }

    @Test
    fun `the file the fallback writes is the same one the file store owns`() = runTest {
        val store = desktopSecureSessionStore(home, secretsStore(home), FakeKeychain(writable = false))

        store.save(SESSION)

        assertContains(home.resolve("secrets.json").readText(), "session.v1")
    }

    // --- the real thing, on the one host that has it -------------------------

    /**
     * The only test that exercises `security(1)`, and it runs nowhere else. The fake above proves
     * the wrapper's decisions; this proves the protocol — `-i` on stdin, Base64 in, exit 44 for a
     * missing item — which is the half a fake cannot say anything about.
     *
     * It does **not** prove the native image can spawn a subprocess at all; that is checked by
     * running the built binary (`journeys/RESULTS.md`, the macOS row).
     */
    @Test
    fun `a real keychain round trip, on a Mac`() {
        if (desktopNativeRow() != DesktopNativeRow.MacArm64) return
        // **Opt-in, and it has to be.** This is the only test in the repo that mutates the
        // developer's *login keychain*, and creating an item there can raise a macOS
        // authorization dialog — which `securityd` then serialises every other caller behind,
        // `gh`'s token read included. A suite that pops a password prompt on `./gradlew test` is
        // a suite people stop running. Turn it on deliberately:
        //
        //     LOOPKY_KEYCHAIN_TESTS=1 ./gradlew :shared:jvmTest --tests '*DesktopSessionStore*'
        //
        // Everything the wrapper decides is covered by the fake above; what this adds is the
        // `security(1)` protocol itself, which is worth running by hand when that changes.
        if (System.getenv("LOOPKY_KEYCHAIN_TESTS").isNullOrBlank()) {
            println("skipping the real keychain round trip: set LOOPKY_KEYCHAIN_TESTS=1 to run it")
            return
        }
        val keychain = SecurityCliKeychain(service = "loopky.test.${System.nanoTime()}")
        try {
            assertIs<KeychainRead.Missing>(keychain.read())
            if (keychain.write("aGVsbG8=").wedged()) return
            assertEquals(KeychainRead.Found("aGVsbG8="), keychain.read())
            // getOrThrow, not isSuccess: `security`'s own message is the only useful diagnostic
            // when this breaks, and a bare boolean throws it away.
            if (keychain.write("d29ybGQ=").wedged()) return
            assertEquals(KeychainRead.Found("d29ybGQ="), keychain.read())
        } finally {
            keychain.delete()
        }
        assertIs<KeychainRead.Missing>(keychain.read())
    }

    /**
     * A timeout here is the machine, not the code, and the difference has to be drawn narrowly.
     *
     * `securityd` serialises every request behind an authorization dialog, so anything that
     * raises one — including another test run hammering the keychain in parallel — wedges every
     * caller until it is answered. That is exactly the hang the bound exists for, and failing the
     * suite over it would make this test flaky for a reason no change to this file can fix.
     * **Only** a timeout is excused: any other failure is `security` answering, which is the code's
     * business, so it still throws.
     */
    private fun Result<Unit>.wedged(): Boolean {
        val failure = exceptionOrNull() ?: return false
        if (failure.message?.contains("no answer in") != true) throw failure
        println("skipping the real keychain round trip: securityd is not answering (${failure.message})")
        return true
    }

    /**
     * The bound has to actually fire, which is not what the code said before: draining both pipes
     * inline meant `waitFor` was only ever reached by a process that had already exited, so the
     * timeout could never be produced. `security find-generic-password` really does block
     * indefinitely on an unlock prompt nobody can answer over SSH, and unbounded that is a
     * `loopky whoami` that never returns.
     *
     * A `sleep` standing in for `security`, so this needs no keychain and runs anywhere POSIX.
     */
    @Test
    fun `a security that never answers is cut off rather than waited on`() {
        // The stand-in is a shebang script, so this needs a POSIX shell to run it at all (#301).
        // On Windows the spawn fails for its own reason and the assertion below would be checking
        // the wrong failure.
        assumeTrue("needs a POSIX shell to stand in for security(1)", Files.isExecutable(Paths.get("/bin/sh")))
        val sleeper = Files.createTempFile("fake-security", ".sh")
        sleeper.toFile().writeText("#!/bin/sh\nsleep 30\n")
        sleeper.toFile().setExecutable(true)

        val started = System.nanoTime()
        val read = SecurityCliKeychain(security = sleeper, timeoutSeconds = 1).read()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertIs<KeychainRead.Failed>(read)
        assertContains(read.message, "no answer in 1s")
        assertTrue(elapsedMs < 15_000, "gave up after ${elapsedMs}ms, so the bound did not fire")
        Files.deleteIfExists(sleeper)
    }

    private class FakeKeychain(
        var value: String? = null,
        private val readable: Boolean = true,
        private val writable: Boolean = true,
        private val deletable: Boolean = true,
    ) : Keychain {
        override val location = "a fake keychain"

        var secretsRead = 0
            private set

        override fun read(): KeychainRead {
            secretsRead++
            return when {
                !readable -> KeychainRead.Failed("no")
                else -> value?.let { KeychainRead.Found(it) } ?: KeychainRead.Missing
            }
        }

        override fun exists(): Boolean? = if (!readable) null else value != null

        override fun write(value: String): Result<Unit> {
            if (!writable) return Result.failure(IllegalStateException("no"))
            this.value = value
            return Result.success(Unit)
        }

        override fun delete(): Result<Unit> {
            if (!deletable) return Result.failure(IllegalStateException("no"))
            value = null
            return Result.success(Unit)
        }
    }

    /**
     * The probes ask about presence, and presence is not the password. Pinned because the shape
     * that answers both — `find-generic-password -w` — is the obvious one to reach for again.
     */
    @Test
    fun `the probes do not pull the secret out of the keychain`() = runTest {
        val keychain = FakeKeychain(value = "c2Vzc2lvbg==")
        val store = desktopSecureSessionStore(home, secretsStore(home), keychain)

        store.location
        store.clear()

        assertEquals(0, keychain.secretsRead, "location and clear only need to know it is there")
    }

    /**
     * The one arrangement file-first cannot survive: the Keychain holding the **new** session and
     * the file an **older** one. `JsonFileStore.persist` rethrows, so a full or read-only disk can
     * produce it — and it does not stay loud, because once the disk recovers `storedInFile()`
     * migrates the older credential back over the newer one and nothing is left to notice.
     */
    @Test
    fun `a file that will not clear keeps the newer session, not the older one`() = runTest {
        val secrets = secretsStore(home)
        val keychain = FakeKeychain()
        desktopSecureSessionStore(home, secrets, keychain).save(SESSION)

        val unclearable = object : SecureSessionStore by FileSecureSessionStore(secretsStore(home)) {
            override suspend fun clear() = throw java.io.IOException("no space left on device")
        }
        MacKeychainSessionStore(keychain, unclearable).save(SECOND)

        // Whatever the file ends up holding, it must not be the credential that is now stale.
        assertEquals(SECOND, MacKeychainSessionStore(keychain, FileSecureSessionStore(secretsStore(home))).load())
    }

    /**
     * `write` returns `Result`, and every caller treats it as total — `save()`'s fallback to the
     * file most of all. A guard that threw instead would take that fallback with it.
     */
    @Test
    fun `a value the write path cannot quote is a failed Result, not a thrown one`() {
        val keychain = SecurityCliKeychain(service = "loopky.unused", security = java.nio.file.Paths.get("/bin/echo"))
        val failure = keychain.write("not base64 — spaces and \"quotes\"").exceptionOrNull()
        assertIs<IllegalArgumentException>(failure)
    }

    private companion object {
        val SECOND = Session(
            identity = PubkyIdentity(pubky = "pk:second", displayName = null, avatarUrl = null, bio = null),
            sessionSecret = "pk:second:cookie",
            capabilities = listOf(Capability("/pub/loopky/:rw")),
            homeserver = "hs.example",
        )
        val SESSION = Session(
            identity = PubkyIdentity(pubky = "pk:abc", displayName = null, avatarUrl = null, bio = null),
            sessionSecret = "pk:abc:cookie",
            capabilities = listOf(Capability("/pub/loopky/:rw")),
            homeserver = "hs.example",
        )
    }
}
