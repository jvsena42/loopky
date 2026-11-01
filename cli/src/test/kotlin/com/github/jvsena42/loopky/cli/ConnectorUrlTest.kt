package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.platform.CONNECTOR_URL
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Windows users are sent to the connector from four places, three of them written by hand. The
 * address is the part that would go stale, so each must carry the one in [CONNECTOR_URL].
 */
class ConnectorUrlTest {

    private val root = File(requireNotNull(System.getProperty("loopky.repoRoot")) { "set by cli/build.gradle.kts" })

    @Test
    fun `every place that sends Windows to the connector names the same address`() {
        assertTrue(CONNECTOR_URL in unsupportedHostMessage("Windows 11", "amd64"))
        listOf("cli/install.sh", "cli/install.ps1", "plugins/loopky/skills/loopky/SKILL.md").forEach { path ->
            val text = File(root, path).readText()
            assertTrue(CONNECTOR_URL in text, "$path does not name $CONNECTOR_URL")
        }
    }
}
