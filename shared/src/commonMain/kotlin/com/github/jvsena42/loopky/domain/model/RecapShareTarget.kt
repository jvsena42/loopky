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
        val limit = maxChars ?: return "$body$SEPARATOR$url"
        val forBody = limit - weigh(url, isUrl = true) - weigh(SEPARATOR)
        if (weigh(body) <= forBody) return "$body$SEPARATOR$url"
        val room = forBody - weigh(ELLIPSIS)
        var end = 0
        var used = 0
        while (end < body.length) {
            // A whole code point or none of it: a caption cut between an emoji's two halves is a
            // lone surrogate, and for Pubky that string goes straight into the post record.
            val step = if (body[end].isHighSurrogate() && end + 1 < body.length) 2 else 1
            val cost = weigh(body.substring(end, end + step))
            if (used + cost > room) break
            used += cost
            end += step
        }
        return body.take(end).trimEnd() + ELLIPSIS + SEPARATOR + url
    }

    /**
     * What [text] costs against [maxChars]. A plain length everywhere but X, which counts any
     * link as 23 and every character outside its Latin ranges — CJK, emoji — as two, so a
     * Japanese caption that fits by length is over the limit there.
     */
    private fun weigh(text: String, isUrl: Boolean = false): Int {
        if (this != X) return text.length
        if (isUrl) return X_URL_WEIGHT
        var weight = 0
        var index = 0
        while (index < text.length) {
            val pair = text[index].isHighSurrogate() && index + 1 < text.length
            weight += if (!pair && text[index].code in X_SINGLE_WEIGHT) 1 else 2
            index += if (pair) 2 else 1
        }
        return weight
    }

    private companion object {
        const val SEPARATOR = "\n\n"
        const val ELLIPSIS = "…"
        const val X_URL_WEIGHT = 23

        /** X's own table of what counts once: Latin and general punctuation. */
        val X_SINGLE_WEIGHT = listOf(0x0000..0x10FF, 0x2000..0x200D, 0x2010..0x201F, 0x2032..0x2037)
            .flatten()
            .toSet()
    }
}
