package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.Pubky

/**
 * Something a `pubky://` address can open to inside Loopky.
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
     * The first address embedded in [text].
     *
     * Splitting on whitespace is not enough on its own: a link at the end of a sentence keeps its
     * punctuation, and a trailing `.` or `)` would otherwise become part of a deck id.
     */
    private fun findIn(text: String): PubkyLink? = text
        .split(' ', '\t', '\n', '\r', '<', '>', '"', '\'')
        .asSequence()
        .map { token -> token.trim { it in TRAILING_PUNCTUATION } }
        .filter { it.startsWith(SCHEME) || it.startsWith(PK_PREFIX) || isPubky(it) }
        .firstNotNullOfOrNull(::parseExact)

    /** True when [candidate] is shaped like a bare pubky. */
    fun isPubky(candidate: String): Boolean = Pubky.isKey(candidate)

    /** True when [candidate] could be the *beginning* of a pubky. See [Pubky.isKeyPrefix]. */
    fun isPubkyPrefix(candidate: String, minLength: Int): Boolean =
        Pubky.isKeyPrefix(candidate, minLength)

    /** The canonical shareable address of someone's profile. */
    fun profileUri(pubky: String): String = "$SCHEME$pubky"
}
