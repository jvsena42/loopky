package com.github.jvsena42.loopky.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopNativeRowTest {

    @Test
    fun `the two shipped rows are recognised`() {
        assertEquals(DesktopNativeRow.LinuxX64, desktopNativeRow("Linux", "amd64"))
        assertEquals(DesktopNativeRow.LinuxX64, desktopNativeRow("Linux", "x86_64"))
        assertEquals(DesktopNativeRow.MacArm64, desktopNativeRow("Mac OS X", "aarch64"))
        // A JVM that reports arm64 rather than aarch64 is the same machine and the same row.
        assertEquals(DesktopNativeRow.MacArm64, desktopNativeRow("Mac OS X", "arm64"))
    }

    @Test
    fun `hosts with no native row are refused`() {
        assertNull(desktopNativeRow("Mac OS X", "x86_64"))
        assertNull(desktopNativeRow("Linux", "aarch64"))
        assertNull(desktopNativeRow("Windows 11", "amd64"))
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

    /** Windows has no build; its users are sent to the hosted connector instead. */
    @Test
    fun `windows is pointed at the connector`() {
        val message = unsupportedDesktopHostMessage("Windows 11", "amd64")
        assertTrue("https://mcp.loopky.app/mcp" in message, message)
    }

    @Test
    fun `macOS is recognised whatever the architecture, because the Keychain does not care`() {
        assertTrue(isMacOs("Mac OS X"))
        assertTrue(isMacOs("macOS"))
        assertTrue(!isMacOs("Linux"))
    }
}
