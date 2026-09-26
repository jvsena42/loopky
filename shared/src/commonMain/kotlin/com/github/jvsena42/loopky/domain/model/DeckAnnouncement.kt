package com.github.jvsena42.loopky.domain.model

/**
 * A post Loopky offers to write to the user's pubky.app feed about a deck.
 *
 * **Announcing, not sharing.** Publishing a deck already writes it publicly to the homeserver —
 * there are no private decks in v1 (spec §11) — so this changes nothing about who *can* see the
 * deck. It only tells the people following the user that it exists. Copy that blurs the two turns
 * the Settings switch into a privacy control it is not.
 *
 * [content] is composed here rather than in the repository so the confirm prompt can preview the
 * exact text that will be written: the preview and the post are the same string by construction.
 */
data class DeckAnnouncement(
    val kind: Kind,
    val deckTitle: String,
    val deckUri: PubkyUri,
    val deckUrl: String,
    /**
     * The original author's pubky, for [Kind.Followed] and [Kind.Cloned] — a clone credits
     * whoever it forked. Written into [content] as a **mention**, so the credit reaches the person
     * being credited rather than only the announcer's own followers.
     */
    val authorPubky: String? = null,
    /** The deck's cover emoji, which opens the post in place of the generic fallback. */
    val coverEmoji: String? = null,
    /**
     * The deck's cover as an `https://` image URL, or null when it has none or its cover is not
     * reachable over the web — see [previewableCoverUrl].
     */
    val coverImageUrl: String? = null,
    /**
     * The deck's topics, written as real tag records on the post by
     * [com.github.jvsena42.loopky.data.repository.DiscoveryRepository.announceDeck] — the records
     * are what Nexus indexes into `/v0/tags/hot` and `/v0/search/posts/by_tag/{label}`.
     *
     * Deliberately **not** repeated as `#hashtags` in [content]: the tag chips a reader sees under
     * the post come from the records, so hashtags in the body only said the same thing twice.
     */
    val tags: List<Tag> = emptyList(),
) {
    enum class Kind { Created, Followed, Cloned }

    /**
     * The post body: a headline, the cover's image URL, and the deck's `https://loopky.app` link.
     *
     * **The deck goes in as [deckUrl], not as [deckUri].** pubky.app renders content as markdown
     * and linkifies only `http(s)`, so a `pubky://` address there is inert text; the web link is
     * clickable, opens Loopky as an App Link where it is installed, and a preview page where it is
     * not. [deckUri] still travels in the post's `embed`, for clients that read the record.
     *
     * **The cover comes before the link, because only the first link is previewed.** pubky.app
     * resolves `attachments` strictly as its own file records, but runs the first `http(s)` link in
     * the content through an OpenGraph probe and renders an image content-type inline. The deck's
     * own cover is a better preview than the site's generic card, which is what a static page can
     * offer; with no cover, that card is what shows.
     *
     * **The author is credited as a mention, which is why the key is written out in full.** Nexus
     * scans post content for [MENTION_PREFIX] followed by exactly 52 characters of z-base-32,
     * writes a MENTIONED edge and notifies that account, and pubky.app renders the pair as a link
     * to their profile. A display name could not do that: it is self-declared and changeable. The
     * deck link carries the key as `author=`, which is not the prefix, so it is not a second one.
     *
     * The title is truncated because it is not always the user's own: announcing a follow or a
     * clone quotes another account's manifest, and pubky-app-specs rejects a post over
     * `post_short_content_max_length` (2,000 characters). A post that fails validation is written
     * and then never indexed, which is the one failure mode with no visible symptom.
     */
    val content: String
        get() {
            val by = authorPubky?.trim()
                ?.takeIf { Pubky.isKey(it) }
                ?.let { " by $MENTION_PREFIX$it" }
                .orEmpty()
            val title = "\"" + deckTitle.trim().ellipsized(MAX_TITLE_LENGTH) + "\""
            // A letter is the title-initial fallback the deck screens draw — and that the
            // editor used to save as `cover_emoji` — never an emoji the author picked, so it
            // must not open the post.
            val icon = coverEmoji?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isLetter) }
                ?: DEFAULT_ICON
            val headline = when (kind) {
                Kind.Created -> "$icon I published a new deck on Loopky: $title"
                Kind.Followed -> "$icon Now following the Loopky deck: $title$by"
                Kind.Cloned -> "$icon Cloned the Loopky deck: $title$by into my library"
            }
            val cover = coverImageUrl?.let { "\n\n$it" }.orEmpty()
            return "$headline$cover\n\n$deckUrl"
        }

    companion object {
        /** Everything an announcement says about a deck comes off the deck itself. */
        fun of(deck: Deck, kind: Kind, authorPubky: String? = null): DeckAnnouncement =
            DeckAnnouncement(
                kind = kind,
                deckTitle = deck.title,
                deckUri = deck.pubkyUri,
                deckUrl = deck.webUrl,
                authorPubky = authorPubky,
                coverEmoji = deck.coverEmoji,
                coverImageUrl = deck.previewableCoverUrl(),
                tags = deck.announceableTags(),
            )

        private const val DEFAULT_ICON = "📚"
        private const val MAX_TITLE_LENGTH = 120

        /**
         * What turns a key in a post body into a mention. `pk:` does the same and is deprecated
         * upstream, so new posts use this one. Anything that is not an exact key is left out
         * entirely rather than written as plain text: the prefix only reads as a mention when a
         * whole key follows it, and `pubky` in front of a near-miss mentions nobody while looking
         * like it should.
         */
        private const val MENTION_PREFIX = "pubky"
    }
}

/**
 * The deck's topics, plus [ReservedTags.DECK] so the announcement is findable as a Loopky deck
 * network-wide — the label's whole purpose, and the reason it is not filtered out here the way a
 * hand-entered reserved label would be.
 *
 * Capped because every one of these costs a homeserver write on top of the post, and a wall of
 * tag chips under it reads as spam in someone else's feed.
 */
private fun Deck.announceableTags(): List<Tag> =
    (tags.filterNot { ReservedTags.isReserved(it) }.take(MAX_ANNOUNCEMENT_TOPICS) + ReservedTags.DECK)
        .distinct()

/** Topics per announcement, before [ReservedTags.DECK] is appended. */
private const val MAX_ANNOUNCEMENT_TOPICS = 5

/**
 * The deck's cover as a URL a reader's client can actually fetch, or null when there is none.
 *
 * **Web covers only, and that is a real limit rather than an oversight.** A deck cover comes in
 * two shapes: an Unsplash-style remote image, which already has an `https://` URL, and a blob the
 * user uploaded to their own homeserver, which has only a `pubky://` one. Nothing outside a Pubky
 * client can fetch the latter — no public gateway maps a `pubky://` record to an HTTP URL — so a
 * gallery cover is dropped here and its announcement simply goes out without an image.
 *
 * Fixing that means giving the cover a pubky.app **blob + file record** and attaching *that*,
 * which is the only form pubky.app resolves. It is not done here because a blob's id is
 * Crockford-base32 of blake3 over its bytes, strictly validated on ingest, and neither platform
 * nor the FFI (`create_tag_id` only) can compute one.
 */
private fun Deck.previewableCoverUrl(): String? {
    val ref = coverImageRef ?: return null
    val url = ref.url ?: return null
    val allowed = PREVIEW_PROTOCOLS.any { url.startsWith("$it://") }
    return url.takeIf { allowed && it.length <= MAX_ATTACHMENT_URL_LENGTH }
}

/** Only what a browser can fetch; see [previewableCoverUrl]. */
private val PREVIEW_PROTOCOLS = listOf("https", "http")

/**
 * Kept at pubky-app-specs' `post_attachment_url_max_length` even though the URL now travels in
 * the body: a cover URL long enough to trip that limit is long enough to swamp the post.
 */
private const val MAX_ATTACHMENT_URL_LENGTH = 200

private const val ELLIPSIS = "…"

private fun String.ellipsized(max: Int): String =
    if (length <= max) this else take(max - ELLIPSIS.length).trimEnd() + ELLIPSIS
