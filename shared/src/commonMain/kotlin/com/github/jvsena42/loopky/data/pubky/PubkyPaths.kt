package com.github.jvsena42.loopky.data.pubky

@Suppress("TooManyFunctions")
internal object PubkyPaths {
    const val APP_NAMESPACE = "pub/loopky"
    private const val PUBKY_APP_NAMESPACE = "pub/pubky.app"
    const val SCHEME = "pubky://"

    fun profile(pubky: String): String =
        "pubky://$pubky/$PUBKY_APP_NAMESPACE/profile.json"

    fun deckRoot(authorPubky: String, deckId: String): String =
        "pubky://$authorPubky/$APP_NAMESPACE/decks/$deckId"

    fun manifest(authorPubky: String, deckId: String): String =
        "${deckRoot(authorPubky, deckId)}/manifest.json"

    /**
     * One chunk of up to `CHUNK_SIZE` card records. Cards used to get a record each, which made a
     * 20k-card deck 20k requests to open and forced an unbounded card index into the manifest.
     */
    fun cardChunk(authorPubky: String, deckId: String, chunk: Int): String =
        "${deckRoot(authorPubky, deckId)}/cards/$chunk.json"

    fun cardsRoot(authorPubky: String, deckId: String): String =
        "${deckRoot(authorPubky, deckId)}/cards/"

    fun media(authorPubky: String, deckId: String, sha256: String, ext: String): String =
        "${deckRoot(authorPubky, deckId)}/media/$sha256.$ext"

    /**
     * A chunk of SRS review state, on **your** homeserver ([ownerPubky]) but keyed by the deck's
     * [authorPubky].
     *
     * Two things changed here (#43 §2). It is no longer nested under `/decks/{deckId}/`, because
     * your review state for someone else's deck was never the owner's data — that only looked
     * right while every deck was your own. And it is keyed by the deck's author, so two authors
     * whose decks happen to share a `deckId` no longer collide in your `srs/` tree. Following
     * decks from many authors is exactly what raises those odds.
     *
     * Chunked for the same reason cards are: one record per card made a studied 20k-card deck
     * ~40,000 records, and `dueForDeck` 20,000 GETs.
     */
    fun srsChunk(ownerPubky: String, authorPubky: String, deckId: String, chunk: Int): String =
        "${srsRoot(ownerPubky, authorPubky, deckId)}$chunk.json"

    fun srsRoot(ownerPubky: String, authorPubky: String, deckId: String): String =
        "pubky://$ownerPubky/$APP_NAMESPACE/srs/$authorPubky/$deckId/"

    /**
     * The user's own app settings, on their own homeserver.
     *
     * A synced record rather than a device preference because these change *scheduling* — the
     * review state they produce already syncs, so leaving the intervals device-local would have
     * two of your devices writing `dueAt`s computed from different rules. Architecture.md §7.5
     * named this path as where such a preference belongs.
     */
    fun settings(ownerPubky: String): String =
        "pubky://$ownerPubky/$APP_NAMESPACE/settings.json"

    fun decksList(authorPubky: String): String =
        "pubky://$authorPubky/$APP_NAMESPACE/decks/"

    /**
     * Social follows use the pubky.app native primitive: a record's *existence* under the owner's
     * `follows/` directory means "owner follows followee". Stored on the follower's homeserver so
     * listing your own follows is a single [PubkyClient.list] on [followsRoot].
     */
    fun followsRoot(ownerPubky: String): String =
        "pubky://$ownerPubky/$PUBKY_APP_NAMESPACE/follows/"

    fun follow(ownerPubky: String, followeePubky: String): String =
        "pubky://$ownerPubky/$PUBKY_APP_NAMESPACE/follows/$followeePubky"

    /**
     * Blocks are pubky.app's **mute** record, `/pub/pubky.app/mutes/{mutee}`, so a block made in
     * Loopky mutes on pubky.app as well, and one made there hides that person here (#577). Public,
     * like every record under `/pub/`.
     */
    fun mutesRoot(ownerPubky: String): String =
        "pubky://$ownerPubky/$PUBKY_APP_NAMESPACE/mutes/"

    fun mute(ownerPubky: String, muteePubky: String): String =
        "pubky://$ownerPubky/$PUBKY_APP_NAMESPACE/mutes/$muteePubky"

    /**
     * Decks the owner follows, on the **follower's** homeserver — existence-as-truth, like
     * [follow], so listing your subscriptions is one [PubkyClient.list] on [subscriptionsRoot].
     *
     * Loopky's own namespace rather than pubky.app's: a follow there means "I follow this *user*",
     * and there is no ecosystem primitive for following one deck. Keyed by author first so two
     * authors whose decks share a `deckId` cannot collide — the same reason `srs/` is author-keyed
     * (see [srsRoot]), and following decks from many authors is what raises those odds.
     */
    fun subscriptionsRoot(ownerPubky: String): String =
        "pubky://$ownerPubky/$APP_NAMESPACE/subscriptions/"

    fun subscription(ownerPubky: String, authorPubky: String, deckId: String): String =
        "${subscriptionsRoot(ownerPubky)}$authorPubky/$deckId.json"

    /**
     * pubky.app post record. The id is a Crockford-base32 microsecond timestamp ([PostIds]), not
     * content-derived — two announcements of the same deck are two posts, which is what a feed
     * expects.
     */
    fun post(ownerPubky: String, postId: String): String =
        "pubky://$ownerPubky/$PUBKY_APP_NAMESPACE/posts/$postId"

    /** The `posts/` directory under one account, for recognising a post URI. */
    fun postsRoot(ownerPubky: String): String =
        "pubky://$ownerPubky/$PUBKY_APP_NAMESPACE/posts/"

    /**
     * pubky.app tag record — the namespace Nexus indexes into its **user/post** graph. Use it only
     * when the tagged subject is a pubky.app profile or post; Nexus rejects the record outright for
     * any other subject (see [loopkyTag] and Architecture.md §7.7). The id is content-derived per
     * pubky-app-specs (`PubkyClient.createTagId`).
     */
    fun tag(ownerPubky: String, tagId: String): String =
        "pubky://$ownerPubky/$PUBKY_APP_NAMESPACE/tags/$tagId"

    /**
     * Tag record in Loopky's own namespace — the namespace Nexus indexes into its generic
     * **resource** graph, which is the only one that accepts a deck manifest as a subject.
     *
     * The `app` a resource is filed under is this path segment (`loopky`) verbatim, not anything
     * derived from the subject URI, so these are the records behind
     * `GET /v0/stream/resources?app=loopky`.
     */
    fun loopkyTag(ownerPubky: String, tagId: String): String =
        "pubky://$ownerPubky/$APP_NAMESPACE/tags/$tagId"

    /** Relative `media/<sha>.<ext>` reference stored inside card/manifest records. */
    fun relativeMedia(sha256: String, ext: String): String = "media/$sha256.$ext"
}
