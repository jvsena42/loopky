package com.github.jvsena42.loopky.data.storage

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where desktop state lives, on each row. Every case injects its host rather than asking the one
 * it runs on, so both rows are covered from either.
 */
class ConfigHomeTest {

    // --- the override, which outranks the platform on every row --------------

    @Test
    fun `an explicit config home wins outright`() {
        val env = mapOf("LOOPKY_CONFIG_HOME" to "/tmp/disposable", "XDG_CONFIG_HOME" to "/xdg")

        assertEquals(Paths.get("/tmp/disposable"), ConfigHome.resolve({ env[it] }))
    }

    /** Blank is not set: `export LOOPKY_CONFIG_HOME=` must not point state at the empty path. */
    @Test
    fun `a blank override is ignored`() {
        val env = mapOf("LOOPKY_CONFIG_HOME" to "  ", "XDG_CONFIG_HOME" to "/xdg")

        assertEquals(Paths.get("/xdg", "loopky"), ConfigHome.resolve({ env[it] }))
    }

    @Test
    fun `xdg comes before the platform default`() {
        val env = mapOf("XDG_CONFIG_HOME" to "/xdg")

        assertEquals(Paths.get("/xdg", "loopky"), ConfigHome.resolve({ env[it] }))
    }

    // --- the platform defaults ----------------------------------------

    @Test
    fun `linux keeps state in dot-config`() {
        assertEquals(
            Paths.get("/home/agent/.config/loopky"),
            ConfigHome.platformDefault(osName = "Linux", userHome = "/home/agent"),
        )
    }

    @Test
    fun `macos keeps state in Application Support`() {
        assertEquals(
            Paths.get("/Users/dev/Library/Application Support/loopky"),
            ConfigHome.platformDefault(osName = "Mac OS X", userHome = "/Users/dev"),
        )
    }
}
