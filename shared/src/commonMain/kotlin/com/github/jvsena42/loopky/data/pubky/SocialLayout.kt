package com.github.jvsena42.loopky.data.pubky

/**
 * Which layout a user's social records are in, as Nexus reports it (`epoch` on `/v0/user/{id}/details`).
 *
 * pubky/pubky-nexus#1129 indexes one layout per user: [V0] reads `/pub/pubky.app/`, [V1] reads
 * `/pub/social/v1/` and ignores the user's `pubky.app` writes from then on. A user moves from [V0]
 * to [V1] when `/pub/social/v1/profile.json` is written, which pubky.app's migrator does last
 * (pubky/pubky-app#3140). Nothing moves a user back.
 */
enum class SocialEpoch(val wire: String) {
    V0("v0"),
    V1("v1"),
    ;

    companion object {
        /** Nexus omits the field for a user it has never flipped, which is [V0]. */
        fun fromWire(value: String?): SocialEpoch = entries.firstOrNull { it.wire == value } ?: V0
    }
}

/**
 * Where an account's social records live, per [SocialEpoch]. Only the social records: decks, cards,
 * review state and settings stay under `/pub/loopky/` in either epoch.
 *
 * A post has two addresses in [V1][SocialEpoch.V1]: the file one version is stored at
 * ([postVersion], `posts/{id}/{editId}.json`) and the versionless reference every tag, reply and
 * embed uses ([post], `posts/{id}`). In [V0][SocialEpoch.V0] they are the same.
 */
sealed interface SocialLayout {
    val epoch: SocialEpoch

    fun profile(owner: String): String
    fun followsRoot(owner: String): String
    fun follow(owner: String, followee: String): String
    fun mutesRoot(owner: String): String
    fun mute(owner: String, mutee: String): String
    fun postsRoot(owner: String): String

    /** The address tags and embeds name a post by. */
    fun post(owner: String, postId: String): String

    /** The file a new post is written to. */
    fun postVersion(owner: String, postId: String): String
    fun tagsRoot(owner: String): String
    fun tag(owner: String, tagId: String): String

    /** The followee's pubky from one entry [PubkyClient.list] returned under [followsRoot], or null. */
    fun followeeOf(entry: String): String? = keyAfter(entry, "/follows/")

    /** The mutee's pubky from one entry under [mutesRoot], or null. */
    fun muteeOf(entry: String): String? = keyAfter(entry, "/mutes/")

    /** Records the epoch's spec puts a `.json` on drop it here; a v0 key has none. */
    fun keyAfter(entry: String, marker: String): String?

    /** pubky.app's layout, `pubky-app-specs` 0.x. */
    data object Legacy : SocialLayout {
        override val epoch = SocialEpoch.V0
        private const val ROOT = "pub/pubky.app"

        override fun profile(owner: String) = "pubky://$owner/$ROOT/profile.json"
        override fun followsRoot(owner: String) = "pubky://$owner/$ROOT/follows/"
        override fun follow(owner: String, followee: String) = "${followsRoot(owner)}$followee"

        /** Public, like every record under `/pub/` (#577). */
        override fun mutesRoot(owner: String) = "pubky://$owner/$ROOT/mutes/"
        override fun mute(owner: String, mutee: String) = "${mutesRoot(owner)}$mutee"
        override fun postsRoot(owner: String) = "pubky://$owner/$ROOT/posts/"
        override fun post(owner: String, postId: String) = "${postsRoot(owner)}$postId"
        override fun postVersion(owner: String, postId: String) = post(owner, postId)
        override fun tagsRoot(owner: String) = "pubky://$owner/$ROOT/tags/"
        override fun tag(owner: String, tagId: String) = "${tagsRoot(owner)}$tagId"

        override fun keyAfter(entry: String, marker: String): String? =
            entry.substringAfter("/$ROOT$marker", missingDelimiterValue = "")
                .substringBefore('/')
                .takeIf { it.isNotEmpty() }
    }

    /** `pubky-social-specs` 1.x: `/pub/social/v1/`, with mutes private under `/priv/social/v1/`. */
    data object SocialV1 : SocialLayout {
        override val epoch = SocialEpoch.V1
        private const val PUB = "pub/social/v1"
        private const val PRIV = "priv/social/v1"

        override fun profile(owner: String) = "pubky://$owner/$PUB/profile.json"
        override fun followsRoot(owner: String) = "pubky://$owner/$PUB/follows/"
        override fun follow(owner: String, followee: String) = "${followsRoot(owner)}$followee.json"

        /** Private: only a session holding `/priv/social/v1/` can read or write it, and Nexus never sees it. */
        override fun mutesRoot(owner: String) = "pubky://$owner/$PRIV/mutes/"
        override fun mute(owner: String, mutee: String) = "${mutesRoot(owner)}$mutee.json"
        override fun postsRoot(owner: String) = "pubky://$owner/$PUB/posts/"
        override fun post(owner: String, postId: String) = "${postsRoot(owner)}$postId"

        /** A new post's first version: `editId == id`, as the spec mints it. */
        override fun postVersion(owner: String, postId: String) = "${postsRoot(owner)}$postId/$postId.json"
        override fun tagsRoot(owner: String) = "pubky://$owner/$PUB/tags/"
        override fun tag(owner: String, tagId: String) = "${tagsRoot(owner)}$tagId.json"

        override fun keyAfter(entry: String, marker: String): String? {
            val tail = entry.substringAfter(marker, missingDelimiterValue = "")
            if (!entry.contains("/$PUB$marker") && !entry.contains("/$PRIV$marker")) return null
            return tail.removeSuffix(".json").takeIf { it.isNotEmpty() && '/' !in it }
        }
    }

    companion object {
        fun of(epoch: SocialEpoch): SocialLayout = when (epoch) {
            SocialEpoch.V0 -> Legacy
            SocialEpoch.V1 -> SocialV1
        }
    }
}
