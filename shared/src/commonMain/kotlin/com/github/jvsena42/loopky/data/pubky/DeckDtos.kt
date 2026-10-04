package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.ChunkMeta
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.Tag
import kotlinx.serialization.Serializable

internal const val SCHEMA_VERSION = 1

/**
 * Cards per chunk record. The single knob for the storage/write trade-off: bigger chunks mean
 * fewer requests to open a deck but more bytes rewritten per card edit.
 *
 * 100 is deliberately conservative — the homeserver's per-record ceiling is not documented
 * anywhere in this repo or the FFI, so this sits well under any plausible limit. Readers derive
 * the chunk count from the manifest and never assume this value, so it can be retuned without a
 * migration; only newly written chunks pick up the new size.
 */
internal const val CHUNK_SIZE = 100

@Serializable
internal data class ManifestDto(
    val schema_version: Int = SCHEMA_VERSION,
    val deck_id: String,
    val author_pubky: String,
    val title: String,
    val description: String? = null,
    val cover_emoji: String? = null,
    val cover_image_ref: MediaRefDto? = null,
    val tags: List<String> = emptyList(),
    val created_at: Long,
    val updated_at: Long,
    /**
     * Denormalized card total, so a deck tile renders without fetching a single chunk. This
     * replaces the unbounded `cards[]` index that made the manifest grow ~73 bytes per card.
     */
    val card_count: Int = 0,
    val chunks: List<ChunkDto> = emptyList(),
    val source: SourceDto? = null,
    /** Set while a publish is in flight; absent once every chunk is up. */
    val incomplete: Boolean = false,
    val listen_enabled: Boolean = true,
    val speak_enabled: Boolean = true,
    /**
     * Additive like the two above, so the schema stays at 1. Defaults **off** rather than on:
     * a manifest written before typing existed says nothing about whether its author wanted the
     * mode, and off is the reading that cannot surprise anyone studying it.
     */
    val type_enabled: Boolean = false,
    /**
     * Additive and defaulted like the three above. Off rather than on for the same reason
     * `type_enabled` is: a manifest written before both directions existed says nothing about
     * whether its author meant the deck to be drilled that way.
     */
    val reverse_enabled: Boolean = false,
    /**
     * BCP-47 tag for the language of the card front / back, e.g. `"en-US"`, `"es-ES"`. Absent on
     * manifests written before the pair existed, which is why listen/speak go inert without them
     * rather than falling back to the reader's device locale — see `Deck.speechReady`.
     */
    val front_lang: String? = null,
    val back_lang: String? = null,
    /**
     * Chunk to resume the media re-host scan at (#53). Meaningless unless `source.kind == "clone"`.
     *
     * A cursor rather than deriving progress from the refs themselves: a chunk with nothing pinned
     * in it is never rewritten, so without this a budgeted sweep restarts at chunk 0 every run and
     * a deck with more chunks than the budget never finishes.
     */
    val media_rehost_cursor: Int = 0,
    /** True once a full pass found nothing left pinned to another author. */
    val media_rehosted: Boolean = false,
    /** Set while a whole-deck reorder is between its first and last write (#449). */
    val reorder_pending: Boolean = false,
)

@Serializable
internal data class ChunkDto(
    val n: Int,
    val count: Int,
    val updated_at: Long,
)

/** Deck provenance (#43 §6 / #33 blocker 3). One block so new sources don't each add a field. */
@Serializable
internal data class SourceDto(
    val kind: String,
    val uri: String? = null,
    val origin_id: String? = null,
    val imported_at: Long? = null,
)

/** One `cards/{n}.json` record: a batch of cards rather than a record per card. */
@Serializable
internal data class CardChunkDto(
    val schema_version: Int = SCHEMA_VERSION,
    val deck_id: String,
    val chunk: Int,
    val cards: List<CardDto> = emptyList(),
)

@Serializable
internal data class CardDto(
    val schema_version: Int = SCHEMA_VERSION,
    val id: String,
    val deck_id: String,
    val updated_at: Long,
    val front: CardSideDto,
    val back: CardSideDto,
    /** Study order; sparse so inserts take a midpoint. Absent on decks written before #43. */
    val ord: Long = 0L,
)

@Serializable
internal data class CardSideDto(
    val text: String? = null,
    val image_ref: MediaRefDto? = null,
    val audio_ref: MediaRefDto? = null,
)

@Serializable
internal data class MediaRefDto(
    val path: String = "",
    val mime: String,
    val sha256: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val duration_ms: Long? = null,
    /** Set for web images referenced by URL; [path]/[sha256] are then empty. */
    val url: String? = null,
    /** Absolute `pubky://…` ref when the blob lives under another author's deck (#43 §3). */
    val uri: String? = null,
)

// --- Mapping ------------------------------------------------------------------

internal fun Deck.toDto() = ManifestDto(
    deck_id = id,
    author_pubky = authorPubky,
    title = title,
    description = description,
    cover_emoji = coverEmoji,
    cover_image_ref = coverImageRef?.toDto(),
    tags = tags.map { it.value },
    created_at = createdAt,
    updated_at = updatedAt,
    card_count = cardCount,
    chunks = chunks.map { ChunkDto(it.n, it.count, it.updatedAt) },
    source = source?.toDto(),
    incomplete = incomplete,
    listen_enabled = listenEnabled,
    speak_enabled = speakEnabled,
    type_enabled = typeEnabled,
    reverse_enabled = reverseEnabled,
    front_lang = frontLang,
    back_lang = backLang,
    media_rehost_cursor = mediaRehostCursor,
    media_rehosted = mediaRehosted,
    reorder_pending = reorderPending,
)

internal fun ManifestDto.toDomain() = Deck(
    id = deck_id,
    authorPubky = author_pubky,
    title = title,
    description = description,
    coverEmoji = cover_emoji,
    coverImageRef = cover_image_ref?.toImageDomain(),
    tags = tags.map { Tag(it) },
    createdAt = created_at,
    updatedAt = updated_at,
    cardCount = card_count,
    chunks = chunks.map { ChunkMeta(it.n, it.count, it.updated_at) },
    source = source?.toDomain(),
    incomplete = incomplete,
    listenEnabled = listen_enabled,
    speakEnabled = speak_enabled,
    typeEnabled = type_enabled,
    reverseEnabled = reverse_enabled,
    frontLang = front_lang,
    backLang = back_lang,
    mediaRehostCursor = media_rehost_cursor,
    mediaRehosted = media_rehosted,
    reorderPending = reorder_pending,
)

internal fun DeckSource.toDto() = SourceDto(
    kind = kind.name.lowercase(),
    uri = uri,
    origin_id = originId,
    imported_at = importedAt,
)

internal fun SourceDto.toDomain() = DeckSource(
    kind = when (kind.lowercase()) {
        "clone" -> DeckSource.Kind.Clone
        "import" -> DeckSource.Kind.Import
        else -> DeckSource.Kind.Original
    },
    uri = uri,
    originId = origin_id,
    importedAt = imported_at,
)

internal fun Card.toDto() = CardDto(
    id = id,
    deck_id = deckId,
    updated_at = updatedAt,
    front = front.toDto(),
    back = back.toDto(),
    ord = ord,
)

internal fun CardDto.toDomain() = Card(
    id = id,
    deckId = deck_id,
    updatedAt = updated_at,
    front = front.toDomain(),
    back = back.toDomain(),
    ord = ord,
)

internal fun CardSide.toDto() = CardSideDto(
    text = text,
    image_ref = imageRef?.toDto(),
    audio_ref = audioRef?.toDto(),
)

internal fun CardSideDto.toDomain() = CardSide(
    text = text,
    imageRef = image_ref?.toImageDomain(),
    audioRef = audio_ref?.toAudioDomain(),
)

internal fun MediaRef.Image.toDto() = MediaRefDto(
    path = path,
    mime = mime,
    sha256 = sha256,
    width = width,
    height = height,
    url = url,
    uri = uri,
)

internal fun MediaRef.Audio.toDto() = MediaRefDto(
    path = path,
    mime = mime,
    sha256 = sha256,
    duration_ms = durationMs,
    uri = uri,
)

internal fun MediaRefDto.toImageDomain() = MediaRef.Image(
    path = path,
    mime = mime,
    sha256 = sha256,
    width = width,
    height = height,
    url = url,
    uri = uri,
)

internal fun MediaRefDto.toAudioDomain() = MediaRef.Audio(
    path = path,
    mime = mime,
    sha256 = sha256,
    durationMs = duration_ms,
    uri = uri,
)
