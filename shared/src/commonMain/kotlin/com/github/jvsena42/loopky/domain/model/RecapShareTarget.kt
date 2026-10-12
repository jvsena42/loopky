package com.github.jvsena42.loopky.domain.model

/** PascalCase entries: both enums cross to Swift. */
enum class RecapImageFormat(val width: Int, val height: Int) {
    Story(1080, 1920),
    Square(1080, 1080),
    Landscape(1200, 675),
}

/**
 * Where a recap can be sent, and what each place can actually take.
 *
 * [takesText] is false for Instagram, which accepts an image and discards any caption handed to
 * it. [maxChars] is the whole post, link included; null where nothing practical limits it.
 */
enum class RecapShareTarget(
    val imageFormat: RecapImageFormat,
    val takesText: Boolean = true,
    val maxChars: Int? = null,
) {
    Pubky(RecapImageFormat.Landscape, maxChars = 2_000),
    X(RecapImageFormat.Landscape, maxChars = 280),
    Threads(RecapImageFormat.Square, maxChars = 500),
    Instagram(RecapImageFormat.Story, takesText = false),
    WhatsApp(RecapImageFormat.Square),
    More(RecapImageFormat.Square),
    ;

    /**
     * [text] followed by [url], with the text shortened if the two would overrun [maxChars]. The
     * link is never the part that is cut: a recap post without it leads nowhere.
     */
    fun compose(text: String, url: String): String {
        val body = text.trim()
        val room = maxChars?.let { it - url.length - SEPARATOR.length } ?: return "$body$SEPARATOR$url"
        val fitted = if (body.length <= room) body else body.take((room - 1).coerceAtLeast(0)).trimEnd() + ELLIPSIS
        return "$fitted$SEPARATOR$url"
    }

    private companion object {
        const val SEPARATOR = "\n\n"
        const val ELLIPSIS = "…"
    }
}
