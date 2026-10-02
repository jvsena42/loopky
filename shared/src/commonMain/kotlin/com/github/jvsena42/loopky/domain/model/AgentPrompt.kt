package com.github.jvsena42.loopky.domain.model

import com.github.jvsena42.loopky.util.encodeUriComponent

/**
 * The prompt the import screen hands a coding agent so it builds a deck with the `loopky` CLI.
 *
 * The same text as the landing page's "Let your AI build the deck" box, so an agent reached from
 * either place is told the same thing: use the Loopky plugin's skill, and read the skill itself
 * when the plugin is not installed. The reader's [build] request is in their own language; the
 * steps stay in English, because the agent reads both.
 */
object AgentPrompt {

    fun build(request: String): String {
        val ask = request.trim()
        val what = if (ask.isEmpty()) PLACEHOLDER_REQUEST else "What I want:\n$ask"
        return "Build me a Loopky flashcard deck with the Loopky CLI.\n\n$what\n\n$SETUP"
    }

    /** Where [app] opens, with the prompt already typed in when it takes one ([AgentApp.prefillsPrompt]). */
    fun openUrl(app: AgentApp, request: String): String =
        if (app.prefillsPrompt) app.url + encodeUriComponent(build(request)) else app.url

    /**
     * Each command is its own entry, never one string with a newline: Claude Code's Add
     * Marketplace box takes only the source, so a pair copied together is refused as one bad
     * `owner/repo` (loopky.github.io#19).
     */
    val pluginInstalls: List<PluginInstall> = listOf(
        PluginInstall(
            app = "Claude Code",
            commands = listOf("/plugin marketplace add jvsena42/loopky", "/plugin install loopky@loopky"),
        ),
        PluginInstall(
            app = "Codex",
            commands = listOf("codex plugin marketplace add jvsena42/loopky", "codex plugin add loopky@loopky"),
        ),
    )

    private const val PLACEHOLDER_REQUEST = "Topic: <WHAT I WANT TO LEARN>\nCards: <HOW MANY>"

    private val SETUP = """
        Use the loopky skill from the Loopky plugin for Claude Code. It installs with:
           /plugin marketplace add jvsena42/loopky
           /plugin install loopky@loopky
        Not installed? Read the skill and follow it:
           https://github.com/jvsena42/loopky/blob/main/plugins/loopky/skills/loopky/SKILL.md
           Its CLI installer is published with each release:
           https://github.com/jvsena42/loopky/releases/latest/download/install.sh
           Windows: https://github.com/jvsena42/loopky/releases/latest/download/install.ps1
    """.trimIndent()
}

/** One agent's plugin install, as the separate commands it has to be typed in as. */
data class PluginInstall(val app: String, val commands: List<String>)

/**
 * The coding agents the import screen opens: each runs in a sandbox that can execute the CLI,
 * which a plain chat cannot. Codex and Jules take no prompt in the URL, so the screen copies it
 * to paste.
 */
enum class AgentApp(val title: String, internal val url: String, val prefillsPrompt: Boolean) {
    ClaudeCode("Claude Code", "https://claude.ai/code?prompt=", prefillsPrompt = true),
    Codex("Codex", "https://chatgpt.com/codex", prefillsPrompt = false),
    Jules("Jules", "https://jules.google.com/", prefillsPrompt = false),
    Cursor("Cursor", "https://cursor.com/link/prompt?text=", prefillsPrompt = true),
}
