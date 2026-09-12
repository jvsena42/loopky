package com.github.jvsena42.loopky.data.storage

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where desktop state lives, on each of the three rows.
 *
 * Untested until #301, which is most of why the Windows row was wrong: [ConfigHome.platformDefault]
 * was a two-way branch, so Windows fell into `~/.config` — under the profile root, and therefore
 * **roaming**. A session secret copied to a domain profile server at logoff is not something a
 * green build was ever going to mention.
 *
 * Every case injects its host rather than asking the one it runs on, so all three rows are covered
 * from any of them. That is the same reason [ConfigHome.resolve] already took an `env`.
 */
class ConfigHomeTest {

    private val noEnv: (String) -> String? = { null }

    // --- the override, which outranks the platform on every row --------------

    @Test
    fun `an explicit config home wins outright`() {
        val env = mapOf("LOOPKY_CONFIG_HOME" to "/tmp/disposable", "XDG_CONFIG_HOME" to "/xdg")

        assertEquals(Paths.get("/tmp/disposable"), ConfigHome.resolve { env[it] })
    }

    /** Blank is not set: `export LOOPKY_CONFIG_HOME=` must not point state at the empty path. */
    @Test
    fun `a blank override is ignored`() {
        val env = mapOf("LOOPKY_CONFIG_HOME" to "  ", "XDG_CONFIG_HOME" to "/xdg")

        assertEquals(Paths.get("/xdg", "loopky"), ConfigHome.resolve { env[it] })
    }

    @Test
    fun `xdg comes before the platform default`() {
        val env = mapOf("XDG_CONFIG_HOME" to "/xdg")

        assertEquals(Paths.get("/xdg", "loopky"), ConfigHome.resolve { env[it] })
    }

    // --- the three platform defaults ----------------------------------------

    @Test
    fun `linux keeps state in dot-config`() {
        assertEquals(
            Paths.get("/home/agent/.config/loopky"),
            ConfigHome.platformDefault(noEnv, osName = "Linux", userHome = "/home/agent"),
        )
    }

    @Test
    fun `macos keeps state in Application Support`() {
        assertEquals(
            Paths.get("/Users/dev/Library/Application Support/loopky"),
            ConfigHome.platformDefault(noEnv, osName = "Mac OS X", userHome = "/Users/dev"),
        )
    }

    /**
     * The row this file exists for. `Local`, never `Roaming` — see [ConfigHome].
     */
    @Test
    fun `windows keeps state in LOCALAPPDATA`() {
        val env = mapOf("LOCALAPPDATA" to """C:\Users\dev\AppData\Local""")

        val home = ConfigHome.platformDefault(
            { env[it] },
            osName = "Windows 11",
            userHome = """C:\Users\dev""",
        )

        assertEquals(Paths.get("""C:\Users\dev\AppData\Local""", "loopky"), home)
    }

    /**
     * A redirected-folder policy moves `%LOCALAPPDATA%`, so the variable outranks the convention —
     * guessing would write outside the directory the machine reserves for this.
     */
    @Test
    fun `a redirected LOCALAPPDATA is honoured`() {
        val env = mapOf("LOCALAPPDATA" to """D:\Redirected\Local""")

        val home = ConfigHome.platformDefault({ env[it] }, osName = "Windows 11", userHome = """C:\Users\dev""")

        assertEquals(Paths.get("""D:\Redirected\Local""", "loopky"), home)
    }

    /** A process handed a stripped environment still has to land somewhere sensible. */
    @Test
    fun `windows falls back to the conventional path when the variable is missing`() {
        val home = ConfigHome.platformDefault(noEnv, osName = "Windows 11", userHome = """C:\Users\dev""")

        assertEquals(Paths.get("""C:\Users\dev""", "AppData", "Local", "loopky"), home)
    }

    /**
     * Windows must not be mistaken for the POSIX row, which is the defect this file pins: the
     * branch used to be macOS-or-everything-else, and "everything else" meant `~/.config` — under
     * the roaming part of the profile.
     */
    @Test
    fun `windows is not the dot-config row`() {
        val windows = ConfigHome.platformDefault(noEnv, osName = "Windows 11", userHome = """C:\Users\dev""")

        assertEquals(false, windows.toString().contains(".config"), "landed in the roaming profile: $windows")
    }
}
