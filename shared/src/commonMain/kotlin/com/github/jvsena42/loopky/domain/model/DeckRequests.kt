package com.github.jvsena42.loopky.domain.model

/**
 * A reader asking for a deck nobody has made yet, posted to their pubky.app feed.
 *
 * The two labels are what a deck maker — or an automation — finds requests by
 * (`/v0/search/posts/by_tag/deck-request`), so they are part of the post's contract: renaming
 * one strands every request already written under it. Ordinary labels, not [ReservedTags]: a
 * request is the reader's own words, and other apps' users can ask the same way. Architecture.md
 * §7.7 point 5b.
 */
object DeckRequests {
    val TAGS: List<Tag> = listOf(Tag("loopky"), Tag("deck-request"))

    /** Fewer characters than this cannot name a subject. */
    const val MIN_LENGTH = 3

    /** One sentence or two. A longer brief is a deck outline, which is the maker's job. */
    const val MAX_LENGTH = 280

    fun isValid(request: String): Boolean = request.trim().length in MIN_LENGTH..MAX_LENGTH

    /**
     * The post exactly as written: the platform's localized [lead], a blank line, then [request].
     * Whatever follows the first blank line is the request verbatim, which is what a reader of the
     * tag parses out without knowing which language the lead was in.
     */
    fun compose(lead: String, request: String): String = "${lead.trim()}\n\n${request.trim()}"
}
