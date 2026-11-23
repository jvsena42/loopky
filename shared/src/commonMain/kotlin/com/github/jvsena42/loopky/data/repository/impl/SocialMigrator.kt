package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.FollowDto
import com.github.jvsena42.loopky.data.pubky.MuteDto
import com.github.jvsena42.loopky.data.pubky.PostDto
import com.github.jvsena42.loopky.data.pubky.PostKinds
import com.github.jvsena42.loopky.data.pubky.ProfileDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.SocialEpochs
import com.github.jvsena42.loopky.data.pubky.SocialLayout
import com.github.jvsena42.loopky.data.pubky.SocialPostDto
import com.github.jvsena42.loopky.data.pubky.SocialPostKinds
import com.github.jvsena42.loopky.data.pubky.TagDto
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.domain.model.PubkyAppFootprint
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.util.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile

/**
 * Moves an account Loopky alone wrote to from `/pub/pubky.app/` to `/pub/social/v1/` (#616).
 *
 * pubky.app migrates the accounts that use it; Nexus will not index a `v0` user's `social/v1`
 * records, so nobody else moves an account only Loopky wrote to. This copies what
 * `pubky-social-migrator` 1.0.0-rc.2 would, for the records Loopky writes: a post `posts/{id}`
 * becomes `posts/{id}/{id}.json`, a `pubky.app` reference takes its v1 spelling, a tag's id is
 * derived again from its rewritten target, a mute moves under `/priv/`, and `created_at` is kept.
 *
 * Four things not to undo:
 * - It runs only once production Nexus reads `social/v1` ([servesSocialV1]). Before that, a flip
 *   would leave the account indexed from a tree Nexus does not read.
 * - Only a [PubkyAppFootprint.LoopkyOnly] or [PubkyAppFootprint.Empty] account is moved. Any other
 *   is pubky.app's to migrate, and an Unknown one is asked again next time.
 * - `profile.json` goes last, because writing it is the flip: Nexus ignores every `social/v1`
 *   record written before it, so an interrupted run changes nothing and the next one finishes it.
 *   The v0 tree is left in place, as the reference migrator leaves it.
 * - Until the flip nothing in `social/v1` is indexed, and the classification proved every record
 *   there is Loopky's, so a copy whose source has gone since an earlier run is deleted. A copy that
 *   is already there is not written again; the reference migrator does not overwrite one either.
 */
internal class SocialMigrator(
    private val pubky: PubkyClient,
    private val footprint: PubkyAppFootprintReader,
    private val epochs: SocialEpochs,
    private val servesSocialV1: suspend () -> Boolean,
) {
    @Volatile
    private var nexusReady = false

    /**
     * Migrates [session]'s account if it should be. [newProfile] is a profile the caller is about to
     * write: it becomes the v1 profile, so an account with no profile yet starts in `social/v1`.
     */
    suspend fun migrate(session: Session, newProfile: ProfileDto? = null): MigrationOutcome {
        val owner = session.identity.pubky
        return epochs.migrating(owner) { migrateHeld(session, owner, newProfile) }
    }

    private suspend fun migrateHeld(session: Session, owner: String, newProfile: ProfileDto?): MigrationOutcome {
        refusal(session, owner)?.let { return it }
        val plan = plan(owner, newProfile) ?: return MigrationOutcome.NoProfile
        val present = existingV1(owner, session.sessionSecret)
        for (stale in present - plan.writes.keys - V1.profile(owner)) {
            pubky.deleteWithSession(stale, session.sessionSecret).getOrThrow()
        }
        for ((path, body) in plan.writes) {
            if (path !in present) pubky.putWithSession(path, body, session.sessionSecret).getOrThrow()
        }
        pubky.putWithSession(V1.profile(owner), plan.profile, session.sessionSecret).getOrThrow()
        epochs.markV1(owner)
        Log.d(TAG, "migrated ${plan.writes.size} records and the profile")
        return MigrationOutcome.Migrated(plan.writes.size)
    }

    /** Why [owner] is not migrated now, or null when it should be. */
    private suspend fun refusal(session: Session, owner: String): MigrationOutcome? {
        // First, being free: the CLI and MCP sessions never hold these, and load a session per command.
        if (!session.canWriteTo(V1.profile(owner)) || !session.canWriteTo(V1.mute(owner, owner))) {
            return MigrationOutcome.MissingScope
        }
        if (hasV1Profile(owner)) {
            epochs.markV1(owner)
            return MigrationOutcome.AlreadyV1
        }
        if (!nexusReady) nexusReady = servesSocialV1()
        if (!nexusReady) return MigrationOutcome.NexusNotReady
        val found = footprint.classify(owner)
        return if (found == PubkyAppFootprint.LoopkyOnly || found == PubkyAppFootprint.Empty) {
            null
        } else {
            MigrationOutcome.NotLoopkys(found)
        }
    }

    private suspend fun hasV1Profile(owner: String): Boolean = pubky.get(V1.profile(owner)).fold(
        onSuccess = { true },
        onFailure = { if (it.isNotFound()) false else throw it },
    )

    private class Plan(val writes: Map<String, String>, val profile: String)

    /** Every v1 write for [owner]'s v0 records, or null when there is no named profile to flip with. */
    private suspend fun plan(owner: String, newProfile: ProfileDto?): Plan? {
        val profile = newProfile ?: readOrNull(V0.profile(owner), ProfileDto.serializer())
        val name = profile?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val writes = linkedMapOf<String, String>()
        val records = listWhole(layoutRoot(V0, owner)).filterNot { it.endsWith("/") }
        for (path in records) {
            when {
                path.startsWith(V0.postsRoot(owner)) -> {
                    val id = path.removePrefix(V0.postsRoot(owner))
                    writes[V1.postVersion(owner, id)] = encode(SocialPostDto.serializer(), post(read(path, PostDto.serializer())))
                }
                path.startsWith(V0.followsRoot(owner)) -> {
                    val followee = requireNotNull(V0.followeeOf(path)) { "unexpected follow path" }
                    writes[V1.follow(owner, followee)] = encode(FollowDto.serializer(), read(path, FollowDto.serializer()))
                }
                path.startsWith(V0.mutesRoot(owner)) -> {
                    val mutee = requireNotNull(V0.muteeOf(path)) { "unexpected mute path" }
                    writes[V1.mute(owner, mutee)] = encode(MuteDto.serializer(), read(path, MuteDto.serializer()))
                }
                path.startsWith(V0.tagsRoot(owner)) -> {
                    val tag = read(path, TagDto.serializer())
                    val moved =
                        TagDto(uri = rewrite(tag.uri), label = foldLabel(tag.label), created_at = tag.created_at)
                    val id = pubky.createTagId(moved.uri, moved.label).getOrThrow()
                    writes[V1.tag(owner, id)] = encode(TagDto.serializer(), moved)
                }
            }
        }
        val v1Profile = SocialProfileDto(
            name = name,
            bio = profile.bio?.trim()?.ifEmpty { null },
            image = profile.image?.let(::rewrite),
        )
        return Plan(writes, encode(SocialProfileDto.serializer(), v1Profile))
    }

    /** Every record already under [owner]'s `social/v1` roots, the private mutes included. */
    private suspend fun existingV1(owner: String, sessionSecret: String): Set<String> {
        val public = listWhole(layoutRoot(V1, owner))
        val mutes = pubky.listAllEntriesWithSession(V1.mutesRoot(owner), sessionSecret).getOrElse {
            if (it.isNotFound()) emptyList() else throw it
        }
        return (public + mutes).filterNot { it.endsWith("/") }.toSet()
    }

    /**
     * Every entry under [prefix], or a throw. A partial listing here would skip copies and delete
     * good ones as stale, and the flip after it would make the loss permanent.
     */
    private suspend fun listWhole(prefix: String): List<String> = pubky.listAllEntries(prefix).getOrElse {
        if (it.isNotFound()) emptyList() else throw it
    }

    private suspend fun <T> read(path: String, serializer: KSerializer<T>): T =
        loopkyJson.decodeFromString(serializer, pubky.get(path).getOrThrow())

    private suspend fun <T> readOrNull(path: String, serializer: KSerializer<T>): T? = pubky.get(path).fold(
        onSuccess = { loopkyJson.decodeFromString(serializer, it) },
        onFailure = { if (it.isNotFound()) null else throw it },
    )

    private fun <T> encode(serializer: KSerializer<T>, value: T): String = loopkyJson.encodeToString(serializer, value)

    private fun post(post: PostDto) = SocialPostDto(
        content = post.content,
        kind = if (post.kind == PostKinds.SHORT) SocialPostKinds.NOTE else post.kind,
        parent = post.parent?.let(::rewrite),
        embed = post.embed?.uri?.let(::rewrite),
    )

    private companion object {
        const val TAG = "Loopky/SocialMigrator"
        val V0 = SocialLayout.Legacy
        val V1 = SocialLayout.SocialV1
    }
}

/** What [SocialMigrator.migrate] did. Only [Migrated] and [AlreadyV1] leave the account in `social/v1`. */
internal sealed interface MigrationOutcome {
    data object AlreadyV1 : MigrationOutcome
    data object NexusNotReady : MigrationOutcome

    /** A session from before #621, without `/pub/social/v1/` and `/priv/social/v1/`. */
    data object MissingScope : MigrationOutcome

    /** pubky.app's to migrate, or not readable enough to tell. */
    data class NotLoopkys(val footprint: PubkyAppFootprint) : MigrationOutcome

    /** No profile with a name, which v1 requires; the first profile Loopky writes migrates it. */
    data object NoProfile : MigrationOutcome
    data class Migrated(val records: Int) : MigrationOutcome
}

/** `PubkySocialUser`: `name` is required in v1. Loopky never writes `links` or `status`. */
@Serializable
internal data class SocialProfileDto(
    val name: String,
    val bio: String? = null,
    val image: String? = null,
)

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

private fun layoutRoot(layout: SocialLayout, owner: String) = layout.profile(owner).removeSuffix("profile.json")
