package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.MediaRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `--json` view of a deck and a card.
 *
 * Their own types rather than serialising the domain models, for two reasons that pull the same
 * way. The domain models are free to change shape — they are internal to the client — while this
 * is a versioned API surface an agent parses (`SCHEMA_VERSION`). And what belongs in the output is
 * not what belongs in memory: the chunk table is an implementation detail nobody consuming this
 * needs, while an image ref is *the* thing a caller has to be able to check.
 *
 * That last part is the reason these carry media at all. An agent cannot look at a screenshot to
 * confirm the picture it attached is the picture it meant, so a read has to echo back the stored
 * ref — its URL, or the sha256 of the blob — and the caller diffs intent against result from
 * these bytes (#54, finding 5).
 *
 * Field names are snake_case because that is what the homeserver records use, and a caller
 * juggling both should not have to remember which side of the wire it is on.
 */
@Serializable
data class DeckView(
    val id: String,
    @SerialName("author_pubky") val authorPubky: String,
    val title: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("card_count") val cardCount: Int = 0,
    @SerialName("cover_emoji") val coverEmoji: String? = null,
    @SerialName("cover_image") val coverImage: MediaView? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    /** True between a publish's two manifest writes — the deck is claimed, its chunks may not be up. */
    val incomplete: Boolean = false,
    @SerialName("front_lang") val frontLang: String? = null,
    @SerialName("back_lang") val backLang: String? = null,
    @SerialName("listen_enabled") val listenEnabled: Boolean = false,
    @SerialName("speak_enabled") val speakEnabled: Boolean = false,
    @SerialName("type_enabled") val typeEnabled: Boolean = false,
    @SerialName("reverse_enabled") val reverseEnabled: Boolean = false,
    /**
     * The manifest's chunk table: which card records exist, how full each one is, and when it last
     * changed.
     *
     * Here because `deck compact`'s entire job is rearranging these, and without them its effect is
     * invisible through the verification channel except as counts in its own result — a caller
     * cannot check the work, only take the command's word for it. `chunks[].updated_at` is also
     * what a follower diffs to re-fetch one record instead of a whole deck, so a caller reasoning
     * about sync cost needs it.
     *
     * **Not contiguous.** Compaction folds a pair of neighbours and drops the higher `n`, so the
     * numbering has gaps — anything walking this must read `n`, never `0 until size`.
     */
    val chunks: List<ChunkView> = emptyList(),
    val uri: String,
    /** The `https://loopky.app` link the apps share: what an agent hands a person, where [uri] is for clients. */
    @SerialName("share_url") val shareUrl: String,
)

/** One card record, as the manifest describes it. */
@Serializable
data class ChunkView(
    val n: Int,
    val count: Int,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class CardView(
    val id: String,
    @SerialName("deck_id") val deckId: String,
    val front: SideView,
    val back: SideView,
    /** Study order. Sparse, so consecutive cards are 1000 apart rather than 1 (see `Card.ord`). */
    val ord: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
)

@Serializable
data class SideView(
    val text: String? = null,
    val image: MediaView? = null,
)

/**
 * A stored media reference, as stored.
 *
 * [url] set means a remote web image referenced by address, with no blob on the homeserver and no
 * bytes ever crossing the wire (#167) — which is what makes image columns in the import format
 * cheap. Otherwise [sha256] identifies a blob, and [uri] is set only when that blob still lives
 * under *another* author's deck, which is what a fresh clone looks like before its media has been
 * re-hosted.
 */
@Serializable
data class MediaView(
    val url: String? = null,
    val sha256: String? = null,
    val mime: String? = null,
    val uri: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

fun Deck.toView(): DeckView = DeckView(
    id = id,
    authorPubky = authorPubky,
    title = title,
    description = description,
    tags = tags.map { it.value },
    cardCount = cardCount,
    coverEmoji = coverEmoji,
    coverImage = coverImageRef?.toView(),
    createdAt = createdAt,
    updatedAt = updatedAt,
    incomplete = incomplete,
    frontLang = frontLang,
    backLang = backLang,
    listenEnabled = listenEnabled,
    speakEnabled = speakEnabled,
    typeEnabled = typeEnabled,
    reverseEnabled = reverseEnabled,
    chunks = chunks.map { ChunkView(n = it.n, count = it.count, updatedAt = it.updatedAt) },
    uri = pubkyUri.value,
    shareUrl = webUrl,
)

fun Card.toView(): CardView = CardView(
    id = id,
    deckId = deckId,
    front = front.toView(),
    back = back.toView(),
    ord = ord,
    updatedAt = updatedAt,
)

fun CardSide.toView(): SideView = SideView(text = text, image = imageRef?.toView())

fun MediaRef.Image.toView(): MediaView = MediaView(
    url = url,
    sha256 = sha256.takeIf { it.isNotEmpty() },
    mime = mime.takeIf { it.isNotEmpty() },
    uri = uri,
    width = width,
    height = height,
)

/** One line per deck, for a person. Tab-separated so `cut` still works on it. */
fun DeckView.toLine(): String =
    listOf(id, cardCount.toString(), title, tags.joinToString(",")).joinToString("\t")

/**
 * One line per card.
 *
 * Newlines in a side are escaped rather than printed: a card side is not one line (an Anki field
 * routinely holds several), and letting one through would make the line count disagree with the
 * card count for a reader that is counting.
 */
fun CardView.toLine(): String = listOf(
    id,
    front.render(),
    back.render(),
).joinToString("\t")

private fun SideView.render(): String {
    val body = text?.replace("\n", "\\n").orEmpty()
    val picture = image?.let { it.url ?: it.sha256 }?.let { " [img:$it]" }.orEmpty()
    return body + picture
}
