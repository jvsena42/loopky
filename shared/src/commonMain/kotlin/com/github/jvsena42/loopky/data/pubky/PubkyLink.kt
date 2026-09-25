package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.Pubky
import com.github.jvsena42.loopky.util.decodeUriComponent
import com.github.jvsena42.loopky.util.encodeUriComponent

/**
 * Something a `pubky://` or `https://loopky.app` address can open to inside Loopky.
 *
 * Sharing a deck or a profile writes one of these URIs into a message; tapping it has to land on
 * the screen it names. [PubkyLinks] is the one place that decides which screen that is, so the
 * deep-link intake, the add-friend sheet and the QR scanner all agree on what a pasted string is.
 */
sealed interface PubkyLink {
    /** The account the address belongs to — the first segment of every `pubky://` URI. */
    val pubky: String

    /** Someone's profile: `pubky://{pubky}`, or their `pub/pubky.app/profile.json` record. */
    data class Profile(override val pubky: String) : PubkyLink

    /** One published deck: `pubky://{pubky}/pub/loopky/decks/{deckId}/manifest.json`. */
    data class Deck(override val pubky: String, val deckId: String) : PubkyLink
}

/**
 * Recognises the addresses Loopky hands out, in the shapes they come back in.
 *
 * Deliberately forgiving, because everything here arrives from a human: a link tapped in a chat, a
 * QR code, a paste of the whole share message ("Spanish Verbs on Loopky" + the URI on the next
 * line), a bare pubky copied off a profile chip. The strict parser next door ([PubkyUris]) answers
 * a different question — whether a URI *is* exactly a deck manifest, for verifying tags that any
 * account can point anywhere — and must stay strict. This one only has to get the user to the
 * right screen.
 */
object PubkyLinks {

    private const val SCHEME = "pubky://"

    /** The `pk:` prefix pubky.app uses when a pubky travels as text rather than as a URI. */
    private const val PK_PREFIX = "pk:"

    private const val DECKS_PREFIX = "pub/loopky/decks/"

    /**
     * Where a shared link points. An `https` address is clickable in every chat client and in a
     * pubky.app post, where `pubky://` is plain text, and a phone with Loopky installed opens it as
     * a verified App Link; one without lands on a web page that shows the deck and the store link.
     * Query parameters rather than path segments because the site is static GitHub Pages, where a
     * path route would only exist as a `404.html` fallback served with status 404.
     */
    private const val WEB_ORIGIN = "https://loopky.app"
    private val WEB_HOSTS = listOf("loopky.app", "www.loopky.app")
    private const val WEB_DECK_PATH = "deck"
    private const val WEB_PROFILE_PATH = "profile"
    private const val PARAM_AUTHOR = "author"
    private const val PARAM_DECK = "id"
    private const val PARAM_PUBKY = "pubky"

    /** Punctuation a link keeps when it ends a sentence, which would otherwise join the deck id. */
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}"

    /**
     * The link [text] carries, or `null` if it carries none.
     *
     * Accepts the address on its own or embedded in surrounding prose, so pasting the whole
     * shared message works as well as pasting the link out of it.
     */
    fun parse(text: String): PubkyLink? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return parseExact(trimmed) ?: findIn(trimmed)
    }

    /** [text] as a single address, with nothing around it. */
    private fun parseExact(text: String): PubkyLink? {
        parseWeb(text)?.let { return it }
        val bare = text.removePrefix(PK_PREFIX).trim()
        if (!bare.startsWith(SCHEME)) {
            return if (isPubky(bare)) PubkyLink.Profile(bare) else null
        }
        val rest = bare.removePrefix(SCHEME)
        val owner = rest.substringBefore('/', missingDelimiterValue = rest)
        if (owner.isEmpty() || owner.any { it.isWhitespace() }) return null
        return fromPath(owner, path = rest.removePrefix(owner).trim('/'))
    }

    /**
     * Which screen a path under one account points at.
     *
     * An unrecognised path falls back to that account's profile rather than to nothing: the URI
     * still names a person, and landing on them beats a tap that does nothing.
     */
    private fun fromPath(owner: String, path: String): PubkyLink {
        if (!path.startsWith(DECKS_PREFIX)) return PubkyLink.Profile(owner)
        // Anything below the deck root — the manifest, a card chunk, a media blob — is that deck.
        val deckId = path.removePrefix(DECKS_PREFIX).substringBefore('/')
        return if (deckId.isEmpty()) PubkyLink.Profile(owner) else PubkyLink.Deck(owner, deckId)
    }

    /**
     * A `loopky.app` share link. Stricter than the `pubky://` path: the account must be a real key,
     * because a web address is one anybody can hand-edit into a link that opens Loopky.
     */
    private fun parseWeb(text: String): PubkyLink? {
        val afterHost = WEB_HOSTS.firstNotNullOfOrNull { host ->
            listOf("https://", "http://").firstNotNullOfOrNull { scheme ->
                val prefix = scheme + host
                text.takeIf { it.startsWith(prefix, ignoreCase = true) }?.drop(prefix.length)
            }
        } ?: return null
        if (afterHost.isNotEmpty() && afterHost.first() !in "/?#") return null
        val path = afterHost.substringBefore('#').substringBefore('?').trim('/')
        val params = afterHost.substringBefore('#').substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .mapNotNull { pair ->
                val value = decodeUriComponent(pair.substringAfter('=', missingDelimiterValue = ""))
                value?.let { pair.substringBefore('=') to it }
            }
            .toMap()
        return when (path) {
            WEB_DECK_PATH -> {
                val author = params[PARAM_AUTHOR]?.takeIf(::isPubky) ?: return null
                val deckId = params[PARAM_DECK]
                    ?.takeIf { id -> id.isNotEmpty() && id.none { it == '/' || it.isWhitespace() } }
                    ?: return null
                PubkyLink.Deck(author, deckId)
            }

            WEB_PROFILE_PATH -> params[PARAM_PUBKY]?.takeIf(::isPubky)?.let(PubkyLink::Profile)
            else -> null
        }
    }

    /**
     * The first address embedded in [text].
     *
     * Splitting on whitespace is not enough on its own: a link at the end of a sentence keeps its
     * punctuation, and a trailing `.` or `)` would otherwise become part of a deck id.
     */
    private fun findIn(text: String): PubkyLink? = text
        .split(' ', '\t', '\n', '\r', '<', '>', '"', '\'')
        .asSequence()
        .map { token -> token.trim { it in TRAILING_PUNCTUATION } }
        .filter { it.startsWith(SCHEME) || it.startsWith(PK_PREFIX) || isPubky(it) || isWebLink(it) }
        .firstNotNullOfOrNull(::parseExact)

    private fun isWebLink(token: String): Boolean =
        WEB_HOSTS.any { host -> token.contains("://$host", ignoreCase = true) }

    /** True when [candidate] is shaped like a bare pubky. */
    fun isPubky(candidate: String): Boolean = Pubky.isKey(candidate)

    /** True when [candidate] could be the *beginning* of a pubky. See [Pubky.isKeyPrefix]. */
    fun isPubkyPrefix(candidate: String, minLength: Int): Boolean =
        Pubky.isKeyPrefix(candidate, minLength)

    /** The link Loopky shares for someone's profile. The deck equivalent is `Deck.webUrl`. */
    fun profileWebUrl(pubky: String): String =
        "$WEB_ORIGIN/$WEB_PROFILE_PATH/?$PARAM_PUBKY=${encodeUriComponent(pubky)}"
}
