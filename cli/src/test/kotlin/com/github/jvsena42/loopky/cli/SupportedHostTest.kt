package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.platform.DesktopNativeRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportedHostTest {

    @Test
    fun `the three shipped rows are recognised`() {
        assertEquals(SupportedHost.LinuxX64, hostSupport("Linux", "amd64"))
        assertEquals(SupportedHost.LinuxX64, hostSupport("Linux", "x86_64"))
        assertEquals(SupportedHost.MacArm64, hostSupport("Mac OS X", "aarch64"))
        // A JVM that reports arm64 rather than aarch64 is the same machine and the same row.
        assertEquals(SupportedHost.MacArm64, hostSupport("Mac OS X", "arm64"))
        assertEquals(SupportedHost.WinX64, hostSupport("Windows 11", "amd64"))
        assertEquals(SupportedHost.WinX64, hostSupport("Windows Server 2022", "x86_64"))
    }

    /** The asset name is what `loopky update` downloads by, so the suffix is part of the contract. */
    @Test
    fun `the windows asset keeps its exe suffix`() {
        assertEquals("loopky-windows-x86-64.exe", SupportedHost.WinX64.asset)
    }

    /**
     * `hostSupport` resolves the row with `entries.first { … }`, which throws rather than returning
     * null when a [DesktopNativeRow] has no entry here. That is the right choice — `firstOrNull`
     * would hand back "host not supported" for a row that *is* supported — but it makes the two
     * enums a landmine, because they sit either side of a module boundary: `DesktopNativeRow` is
     * public in `:shared`, this is internal to `:cli`, and `install.sh` already anticipates Linux
     * arm64 as the next row.
     *
     * Adding a row there and forgetting one here throws `NoSuchElementException` out of
     * `requireSupportedHost` on exactly the host being added — an unchecked throw instead of the
     * clean exit 10 the whole refusal path exists to produce. The per-host tests above all stay
     * green, because they enumerate hosts rather than assert coverage.
     */
    @Test
    fun `every native row has a supported host`() {
        DesktopNativeRow.entries.forEach { row ->
            assertNotNull(
                SupportedHost.entries.firstOrNull { it.row == row },
                "no SupportedHost for $row — hostSupport() would throw rather than exit 10",
            )
        }
    }

    @Test
    fun `hosts with no native row are refused`() {
        // Each absence is a decision rather than a gap — see shared/src/jvmMain/resources/README.md.
        assertNull(hostSupport("Mac OS X", "x86_64"))
        assertNull(hostSupport("Linux", "aarch64"))
        // ARM64 Windows: the x64 binary runs there under emulation, but a JVM reporting aarch64
        // cannot load an x64 DLL into its own process.
        assertNull(hostSupport("Windows 11", "aarch64"))
    }

    @Test
    fun `an Intel Mac is told about Rosetta rather than about the network`() {
        val message = unsupportedHostMessage("Mac OS X", "x86_64")
        assertTrue("Apple Silicon" in message, message)
        assertTrue("Rosetta" in message, message)
    }

    /**
     * The refusal that survived the row landing. x64 Windows is shipped, so the only Windows host
     * still refused is ARM64 — and telling it "Windows is not a target" would now be false.
     */
    @Test
    fun `arm64 windows is told which builds exist, not that windows is unsupported`() {
        val message = unsupportedHostMessage("Windows 11", "aarch64")
        assertTrue("Windows x86_64" in message, message)
        assertTrue("not a target" !in message, message)
    }

    /**
     * The whole reason [ExitCode.UnsupportedHost] exists, pinned as a fact rather than a claim.
     *
     * This is verbatim the shape JNA throws when no row on the classpath matches the host. Left to
     * the shared classifier it is not merely unclassified — `isNotFound()` matches the words "not
     * found" in it, so a machine that can never run this binary reports [ExitCode.NotFound], and
     * an agent reads that as "the deck you asked for does not exist" and moves on to the next one.
     */
    @Test
    fun `an unloadable library classifies as not_found, which is why the pre-check exists`() {
        val jnaFailure = UnsatisfiedLinkError(
            "Unable to load library 'pubkycore': Native library (darwin-x86-64/libpubkycore.dylib) " +
                "not found in resource path",
        )
        assertEquals(ExitCode.NotFound, ExitCode.of(jnaFailure))
    }
}
