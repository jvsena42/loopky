package com.github.jvsena42.loopky.domain.model

import com.github.jvsena42.loopky.util.decodeUriComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentPromptTest {

    @Test
    fun theRequestStandsWhereThePlaceholdersWere() {
        val prompt = AgentPrompt.build("  Ein Deck über Dark, Staffel 1.  ")

        assertTrue(prompt.startsWith("Build me a Loopky flashcard deck with the Loopky CLI.\n\n"))
        assertTrue("\n\nWhat I want:\nEin Deck über Dark, Staffel 1.\n\nUse the loopky skill" in prompt)
        assertFalse("<WHAT I WANT TO LEARN>" in prompt)
    }

    @Test
    fun aBlankRequestKeepsThePlaceholdersForTheAgentToAskAbout() {
        val prompt = AgentPrompt.build("   ")

        assertTrue("Topic: <WHAT I WANT TO LEARN>\nCards: <HOW MANY>" in prompt)
        assertFalse("What I want:" in prompt)
    }

    @Test
    fun thePromptSendsTheAgentToThePluginSkillAndItsFallback() {
        val prompt = AgentPrompt.build("Anything")

        assertTrue("Use the loopky skill from the Loopky plugin for Claude Code." in prompt)
        assertTrue("/plugin marketplace add jvsena42/loopky\n" in prompt)
        assertTrue("/plugin install loopky@loopky\n" in prompt)
        assertTrue("plugins/loopky/skills/loopky/SKILL.md" in prompt)
        assertTrue(prompt.endsWith("releases/latest/download/install.ps1"))
    }

    @Test
    fun aPrefillingAgentOpensWithThePromptThatWouldBeCopied() {
        val request = "日本語 deck & more? 🍥"
        for (app in AgentApp.entries.filter { it.prefillsPrompt }) {
            val url = AgentPrompt.openUrl(app, request)
            val encoded = url.removePrefix(app.url)

            assertEquals(AgentPrompt.build(request), decodeUriComponent(encoded))
            assertFalse(encoded.any { it in " \n&?#" })
        }
    }

    @Test
    fun anAgentThatTakesNoPromptOpensBare() {
        assertEquals("https://chatgpt.com/codex", AgentPrompt.openUrl(AgentApp.Codex, "Anything"))
        assertEquals("https://jules.google.com/", AgentPrompt.openUrl(AgentApp.Jules, "Anything"))
    }

    @Test
    fun everyPluginCommandIsCopiedOnItsOwn() {
        // Two commands in one copy paste into Add Marketplace as one bad owner/repo (site #19).
        val commands = AgentPrompt.pluginInstalls.flatMap { it.commands }

        assertEquals(4, commands.size)
        assertTrue(commands.none { '\n' in it })
    }
}
