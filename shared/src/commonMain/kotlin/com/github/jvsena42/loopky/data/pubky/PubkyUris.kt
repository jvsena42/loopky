package com.github.jvsena42.loopky.data.pubky

/**
 * The inverse of [PubkyPaths]: recognising a URI someone else produced.
 *
 * These matter because tags are read back from a public indexer, where any account can point any
 * label at any URI (issue #40). A claim is only worth what it resolves to, and the first half of
 * checking that is refusing to treat a URI as something it is not shaped like.
 */
internal object PubkyUris {

    /** The pubky a `pubky://{pubky}/…` URI belongs to, or `null` if [uri] isn't one. */
    fun ownerOf(uri: String): String? {
        if (!uri.startsWith(PubkyPaths.SCHEME)) return null
        val owner = uri.removePrefix(PubkyPaths.SCHEME)
            .substringBefore('/', missingDelimiterValue = "")
        return owner.ifEmpty { null }
    }

    /** True when [uri] is exactly some user's profile record, in either [SocialLayout]. */
    fun isProfile(uri: String): Boolean {
        val owner = ownerOf(uri) ?: return false
        return uri == SocialLayout.Legacy.profile(owner) || uri == SocialLayout.SocialV1.profile(owner)
    }

    /**
     * True when [uri] is exactly some user's pubky.app post record.
     *
     * Same job as [isProfile]: deciding which namespace a tag record on this subject belongs in.
     * Nexus indexes a tag into the post/user graph only for these two shapes — see
     * [com.github.jvsena42.loopky.data.repository.impl.TagRepositoryImpl].
     */
    fun isPost(uri: String): Boolean {
        val owner = ownerOf(uri) ?: return false
        // The v1 form is the versionless reference, `posts/{id}`, not a version's file.
        return listOf(SocialLayout.Legacy, SocialLayout.SocialV1).any { layout ->
            val root = layout.postsRoot(owner)
            val id = uri.removePrefix(root)
            uri.startsWith(root) && id.isNotEmpty() && '/' !in id
        }
    }

    /**
     * Parse a deck manifest URI back into its author and deck id, or `null` if [uri] is not
     * exactly `pubky://{author}/pub/loopky/decks/{deckId}/manifest.json`.
     *
     * Deliberately strict — a tag pointed at a card chunk, a media blob, another app's record or
     * an arbitrary web page must not read as a deck. Keep it in sync with `Deck.pubkyUri`, which
     * builds the same string literally because domain models cannot depend on this layer.
     */
    fun parseDeckManifest(uri: String): DeckRef? {
        val owner = ownerOf(uri) ?: return null
        val prefix = PubkyPaths.deckRoot(owner, deckId = "")
        if (!uri.startsWith(prefix)) return null
        val tail = uri.removePrefix(prefix)
        val deckId = tail.substringBefore('/', missingDelimiterValue = "")
        if (deckId.isEmpty() || tail != "$deckId/manifest.json") return null
        return DeckRef(authorPubky = owner, deckId = deckId)
    }
}

/** The author and deck id parsed out of a deck manifest URI. */
internal data class DeckRef(val authorPubky: String, val deckId: String)
