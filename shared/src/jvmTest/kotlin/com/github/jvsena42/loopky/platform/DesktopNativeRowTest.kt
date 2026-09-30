package com.github.jvsena42.loopky.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopNativeRowTest {

    @Test
    fun `the three shipped rows are recognised`() {
        assertEquals(DesktopNativeRow.LinuxX64, desktopNativeRow("Linux", "amd64"))
        assertEquals(DesktopNativeRow.LinuxX64, desktopNativeRow("Linux", "x86_64"))
        assertEquals(DesktopNativeRow.MacArm64, desktopNativeRow("Mac OS X", "aarch64"))
        // A JVM that reports arm64 rather than aarch64 is the same machine and the same row.
        assertEquals(DesktopNativeRow.MacArm64, desktopNativeRow("Mac OS X", "arm64"))
        assertEquals(DesktopNativeRow.WinX64, desktopNativeRow("Windows 11", "amd64"))
        assertEquals(DesktopNativeRow.WinX64, desktopNativeRow("Windows Server 2022", "x86_64"))
    }

    @Test
    fun `hosts with no native row are refused`() {
        assertNull(desktopNativeRow("Mac OS X", "x86_64"))
        assertNull(desktopNativeRow("Linux", "aarch64"))
        // ARM64 Windows: a real machine, and not this row. The x64 build runs there under
        // emulation, but a JVM reporting aarch64 cannot load an x64 DLL into its own process.
        assertNull(desktopNativeRow("Windows 11", "aarch64"))
    }

    /**
     * The failure this replaces is a *wrong* diagnosis, not a missing one: JNA's miss reads as a
     * 404 to the shared classifier, so an Intel Mac used to be told the record does not exist.
     */
    @Test
    fun `an Intel Mac is told about Apple Silicon and Rosetta, not about the network`() {
        val message = unsupportedDesktopHostMessage("Mac OS X", "x86_64")
        assertTrue("Apple Silicon" in message, message)
        assertTrue("Rosetta" in message, message)
    }

    /**
     * The refusal that survived the row landing. An x64 Windows machine is now supported, so the
     * only Windows host still refused is ARM64 — and it must be told which builds exist rather than
     * that its OS is unsupported, which would be false.
     */
    @Test
    fun `arm64 windows is told which builds exist, not that windows is unsupported`() {
        val message = unsupportedDesktopHostMessage("Windows 11", "aarch64")
        assertTrue("Windows x86_64" in message, message)
        assertTrue("not a target" !in message, message)
    }

    @Test
    fun `macOS is recognised whatever the architecture, because the Keychain does not care`() {
        assertTrue(isMacOs("Mac OS X"))
        assertTrue(isMacOs("macOS"))
        assertTrue(!isMacOs("Linux"))
    }
}
