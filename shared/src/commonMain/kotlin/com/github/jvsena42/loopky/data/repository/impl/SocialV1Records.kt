package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.FollowDto
import com.github.jvsena42.loopky.data.pubky.MuteDto
import com.github.jvsena42.loopky.data.pubky.PostDto
import com.github.jvsena42.loopky.data.pubky.PostKinds
import com.github.jvsena42.loopky.data.pubky.ProfileDto
import com.github.jvsena42.loopky.data.pubky.SocialLayout
import com.github.jvsena42.loopky.data.pubky.SocialPostDto
import com.github.jvsena42.loopky.data.pubky.SocialPostKinds
import com.github.jvsena42.loopky.data.pubky.TagDto
import kotlinx.serialization.KSerializer

// One v0 record in its v1 form, as pubky-social-migrator 1.0.0-rc.2 writes it, and the v1
// reader's rules for what it refuses (#616).

/** [profile] as v1 stores it, or null when the v1 reader would refuse it. */
internal fun socialProfile(profile: ProfileDto): SocialProfileDto? {
    val name = profile.name?.trim().orEmpty()
    val bio = profile.bio?.trim()?.ifEmpty { null }
    if (codePoints(name) !in NAME_LENGTH || (bio != null && codePoints(bio) > BIO_MAX)) return null
    // The reference drops a picture its gate refuses rather than the profile.
    val image = profile.image?.let(::rewrite)?.takeIf { uri ->
        IMAGE_SCHEMES.any { uri.startsWith(it) } && codePoints(uri) <= IMAGE_MAX
    }
    return SocialProfileDto(name = name, bio = bio, image = image)
}

/** A record the reader cannot parse is skipped, as the reference skips it. */
private fun <T> decodeOrSkip(serializer: KSerializer<T>, json: String): T? =
    runCatching { loopkyJson.decodeFromString(serializer, json) }.getOrNull()

internal fun <T> encoded(serializer: KSerializer<T>, value: T): String = loopkyJson.encodeToString(serializer, value)

internal fun movedPost(owner: String, id: String, json: String): Pair<String, String>? {
    val post = decodeOrSkip(PostDto.serializer(), json)?.let(::socialPost) ?: return null
    val valid = isKey(id) && post.content.isNotBlank() && codePoints(post.content) <= NOTE_MAX
    return if (valid) SocialLayout.SocialV1.postVersion(owner, id) to encoded(SocialPostDto.serializer(), post) else null
}

internal fun movedFollow(owner: String, followee: String, json: String): Pair<String, String>? {
    val follow = decodeOrSkip(FollowDto.serializer(), json)?.takeIf { isPubky(followee) } ?: return null
    // Loopky wrote milliseconds to v0; the spec, and every v1 follow Loopky writes, use µs.
    val createdAt = follow.created_at.let { if (it < MICROS_FLOOR) it * MICROS_PER_MILLI else it }
    return SocialLayout.SocialV1.follow(owner, followee) to encoded(FollowDto.serializer(), FollowDto(created_at = createdAt))
}

internal fun movedMute(owner: String, mutee: String, json: String): Pair<String, String>? {
    val mute = decodeOrSkip(MuteDto.serializer(), json)?.takeIf { isPubky(mutee) } ?: return null
    return SocialLayout.SocialV1.mute(owner, mutee) to encoded(MuteDto.serializer(), mute)
}

internal fun movedTag(owner: String, json: String, tagId: (String, String) -> String): Pair<String, String>? {
    val tag = decodeOrSkip(TagDto.serializer(), json) ?: return null
    val moved = TagDto(uri = rewrite(tag.uri), label = foldLabel(tag.label), created_at = tag.created_at)
    if (!isLabel(moved.label)) return null
    return SocialLayout.SocialV1.tag(owner, tagId(moved.uri, moved.label)) to encoded(TagDto.serializer(), moved)
}

private fun socialPost(post: PostDto) = SocialPostDto(
    content = post.content,
    kind = if (post.kind == PostKinds.SHORT) SocialPostKinds.NOTE else post.kind,
    parent = post.parent?.let(::rewrite),
    embed = post.embed?.uri?.let(::rewrite),
)

private const val NOTE_MAX = 2_000
internal const val MICROS_PER_MILLI = 1_000L

/** A `created_at` under this is milliseconds: 1e14 µs is 1973, 1e14 ms is the year 5138. */
private const val MICROS_FLOOR = 100_000_000_000_000L
private val NAME_LENGTH = 3..50
private const val BIO_MAX = 160
private const val IMAGE_MAX = 300
private val IMAGE_SCHEMES = listOf("pubky://", "http://", "https://")
private const val LABEL_MAX = 20
private const val PUBKY_LENGTH = 52
private const val Z_BASE32 = "ybndrfg8ejkmcpqxot1uwisza345h769"

private fun codePoints(text: String): Int = text.count { !it.isLowSurrogate() }

/** One path segment, as a post id is. */
private fun isKey(segment: String) = segment.isNotEmpty() && '/' !in segment

private fun isPubky(key: String) = key.length == PUBKY_LENGTH && key.all { it in Z_BASE32 }

/** `validate_tag_label`: 1–20 code points, no whitespace, comma or colon. */
private fun isLabel(label: String) =
    codePoints(label) in 1..LABEL_MAX && label.none { it.isWhitespace() || it == ',' || it == ':' }

/**
 * A `pubky://` reference in its v1 spelling, as the reference migrator rewrites it: a profile, post
 * or follow under anyone's `/pub/pubky.app/` moves to `/pub/social/v1/`. Everything else, a deck
 * included, stays as written.
 */
internal fun rewrite(uri: String): String {
    if (!uri.startsWith("pubky://")) return uri
    val host = uri.removePrefix("pubky://").substringBefore('/')
    val path = uri.removePrefix("pubky://$host/")
    val legacy = "pub/pubky.app/"
    if (!path.startsWith(legacy)) return uri
    val rest = path.removePrefix(legacy)
    val segments = rest.split('/')
    val v1 = SocialLayout.SocialV1
    return when {
        rest == "profile.json" -> v1.profile(host)
        segments.size == 2 && segments[0] == "posts" && segments[1].isNotEmpty() -> v1.post(host, segments[1])
        segments.size == 2 && segments[0] == "follows" && segments[1].isNotEmpty() -> v1.follow(host, segments[1])
        else -> uri
    }
}

/** The v1 label fold: trimmed, ASCII letters lowercased. Loopky's labels are already folded (#479). */
internal fun foldLabel(label: String): String =
    label.trim().map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString("")
