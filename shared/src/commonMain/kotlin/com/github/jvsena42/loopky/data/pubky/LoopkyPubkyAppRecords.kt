package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.DeckRequests
import com.github.jvsena42.loopky.domain.model.ReservedTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Which records under `/pub/pubky.app/` Loopky itself writes. Every Pubky app shares that
 * namespace, so these rules decide what Delete account may remove there and whether anything
 * else uses the account.
 *
 * Each rule is strict on purpose: a record wrongly called Loopky's is a record another app owns,
 * and deleting it cannot be undone. A record they cannot place counts as not Loopky's.
 */
internal object LoopkyPubkyAppRecords {

    /** First-level entries of `/pub/pubky.app/` that Loopky writes. Anything else is another app. */
    val ENTRIES: Set<String> = setOf(PROFILE, "follows/", "mutes/", "posts/", "tags/")

    /** The profile fields the profile editor writes. It never sets `image`; it only carries one forward. */
    private val PROFILE_FIELDS = setOf("name", "bio", "image")

    private val DECK_LABEL = ReservedTags.DECK.value
    private val DECK_REQUEST_LABELS = DeckRequests.TAGS.map { it.value }.toSet()

    /** Every label [isLoopkyPost] looks for, so a caller missing some can ask what they could change. */
    val POST_LABELS: Set<String> = DECK_REQUEST_LABELS + DECK_LABEL

    private const val PUBKY_SCHEME = "pubky://"
    private const val PROFILE = "profile.json"
    private const val PROFILE_PATH = "pub/pubky.app/$PROFILE"
    private const val LOOPKY_PREFIX = "pub/loopky/"
    private const val LOOPKY_DECKS_PREFIX = "pub/loopky/decks/"

    private val lenient = Json { ignoreUnknownKeys = true }

    /**
     * Whether `profile.json` could have been written by Loopky alone: only `name` and `bio`, plus an
     * `image` that is absent or null. A picture, links or a status were set by another client.
     */
    fun isLoopkyProfile(json: String): Boolean {
        val profile = runCatching { lenient.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return false
        return profile.keys.all { it in PROFILE_FIELDS } && (profile["image"] ?: JsonNull) == JsonNull
    }

    /**
     * Whether [post], at one of [owner]'s post paths, is one Loopky published. [labels] are the
     * labels [owner] put on it, lowercased.
     *
     * - An announcement of one of [owner]'s own decks: a link embed of their deck. The deck path
     *   is enough on its own here, because announcing is the only thing that writes it.
     * - An announcement of a followed or copied deck: a link embed of any deck, plus the
     *   `loopky-deck` label. A deck link alone could be someone sharing it by hand.
     * - A recap: a link embed of [owner]'s recap page.
     * - A deck request: a short post with no embed, labelled both `loopky` and `deck-request`.
     *
     * Loopky never writes a reply or attaches a file, so a post with a parent or attachments is
     * another app's whatever it embeds or is labelled.
     */
    fun isLoopkyPost(owner: String, post: PostDto, labels: Set<String>): Boolean {
        if (post.parent != null || !post.attachments.isNullOrEmpty()) return false
        val embed = post.embed
        if (embed == null) {
            return post.kind == PostKinds.SHORT && labels.containsAll(DECK_REQUEST_LABELS)
        }
        if (post.kind != PostKinds.LINK || embed.kind != PostKinds.LINK) return false
        val uri = embed.uri
        return uri.startsWith(PubkyPaths.decksList(owner)) ||
            (isDeckUri(uri) && DECK_LABEL in labels) ||
            PubkyLinks.isRecapWebUrlOf(uri, owner)
    }

    /**
     * Whether a label on [subject] is Loopky's: on a profile, on a deck, on one of [loopkyPosts],
     * or on an own post that is gone. [ownPosts] is the owner's posts root; [postPaths] the posts
     * it holds.
     */
    fun isLoopkyLabel(subject: String, ownPosts: String, loopkyPosts: Set<String>, postPaths: Set<String>): Boolean =
        (subject.startsWith(ownPosts) && (subject in loopkyPosts || subject !in postPaths)) ||
            isProfileUri(subject) ||
            isLoopkyNamespaceUri(subject)

    /** Whether [uri] names anyone's `profile.json`. */
    fun isProfileUri(uri: String): Boolean = pathOf(uri) == PROFILE_PATH

    /** Whether [uri] is anywhere in anyone's `/pub/loopky/`. Decks were tagged here before Nexus indexed resources. */
    fun isLoopkyNamespaceUri(uri: String): Boolean = pathOf(uri)?.startsWith(LOOPKY_PREFIX) == true

    private fun isDeckUri(uri: String): Boolean = pathOf(uri)?.startsWith(LOOPKY_DECKS_PREFIX) == true

    /** The part of a `pubky://{key}/…` address after the key, or null when [uri] is not one. */
    private fun pathOf(uri: String): String? {
        if (!uri.startsWith(PUBKY_SCHEME)) return null
        val rest = uri.removePrefix(PUBKY_SCHEME)
        val slash = rest.indexOf('/')
        return if (slash <= 0) null else rest.substring(slash + 1)
    }
}
