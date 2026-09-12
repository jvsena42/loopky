package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.InstallMethod
import com.github.jvsena42.loopky.cli.Installation
import com.github.jvsena42.loopky.cli.SupportedHost
import com.github.jvsena42.loopky.cli.UpdateChecker
import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.nexus.HttpRequest
import com.github.jvsena42.loopky.data.nexus.HttpResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `loopky update` (#209) — everything up to the point where it would fetch bytes.
 *
 * The download itself is not exercised here: it is a `HttpURLConnection` against a real release
 * asset, and the release workflow's smoke test is where an actual artifact gets to prove it. What
 * is pinned here is the set of decisions taken *before* anything is downloaded, because each one
 * of them is a way for a self-updater to do something wrong quietly.
 */
class UpdateCommandTest {

    private fun checker(body: String?, version: String = "0.8.0"): UpdateChecker {
        val fetcher = object : HttpFetcher {
            override suspend fun send(request: HttpRequest): Result<HttpResponse> =
                body?.let { Result.success(HttpResponse(200, it)) } ?: Result.failure(IOException("offline"))
        }
        return UpdateChecker(
            configHome = Files.createTempDirectory("loopky-update-cmd"),
            fetcher = fetcher,
            currentVersion = version,
        )
    }

    private fun binary(path: String = "/home/agent/.local/bin/loopky") =
        Installation(InstallMethod.Binary, Path.of(path))

    private fun field(result: com.github.jvsena42.loopky.cli.CommandResult, name: String) =
        result.data.jsonObject.getValue(name).jsonPrimitive.content

    @Test
    fun `--check reports without applying anything`() = runTest {
        val result = update(
            Args.parse(arrayOf("update", "--check")),
            checker("""{"version":"0.9.0","schema":2}"""),
            binary(),
        )
        assertEquals("0.9.0", field(result, "latest"))
        assertEquals("0.8.0", field(result, "current"))
        assertTrue(field(result, "update_available").toBoolean())
        assertTrue(field(result, "schema_changed").toBoolean())
        assertFalse(field(result, "applied").toBoolean())
        assertEquals("binary", field(result, "install"))
    }

    @Test
    fun `being current is an ordinary success, not a failure`() = runTest {
        val result = update(
            Args.parse(arrayOf("update")),
            checker("""{"version":"0.8.0","schema":1}"""),
            binary(),
        )
        assertFalse(field(result, "update_available").toBoolean())
        assertFalse(field(result, "applied").toBoolean())
        assertTrue(result.text.contains("latest release"))
    }

    /**
     * A check that could not complete changed nothing, so it exits 0. No egress and an allowlist
     * proxy are ordinary in the environments this runs in, and neither is a reason for a command
     * that touched nothing to report a fault.
     */
    @Test
    fun `an unreachable release page is reported, not thrown`() = runTest {
        val result = update(Args.parse(arrayOf("update")), checker(null), binary())
        assertFalse(field(result, "applied").toBoolean())
        assertEquals("null", result.data.jsonObject.getValue("latest").toString())
    }

    /**
     * The refusal that matters: a managed install exits **11**, never 0. An agent that asked for
     * an update and got a zero would carry on believing it had one, which is the exact failure the
     * whole check exists to prevent.
     */
    @Test
    fun `a package-managed install refuses with the right command and a non-zero code`() = runTest {
        listOf(
            InstallMethod.Homebrew to "brew upgrade",
            InstallMethod.Debian to "dpkg -i",
            InstallMethod.Container to "docker pull",
            InstallMethod.Jar to "jar distribution",
        ).forEach { (method, advice) ->
            val error = assertFailsWith<CliError> {
                update(
                    Args.parse(arrayOf("update")),
                    checker("""{"version":"0.9.0","schema":1}"""),
                    Installation(method, Path.of("/usr/bin/loopky")),
                )
            }
            assertEquals(ExitCode.UpdateUnsupported, error.exitCode, "$method must not exit 0")
            assertTrue(error.message.orEmpty().contains(advice), "$method should be told to run $advice")
        }
    }

    @Test
    fun `a binary that cannot locate itself refuses rather than writing over a guess`() = runTest {
        val error = assertFailsWith<CliError> {
            update(
                Args.parse(arrayOf("update")),
                checker("""{"version":"0.9.0","schema":1}"""),
                Installation(InstallMethod.Unknown, null),
            )
        }
        assertEquals(ExitCode.UpdateUnsupported, error.exitCode)
    }

    /**
     * The applied path, end to end and without a network — the one that writes over an executable.
     *
     * `advice` has to come back **empty**: it is the *next* action, and applying the update is what
     * makes there be none. Reported unchanged it reads "applied: true, advice: Run `loopky
     * update`", and an agent treating the field as its next step — which is exactly what the field
     * invites — runs the whole forced, uncached check again to be told it is current.
     */
    @Test
    fun `a successful update replaces the binary and leaves no next action`() = runTest {
        val dir = Files.createTempDirectory("loopky-applied")
        val target = dir.resolve("loopky")
        Files.writeString(target, "the old binary")

        val result = update(
            Args.parse(arrayOf("update")),
            checker("""{"version":"0.9.0","schema":1}"""),
            Installation(InstallMethod.Binary, target),
        ) { version ->
            assertEquals("0.9.0", version, "it downloads the version it reported, not `latest`")
            "the new binary".toByteArray()
        }

        assertTrue(field(result, "applied").toBoolean())
        assertTrue(field(result, "verified").toBoolean())
        assertEquals("", field(result, "advice"), "there is no next action once it is applied")
        assertEquals("the new binary", Files.readString(target))
        assertTrue(Files.isExecutable(target))
    }

    /** `--check` answers on a managed install too — asking is never refused, only doing. */
    @Test
    fun `--check on a managed install answers instead of refusing`() = runTest {
        val result = update(
            Args.parse(arrayOf("update", "--check")),
            checker("""{"version":"0.9.0","schema":1}"""),
            Installation(InstallMethod.Homebrew, Path.of("/opt/homebrew/Cellar/loopky/0.8.0/bin/loopky")),
        )
        assertTrue(field(result, "update_available").toBoolean())
        assertFalse(field(result, "can_self_update").toBoolean())
        assertTrue(field(result, "advice").contains("brew upgrade"))
    }
}

/**
 * The destructive half of `loopky update`, exercised without a network: what actually happens to
 * the file on disk.
 */
class ReplaceInPlaceTest {

    @Test
    fun `the digest is the one sha256sum would print`() {
        // `echo -n abc | sha256sum`. Pinned against an external tool rather than against itself,
        // because this value is compared with a digest GitHub published — a hex encoding that
        // drops a leading zero would match nothing and be invisible in a self-consistent test.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".toByteArray()),
        )
    }

    @Test
    fun `replacing a binary leaves the new contents, executable`() {
        val dir = Files.createTempDirectory("loopky-replace")
        val target = dir.resolve("loopky")
        Files.writeString(target, "the old binary")

        replaceInPlace(target, "the new binary".toByteArray())

        assertEquals("the new binary", Files.readString(target))
        assertTrue(Files.isExecutable(target))
        assertEquals(
            listOf(target),
            Files.list(dir).use { it.toList() },
            "no temp file left behind beside it",
        )
    }

    /**
     * A read-only directory is the container layer and the root-owned `/usr/bin` case, and it has
     * to arrive as the same honest refusal as a package-managed install — not as an internal
     * error, and never as a partially written executable.
     */
    @Test
    fun `an unwritable directory refuses without touching anything`() {
        // The directory is made unwritable through POSIX modes, which Windows does not have (#301).
        // Refusing to replace a *running* `.exe` is the case that matters there, and it is its own
        // test rather than this one wearing a second hat.
        assumeTrue(
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
            "needs POSIX file permissions",
        )
        val dir = Files.createTempDirectory("loopky-replace-ro")
        val target = dir.resolve("loopky")
        Files.writeString(target, "the old binary")
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val error = assertFailsWith<CliError> { replaceInPlace(target, "new".toByteArray()) }
            assertEquals(ExitCode.UpdateUnsupported, error.exitCode)
            assertEquals("the old binary", Files.readString(target))
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }
}

/**
 * The verification policy, which is the security-critical half of `loopky update`: this is the one
 * command that fetches an executable and then runs it as the user.
 */
class VerifiedDownloadTest {

    private val checker = UpdateChecker(configHome = Files.createTempDirectory("loopky-verify"))
    private val binary = "the new binary".toByteArray()
    private val digest = sha256(binary)

    private suspend fun fetch(get: (String) -> ByteArray) =
        fetchVerifiedBinary(checker, "0.9.0", SupportedHost.LinuxX64, get)

    @Test
    fun `a matching digest is accepted, in the format sha256sum publishes`() = runTest {
        // `sha256sum` writes `<hex>  <filename>`, so the filename has to be dropped.
        val bytes = fetch { url -> if (url.endsWith(".sha256")) "$digest  loopky\n".toByteArray() else binary }
        assertContentEquals(binary, bytes)
    }

    @Test
    fun `a mismatched digest is refused and nothing is returned`() = runTest {
        val error = assertFailsWith<CliError> {
            fetch { url -> if (url.endsWith(".sha256")) "${"0".repeat(64)}  loopky".toByteArray() else binary }
        }
        assertEquals(ExitCode.Internal, error.exitCode)
        assertTrue(error.message.orEmpty().contains("checksum mismatch"))
    }

    /**
     * A genuinely absent digest is a malformed release, and refusing is right — `install.sh`
     * degrades to "digest NOT checked" on a minimal host because its alternative is a plain `curl`
     * with no check at all; here the alternative is simply not updating.
     */
    @Test
    fun `a 404 on the digest is a malformed release`() = runTest {
        val error = assertFailsWith<CliError> {
            fetch { url ->
                if (url.endsWith(".sha256")) throw CliError(ExitCode.NotFound, "$url does not exist")
                binary
            }
        }
        assertEquals(ExitCode.Internal, error.exitCode)
        assertTrue(error.message.orEmpty().contains("no published checksum"))
    }

    /**
     * But a 503 or a read timeout from the object store is an ordinary blip. Reported as a missing
     * checksum it reads as a supply-chain warning and points at a release-integrity investigation,
     * with exit 1 telling an agent this is an internal bug rather than something worth retrying.
     */
    @Test
    fun `a transport failure on the digest keeps its own diagnosis and exit code`() = runTest {
        val error = assertFailsWith<CliError> {
            fetch { url ->
                if (url.endsWith(".sha256")) throw CliError(ExitCode.Network, "HTTP 503 fetching $url")
                binary
            }
        }
        assertEquals(ExitCode.Network, error.exitCode)
        assertTrue(error.message.orEmpty().contains("503"))
    }

    /**
     * 11 means one thing — "this install is owned by something else, use that tool". A full disk or
     * a filesystem with no atomic rename told 11 makes an agent conclude it is on Homebrew and stop
     * retrying, when the fix is to free space.
     */
    @Test
    fun `only a permission failure is exit 11`() {
        val target = Path.of("/home/agent/.local/bin/loopky")
        assertEquals(
            ExitCode.UpdateUnsupported,
            replaceFailed(target, AccessDeniedException(target.toString())).exitCode,
        )
        assertEquals(
            ExitCode.UpdateUnsupported,
            replaceFailed(target, FileSystemException(target.toString(), null, "Read-only file system")).exitCode,
        )
        assertEquals(
            ExitCode.Internal,
            replaceFailed(target, FileSystemException(target.toString(), null, "No space left on device")).exitCode,
        )
        assertEquals(
            ExitCode.Internal,
            replaceFailed(target, AtomicMoveNotSupportedException(target.toString(), null, "cross-device")).exitCode,
        )
    }
}
