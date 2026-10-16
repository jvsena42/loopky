package com.github.jvsena42.loopky.mcp.tools

/**
 * The server's `instructions`: what the agent skill says about writing cards, read from the copy
 * of `plugins/loopky/skills/loopky/SKILL.md` the build puts on the classpath (#486).
 *
 * Generated at build time rather than typed here so the two cannot drift: the skill is what
 * Claude Code reads about Loopky, and a connector that taught different rules would make the same
 * user's decks differ by which door they came in through. Only the sections about *cards* are
 * taken — the workflow sections are about a binary this server is not.
 */
object McpInstructions {

    fun load(): String {
        val skill = McpInstructions::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader()?.use { it.readText() }
            ?: error("$RESOURCE is missing from the classpath; the build copies it from plugins/")
        val sections = SECTIONS.map { heading ->
            sectionOf(skill, heading) ?: error("SKILL.md has no `## $heading` section; McpInstructions names it")
        }
        return (listOf(PREFACE) + sections).joinToString("\n\n")
    }

    /** One `## heading` section, up to the next `## `. */
    private fun sectionOf(markdown: String, heading: String): String? {
        val lines = markdown.lines()
        val start = lines.indexOfFirst { it.trim() == "## $heading" }
        if (start < 0) return null
        val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }.let { if (it < 0) lines.size else start + 1 + it }
        return lines.subList(start, end).joinToString("\n").trim()
    }

    private const val RESOURCE = "/com/github/jvsena42/loopky/mcp/SKILL.md"

    private val SECTIONS = listOf("Writing good cards", "Where to find pictures", "Limits worth knowing")

    private val PREFACE = """
        # Loopky

        Loopky is a free spaced-repetition flashcards app (Android, iOS). These tools write decks to
        the connected user's own account; they study them on their phone. The tools manage decks and
        cards; they do not study.

        Workflow: `deck_list` to see what exists; `deck_create` or `import` to publish, with
        `dry_run: true` first and the `card_advice`, `image_advice` and `language_advice` in the
        result fixed before the real call; then verify from what was stored with `deck_show` and
        `card_list` — a card's `front` is an object with `text` and `image`. Results are the CLI's
        `--json` envelope: read `ok`, then `error.code`, then `data`. `error.reconnect: true` means the
        user has to reconnect this connector; nothing else helps.

        Several cards go in `cards` (an array of objects) or `text` (TSV or JSONL) — at most
        ${InlineLimits.MAX_CARDS} cards and 1 MiB per call; split a bigger deck across `card_add`
        calls, which append. Pictures are https URLs the phone fetches; the server never fetches
        them, so there is no `check_images` here. An Anki .apkg cannot be imported through this
        connector. One write at a time per account: wait for a call to return before the next.

        The rules below are the agent skill's, written for the `loopky` command line: a flag spelled
        `--front-lang` there is the `front_lang` input of a tool here, `--check-images` and
        `loopky doctor` do not exist here, and a "deck of thousands" is several calls.
    """.trimIndent()
}
