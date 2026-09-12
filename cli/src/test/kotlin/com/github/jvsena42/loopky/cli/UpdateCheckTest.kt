package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.nexus.HttpRequest
import com.github.jvsena42.loopky.data.nexus.HttpResponse
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The version check (#209): what it reports, and — more of these — what it refuses to do.
 *
 * The check is allowed to be wrong about the world; it is never allowed to cost the caller
 * anything. So most of what is pinned here is the *absence* of behaviour: no second network call
 * inside the TTL, no failure surfacing as a failure, no "update available" from a string it could
 * not parse.
 */
class UpdateCheckTest {

    private class FakeFetcher(private val answer: (String) -> Result<HttpResponse>) : HttpFetcher {
        var calls = 0
            private set

        override suspend fun send(request: HttpRequest): Result<HttpResponse> {
            calls++
            return answer(request.url)
        }
    }

    private fun ok(body: String) = FakeFetcher { Result.success(HttpResponse(200, body)) }

    private fun tempHome(): Path = Files.createTempDirectory("loopky-update-test")

    private fun checker(
        home: Path,
        fetcher: HttpFetcher,
        now: Long = 1_000_000L,
        version: String = "0.8.0",
        schema: Int = 1,
    ) = UpdateChecker(
        configHome = home,
        fetcher = fetcher,
        now = { now },
        currentVersion = version,
        currentSchema = schema,
    )

    @Test
    fun `a newer release is reported with its version and schema`() = runTest {
        val home = tempHome()
        val update = checker(home, ok("""{"version":"0.9.0","schema":2}""")).check()
        assertNotNull(update)
        assertEquals("0.9.0", update.version)
        assertEquals(2, update.schema)
        assertTrue(update.schemaChanged, "a released schema of 2 against this binary's 1 is a louder fact")
    }

    @Test
    fun `the same version is not an update`() = runTest {
        val home = tempHome()
        assertNull(checker(home, ok("""{"version":"0.8.0","schema":1}""")).check())
    }

    @Test
    fun `an older release is not an update`() = runTest {
        val home = tempHome()
        assertNull(checker(home, ok("""{"version":"0.7.4","schema":1}""")).check())
    }

    /**
     * The whole reason the check may exist at all: it cannot make a command fail. A transport
     * error, a 404 from a release page with no manifest yet, and a body that is not the manifest
     * all have to arrive as "nothing to say".
     */
    @Test
    fun `a check that cannot complete says nothing at all`() = runTest {
        assertNull(checker(tempHome(), FakeFetcher { Result.failure(IOException("no route to host")) }).check())
        assertNull(checker(tempHome(), FakeFetcher { Result.success(HttpResponse(404, "Not Found")) }).check())
        assertNull(checker(tempHome(), ok("<html>a proxy login page</html>")).check())
    }

    @Test
    fun `the answer is cached for a day, so a second command makes no network call`() = runTest {
        val home = tempHome()
        val fetcher = ok("""{"version":"0.9.0","schema":1}""")
        assertNotNull(checker(home, fetcher, now = 0).check())
        assertEquals(1, fetcher.calls)

        val hourLater = checker(home, fetcher, now = 60L * 60 * 1000).check()
        assertNotNull(hourLater)
        assertEquals(1, fetcher.calls, "an hour later is still inside the TTL")

        assertNotNull(checker(home, fetcher, now = 25L * 60 * 60 * 1000).check())
        assertEquals(2, fetcher.calls, "a day later, it asks again")
    }

    /**
     * A *failed* check is cached too. Without this, a sandbox with no egress pays a DNS timeout on
     * every invocation — which is exactly the cost an agent doing 200 writes notices, and the
     * reason the TTL exists in the first place.
     */
    @Test
    fun `a failed check is remembered, so an offline box is not asked again every command`() = runTest {
        val home = tempHome()
        val fetcher = FakeFetcher { Result.failure(IOException("offline")) }
        assertNull(checker(home, fetcher, now = 0).check())
        assertNull(checker(home, fetcher, now = 60L * 1000).check())
        assertEquals(1, fetcher.calls)
        assertTrue(home.resolve("update-check.json").readText().contains("checked_at"))
    }

    @Test
    fun `update --check bypasses the cache, because it is a direct question`() = runTest {
        val home = tempHome()
        val fetcher = ok("""{"version":"0.9.0","schema":1}""")
        checker(home, fetcher, now = 0).check()
        checker(home, fetcher, now = 0).latest(force = true)
        assertEquals(2, fetcher.calls)
    }

    @Test
    fun `an unreadable cache file is treated as absent rather than fatal`() = runTest {
        val home = tempHome()
        Files.writeString(home.resolve("update-check.json"), "{ not json")
        val fetcher = ok("""{"version":"0.9.0","schema":1}""")
        assertNotNull(checker(home, fetcher).check())
        assertEquals(1, fetcher.calls)
    }

    /**
     * A comparison the code cannot make must resolve to "no update", never to "yes". The consumer
     * of this answer downloads and executes a file.
     */
    @Test
    fun `a version neither side can parse is never newer`() {
        assertFalse(UpdateChecker.isNewer("nightly", "0.8.0"))
        assertFalse(UpdateChecker.isNewer("0.9.0", "dev"))
        assertFalse(UpdateChecker.isNewer("", "0.8.0"))
    }

    @Test
    fun `versions compare by number, not by string`() {
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.99.99"))
        assertTrue(UpdateChecker.isNewer("v0.9.0", "0.8.0"))
        assertFalse(UpdateChecker.isNewer("0.9.0", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.9.0", "0.10.0"))
    }

    /** `latest` never points at a pre-release, so this only fires when the *installed* copy is one. */
    @Test
    fun `the release supersedes its own release candidate`() {
        assertTrue(UpdateChecker.isNewer("0.9.0", "0.9.0-rc1"))
        assertFalse(UpdateChecker.isNewer("0.9.0-rc1", "0.9.0"))
    }

    @Test
    fun `the check is switched off by a flag, by an environment variable, and for update itself`() {
        val plain = Args.parse(arrayOf("deck", "list"))
        assertTrue(UpdateChecker.enabled(plain) { null })
        assertFalse(UpdateChecker.enabled(plain) { if (it == "LOOPKY_NO_UPDATE_CHECK") "1" else null })
        assertFalse(UpdateChecker.enabled(Args.parse(arrayOf("deck", "list", "--no-update-check"))) { null })
        assertFalse(
            UpdateChecker.enabled(Args.parse(arrayOf("update", "--check"))) { null },
            "`update` asks the same question itself, uncached — a notice above its own answer is the sentence twice",
        )
    }

    @Test
    fun `the download URL is pinned to the version that was reported, not to latest`() {
        val url = checker(tempHome(), ok("")).assetUrl("0.9.0", "loopky-linux-x86-64")
        assertEquals(
            "https://github.com/jvsena42/loopky/releases/download/v0.9.0/loopky-linux-x86-64",
            url,
        )
    }

    /**
     * The instruction has to match the installation. Telling a Homebrew user to run `loopky
     * update` sends them at a command that refuses; telling a container to self-update describes a
     * change that vanishes with the container.
     */
    @Test
    fun `the advice names the tool that owns this copy`() {
        fun advice(method: InstallMethod) =
            updateAdvice(Installation(method, Path.of("/usr/bin/loopky")), "0.9.0")

        assertTrue(advice(InstallMethod.Binary).contains("loopky update"))
        assertTrue(advice(InstallMethod.Homebrew).contains("brew upgrade"))
        assertTrue(advice(InstallMethod.Debian).contains("dpkg -i"))
        assertTrue(advice(InstallMethod.Container).contains("docker pull ghcr.io/jvsena42/loopky:0.9.0"))
        assertTrue(advice(InstallMethod.Jar).contains("jar distribution"))
    }

    /**
     * The sentence itself, because it is the *only* thing a person sees and the schema half of it
     * is the part that matters: a newer CLI at a different envelope schema means the reader's own
     * parser may be wrong, which no amount of "an update is available" conveys.
     */
    @Test
    fun `the stderr line names both numbers, and says so louder when the schema moved`() {
        val binary = Installation(InstallMethod.Binary, Path.of("/home/agent/.local/bin/loopky"))

        val plain = updateNotice(UpdateAvailable("0.9.0", 1, schemaChanged = false), binary)
        assertTrue(plain.contains("version 0.9.0 is out"))
        assertTrue(plain.contains("loopky update"))
        assertFalse(
            plain.startsWith("loopky"),
            "the caller already prefixes `loopky: `, so repeating it reads as `loopky: loopky …`",
        )
        assertFalse(plain.contains("schema"), "a same-schema update is a convenience, not a warning")

        val moved = updateNotice(UpdateAvailable("0.9.0", 2, schemaChanged = true), binary)
        assertTrue(moved.contains("--json schema is 2"))
        assertTrue(moved.contains("may be wrong"))
    }

    /**
     * **Only a downloaded file we own may replace itself, and that is now two rows** (#301).
     *
     * Written against the whole enum rather than against a list of the refusing ones, because the
     * job is to make a *new* row's self-update capability a deliberate decision: an entry added
     * without a thought about `canSelfUpdate` lands in the refusing set and fails nothing, while
     * one that quietly joins the updating set has to be argued for here first.
     *
     * `WindowsBinary` joined by gaining a mechanism, not by relaxing a rule — it renames the
     * running image aside instead of writing over it.
     */
    @Test
    fun `only a downloaded binary may replace itself, on either of its two rows`() {
        val updatable = setOf(InstallMethod.Binary, InstallMethod.WindowsBinary)
        updatable.forEach { assertTrue(it.canSelfUpdate, "$it is a downloaded file in a directory we own") }
        InstallMethod.entries.filterNot { it in updatable }.forEach {
            assertFalse(it.canSelfUpdate, "$it is owned by something else")
        }
        assertFalse(
            Installation(InstallMethod.Binary, null).canSelfUpdate,
            "a binary whose own path is unknown has nothing to write over",
        )
    }

    @Test
    fun `a schema bump is reported apart from the version bump`() {
        val checker = checker(tempHome(), ok(""), schema = 1)
        assertFalse(checker.available(ReleaseManifest("0.9.0", 1))!!.schemaChanged)
        assertTrue(checker.available(ReleaseManifest("0.9.0", 2))!!.schemaChanged)
    }
}
