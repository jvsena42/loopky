package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.data.pubky.ManifestDto
import com.github.jvsena42.loopky.data.pubky.toDomain
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.CachedDecks
import com.github.jvsena42.loopky.domain.model.Deck
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The decks last listed from the homeserver, kept on the device so Home, the library and Profile
 * paint on the frame they are composed instead of after a directory listing plus one manifest GET
 * per deck.
 *
 * **A display copy, and only that.** The chunk table is dropped on the way in, because nothing
 * here may reach a write path: a manifest patched from a stale `chunks` orphans card records
 * (Architecture.md §8.0). `DeckRepositoryImpl`'s in-memory cache is deliberately *not* hydrated
 * from this — a deck is re-fetched before anything acts on it, and this exists so the screen is
 * not a spinner while that happens.
 *
 * Alongside [StudyProgressStore], and account-scoped for the same reason: which decks are yours is
 * a claim about a person, and a record belonging to someone else reads as absent.
 */
interface DeckCacheStore {
    /** [ownerPubky]'s last-seen library, or null before a listing has succeeded on this device. */
    suspend fun load(ownerPubky: String): CachedDecks?

    suspend fun save(ownerPubky: String, decks: CachedDecks)

    /**
     * Remove the snapshot entirely.
     *
     * For account deletion, where saving an empty one would do the visible job and still leave a
     * record naming the deleted pubky on the device. Not account-scoped, deliberately: there is one
     * key, the caller is erasing the only account that could own it, and a `clear` that first had
     * to agree about whose it was could leave a stranger's behind.
     */
    suspend fun clear()
}

internal const val KEY_DECK_CACHE = "deck_cache"

/** One library snapshot on disk. Manifests are stored in their on-wire shape, minus the chunks. */
@Serializable
internal data class StoredDeckCache(
    val ownerPubky: String,
    val owned: List<ManifestDto> = emptyList(),
    val followed: List<ManifestDto> = emptyList(),
)

/** Shared by every platform implementation so the on-disk shape cannot drift between them. */
private val deckCacheJson = Json { ignoreUnknownKeys = true }

/** Strips the chunk table: see the class doc for why this must never carry one. */
private fun Deck.toCacheDto(): ManifestDto = toDto().copy(chunks = emptyList())

internal fun encodeDeckCache(ownerPubky: String, decks: CachedDecks): String =
    deckCacheJson.encodeToString(
        StoredDeckCache(
            ownerPubky = ownerPubky,
            owned = decks.owned.map { it.toCacheDto() },
            followed = decks.followed.map { it.toCacheDto() },
        ),
    )

/**
 * A snapshot that cannot be read costs one cold start's worth of spinner — never an error. A
 * snapshot belonging to a different account is treated the same way as an unreadable one.
 */
internal fun decodeDeckCache(payload: String?, ownerPubky: String): CachedDecks? {
    if (payload.isNullOrBlank()) return null
    return runCatching { deckCacheJson.decodeFromString<StoredDeckCache>(payload) }
        .getOrNull()
        ?.takeIf { it.ownerPubky == ownerPubky }
        ?.let { CachedDecks(it.owned.map(ManifestDto::toDomain), it.followed.map(ManifestDto::toDomain)) }
}
