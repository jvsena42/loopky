package com.github.jvsena42.loopky.domain.model

import com.github.jvsena42.loopky.util.encodeUriComponent
import kotlin.jvm.JvmInline

data class Deck(
    val id: String,
    val authorPubky: String,
    val title: String,
    val description: String?,
    val coverEmoji: String? = null,
    val coverImageRef: MediaRef.Image?,
    val tags: List<Tag>,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * Denormalized so a deck tile can render "20,000 cards" from the manifest alone. Previously
     * derived from the card index, which meant downloading every card entry to draw one tile.
     */
    val cardCount: Int,
    /** Card chunks that make up this deck; membership is their union. See [ChunkMeta]. */
    val chunks: List<ChunkMeta> = emptyList(),
    /** Where this deck came from (clone, import, original). Null for decks published before §6. */
    val source: DeckSource? = null,
    /**
     * True between the two manifest writes of a publish: the deck is claimed but its chunks may
     * not all be up yet. Lets an interrupted publish stay visible and deletable instead of
     * orphaning chunk records under a deck root no listing can see.
     */
    val incomplete: Boolean = false,
    /** Opt-in: play TTS audio of the card back during study. */
    val listenEnabled: Boolean = true,
    /** Opt-in: pronunciation practice (speech recognition) on the card back during study. */
    val speakEnabled: Boolean = true,
    /**
     * Opt-in: type the answer during study instead of reading it off the flipped card.
     *
     * Defaults **off**, unlike the two above, and a manifest written before the field decodes
     * that way too: silently turning a deck's cards into an exercise its author never chose is
     * a bigger surprise than a missing button. Deliberately not folded through [speechReady] —
     * see that property.
     */
    val typeEnabled: Boolean = false,
    /**
     * Opt-in: study every card in both directions, back-to-front as well as front-to-back.
     *
     * Not a second set of cards, and it must never become one — a reverse is a way of *studying* a
     * card, not another card. Both directions therefore share the card's one review state, which
     * is why the pair is graded once, from whichever direction went worse (see
     * `StudySessionViewModel`).
     *
     * Defaults **off**, like [typeEnabled] and for the same reason: a manifest written before the
     * field says nothing about its author's intent, and silently doubling how long a deck takes to
     * get through is a bigger surprise than a missing toggle. Deliberately outside [speechReady] —
     * swapping two sides needs no declared language.
     */
    val reverseEnabled: Boolean = false,
    /** BCP-47 tag for the card front's language, e.g. `"en-US"`. See [speechReady]. */
    val frontLang: String? = null,
    /** BCP-47 tag for the card back's language, e.g. `"es-ES"`. See [speechReady]. */
    val backLang: String? = null,
    /**
     * Chunk the media re-host sweep should resume at (#53). Only meaningful for a clone, whose
     * card media starts out pinned to the original author's blobs.
     */
    val mediaRehostCursor: Int = 0,
    /** True once a full sweep found nothing left pinned to another author. */
    val mediaRehosted: Boolean = false,
) {
    /**
     * Whether the deck has declared what language each side is in.
     *
     * Listen and Speak hand text straight to the OS engines, which fall back to the *reader's*
     * device locale when given no language — reading a Spanish card with an English voice and
     * transcribing the reply with an English model. A deck that has not declared its pair
     * therefore offers neither feature, whatever [listenEnabled] and [speakEnabled] say; decks
     * published before the pair existed decode to nulls and land here.
     *
     * Covers Listen and Speak **only**. Typing compares two strings and has no engine to
     * substitute a locale into, so gating [typeEnabled] on this would withhold the one assisted
     * mode from exactly the decks — every import predating the pair — that have no other.
     */
    val speechReady: Boolean get() = frontLang != null && backLang != null

    // Built literally rather than via PubkyPaths: that lives in `data/pubky`, and domain models
    // must not depend on the data layer (Architecture §4.1).
    val pubkyUri: PubkyUri get() = PubkyUri("pubky://$authorPubky/pub/loopky/decks/$id/manifest.json")

    /**
     * The link Loopky shares for this deck: clickable where [pubkyUri] is plain text, opened by the
     * app as a verified App Link, and a web preview of the deck for anyone without it. Built
     * literally for the same reason as [pubkyUri]; `PubkyLinks.parse` reads it back, and
     * `PubkyLinksTest` holds the two together.
     */
    val webUrl: String
        get() = "https://loopky.app/deck/?author=${encodeUriComponent(authorPubky)}&id=${encodeUriComponent(id)}"
}

/**
 * One card-chunk record's metadata, as carried by the manifest. [updatedAt] is what lets a client
 * — including a follower syncing someone else's deck — re-fetch only the chunks that changed
 * rather than diffing a full card index.
 */
data class ChunkMeta(
    val n: Int,
    val count: Int,
    val updatedAt: Long,
)

/** Deck provenance. One block rather than a field per origin, so new sources don't churn the schema. */
data class DeckSource(
    val kind: Kind,
    /** `pubky://…/manifest.json` of the deck this came from, when [kind] is [Kind.Clone]. */
    val uri: String? = null,
    /** Source-specific id (an Anki deck id, a URL, …). */
    val originId: String? = null,
    val importedAt: Long? = null,
) {
    enum class Kind { Original, Clone, Import }
}

/**
 * Cards in study order. Card records come back keyed by id, so without this the display order is
 * whatever the random card ids happen to sort to. Order now travels on the card itself as
 * [Card.ord]; ties (and cards added locally before an ord is assigned) fall back to id so the
 * order is at least stable between renders.
 */
fun List<Card>.inStudyOrder(): List<Card> = sortedWith(compareBy({ it.ord }, { it.id }))

@JvmInline
value class PubkyUri(val value: String)
