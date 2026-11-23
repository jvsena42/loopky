package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.LoopkyPubkyAppRecords
import com.github.jvsena42.loopky.data.pubky.ProfileDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.SocialEpochs
import com.github.jvsena42.loopky.data.pubky.SocialLayout
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.mapConcurrently
import com.github.jvsena42.loopky.domain.model.PubkyAppFootprint
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.Volatile

/**
 * Moves an account Loopky alone wrote to from `/pub/pubky.app/` to `/pub/social/v1/` (#616).
 *
 * pubky.app migrates the accounts that use it; Nexus will not index a `v0` user's `social/v1`
 * records, so nobody else moves an account only Loopky wrote to. This copies what
 * `pubky-social-migrator` 1.0.0-rc.2 would, for the records Loopky writes: a post `posts/{id}`
 * becomes `posts/{id}/{id}.json`, a `pubky.app` reference takes its v1 spelling, a tag's id is
 * derived again from its rewritten target, and a mute moves under `/priv/`. A record the v1 reader
 * would refuse is skipped, as the reference skips it. One deliberate difference: a follow's
 * `created_at`, which Loopky wrote to v0 in milliseconds, moves in microseconds.
 *
 * Things not to undo:
 * - It runs only once production Nexus reads `social/v1` ([servesSocialV1]).
 * - Only a [PubkyAppFootprint.LoopkyOnly] or [PubkyAppFootprint.Empty] account is moved. The tree
 *   is read and planned first; the copy and the flip run inside [SocialEpochs.exclusive], so no
 *   write of this process lands in between, and the tree is read again before each to catch one
 *   that landed before the hold or came from another device.
 * - `profile.json` goes last, because writing it is the flip: Nexus ignores every `social/v1`
 *   record written before it, so an interrupted run changes nothing and the next one finishes it.
 *   Until then the classification proved every record in `social/v1` is Loopky's, so a copy whose
 *   source has gone since an earlier run is deleted. The v0 tree is left in place.
 * - `/priv/social/v1/_migrated.json` is written after the flip, in the reference's shape: it is
 *   what tells pubky.app's migrator the account is done, and without it that migrator would copy
 *   back every record deleted from `social/v1` since, from the `pubky.app` tree left in place.
 */
internal class SocialMigrator(
    private val pubky: PubkyClient,
    private val footprint: PubkyAppFootprintReader,
    private val epochs: SocialEpochs,
    private val servesSocialV1: suspend () -> Boolean,
    private val now: () -> Long = ::epochMillis,
) {
    @Volatile
    private var nexusReady = false

    /** Answers that hold for the life of the process unless a new profile is written. */
    private val settled = mutableMapOf<String, MigrationOutcome>()
    private val flagChecked = mutableSetOf<String>()
    private val settledLock = Mutex()

    /**
     * Migrates [session]'s account if it should be. [newProfile] is a profile the caller is about to
     * write: it becomes the v1 profile, so an account with no profile yet starts in `social/v1`.
     */
    suspend fun migrate(session: Session, newProfile: ProfileDto? = null): MigrationOutcome {
        val owner = session.identity.pubky
        when (val first = refusal(session, owner, newProfile)) {
            null -> Unit
            // In the hold, like the flip: Delete account removes the flag inside it.
            MigrationOutcome.AlreadyV1 -> return epochs.exclusive(owner) {
                if (epochs.isRetired(owner)) MigrationOutcome.Erased else first.also { completeFlag(session, owner) }
            }
            else -> return first
        }
        // Read and planned outside the hold, so writes wait only for the copy and the flip.
        val plan = when (val planned = plan(owner, newProfile)) {
            is Planned.Refused -> return settle(owner, planned.outcome, newProfile)
            is Planned.Ready -> planned.plan
        }
        return epochs.exclusive(owner) { copyAndFlip(session, owner, newProfile, plan) }
    }

    /** Forgets what this process concluded about [owner], after a profile write or an erase. */
    suspend fun forget(owner: String) = settledLock.withLock {
        settled.remove(owner)
        flagChecked.remove(owner)
    }

    private suspend fun copyAndFlip(session: Session, owner: String, newProfile: ProfileDto?, first: Plan): MigrationOutcome {
        var plan = first
        repeat(MAX_PASSES) {
            ended(owner)?.let { return it }
            // A write that landed before the hold, or another device's, makes the plan stale.
            if (snapshot(owner, newProfile) != plan.snapshot) {
                plan = when (val planned = plan(owner, newProfile)) {
                    is Planned.Refused -> return settle(owner, planned.outcome, newProfile)
                    is Planned.Ready -> planned.plan
                }
            }
            val present = existingV1(owner, session.sessionSecret)
            // Again, closer to the deletes: past a flip by another device they would undo its writes.
            ended(owner)?.let { return it }
            // A post goes after its labels and is deleted before them: the next run classifies
            // `social/v1` too, and a Loopky post without its labels there reads as another app's.
            val (staleTags, staleRest) = (present - plan.writes.keys - V1.profile(owner)).partition(::isTag)
            for (pass in listOf(staleRest, staleTags)) {
                pass.mapConcurrently { pubky.deleteWithSession(it, session.sessionSecret).getOrThrow() }
            }
            val (tags, rest) = plan.writes.filterKeys { it !in present }.toList().partition { isTag(it.first) }
            for (pass in listOf(tags, rest)) {
                pass.mapConcurrently { (path, body) -> pubky.putWithSession(path, body, session.sessionSecret).getOrThrow() }
            }
            if (snapshot(owner, newProfile) != plan.snapshot) return@repeat
            ended(owner)?.let { return it }
            pubky.putWithSession(V1.profile(owner), plan.profile, session.sessionSecret).getOrThrow()
            epochs.markV1(owner)
            writeFlag(session, owner, plan.records)
            Log.d(TAG, "migrated ${plan.writes.size} records and the profile, skipped ${plan.records.skipped.size}")
            return MigrationOutcome.Migrated(plan.writes.size)
        }
        error("pubky.app kept changing during the migration")
    }

    /** The account was deleted, or another device flipped it, since the run began. */
    private suspend fun ended(owner: String): MigrationOutcome? = when {
        epochs.isRetired(owner) -> MigrationOutcome.Erased
        flipped(owner) -> MigrationOutcome.AlreadyV1
        else -> null
    }

    /** Why [owner] is not migrated now, or null when it should be. */
    private suspend fun refusal(session: Session, owner: String, newProfile: ProfileDto?): MigrationOutcome? {
        // First, being free: the CLI and MCP sessions never hold these, and load a session per command.
        if (!session.canWriteTo(V1.profile(owner)) || !session.canWriteTo(V1.mute(owner, owner))) {
            return MigrationOutcome.MissingScope
        }
        if (flipped(owner)) return MigrationOutcome.AlreadyV1
        if (!nexusReady) nexusReady = servesSocialV1()
        if (!nexusReady) return MigrationOutcome.NexusNotReady
        if (newProfile == null) settledLock.withLock { settled[owner] }?.let { return it }
        val found = footprint.classify(owner)
        return when (found) {
            PubkyAppFootprint.LoopkyOnly, PubkyAppFootprint.Empty -> null
            // Unknown is a read that failed, so it is asked again next time.
            PubkyAppFootprint.Unknown -> MigrationOutcome.NotLoopkys(found)
            else -> settle(owner, MigrationOutcome.NotLoopkys(found), newProfile)
        }
    }

    private suspend fun settle(owner: String, outcome: MigrationOutcome, newProfile: ProfileDto?): MigrationOutcome {
        if (newProfile == null) settledLock.withLock { settled[owner] = outcome }
        return outcome
    }

    private suspend fun flipped(owner: String): Boolean {
        val exists = pubky.get(V1.profile(owner)).fold(
            onSuccess = { true },
            onFailure = { if (it.isNotFound()) false else throw it },
        )
        if (exists) epochs.markV1(owner)
        return exists
    }

    /**
     * What [owner]'s v0 tree becomes in v1, the profile aside.
     *
     * @property writes every v1 record to hold, by path.
     * @property migrated the owner-relative v0 paths that moved, for the flag.
     * @property skipped the owner-relative v0 paths the v1 reader would refuse.
     * @property sources every v0 record read.
     */
    private class Records(
        val writes: Map<String, String>,
        val migrated: List<String>,
        val skipped: List<String>,
        val sources: Set<String>,
    )

    /** [snapshot] is what the plan was made from, compared again before the copy and the flip. */
    private class Plan(val records: Records, val profile: String, val snapshot: Snapshot) {
        val writes get() = records.writes
    }

    /** The v0 paths, and the profile's body: an edit to it from another device changes no path. */
    private data class Snapshot(val paths: Set<String>, val profile: String?)

    private sealed interface Planned {
        class Ready(val plan: Plan) : Planned
        class Refused(val outcome: MigrationOutcome) : Planned
    }

    private suspend fun snapshot(owner: String, newProfile: ProfileDto?): Snapshot {
        val profile = if (newProfile != null) {
            null
        } else {
            pubky.get(V0.profile(owner)).fold(
                onSuccess = { it },
                onFailure = { if (it.isNotFound()) null else throw it },
            )
        }
        return Snapshot(pubky.v0Records(owner), profile)
    }

    private suspend fun plan(owner: String, newProfile: ProfileDto?): Planned {
        val snapshot = snapshot(owner, newProfile)
        // Asked again of a re-plan inside the hold: another client may have added links or a status.
        if (snapshot.profile != null && !LoopkyPubkyAppRecords.isLoopkyProfile(snapshot.profile)) {
            return Planned.Refused(MigrationOutcome.NotLoopkys(PubkyAppFootprint.Direct))
        }
        val profile = newProfile
            ?: snapshot.profile?.let { runCatching { loopkyJson.decodeFromString(ProfileDto.serializer(), it) }.getOrNull() }
            ?: return Planned.Refused(MigrationOutcome.NoProfile)
        val v1Profile = socialProfile(profile) ?: return Planned.Refused(MigrationOutcome.InvalidProfile)
        val records = records(owner, snapshot.paths)
        return Planned.Ready(Plan(records, encoded(SocialProfileDto.serializer(), v1Profile), snapshot))
    }

    private suspend fun records(owner: String, sources: Set<String>): Records {
        val writes = linkedMapOf<String, String>()
        val migrated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (path in sources.sorted()) {
            val moved = if (path == V0.profile(owner)) null else move(owner, path)
            when {
                path == V0.profile(owner) -> migrated += relative(owner, path)
                moved == null -> skipped += relative(owner, path)
                else -> {
                    writes[moved.first] = moved.second
                    migrated += relative(owner, path)
                }
            }
        }
        return Records(writes, migrated, skipped, sources)
    }

    /** The v1 path and body for one v0 record, or null when the v1 reader would refuse it. */
    private suspend fun move(owner: String, path: String): Pair<String, String>? {
        val kind = V0_ROOTS.firstOrNull { path.startsWith(it(owner)) }
            // The classification admits nothing else, so another app wrote it since: stop, copy nothing.
            ?: error("unexpected record in pubky.app: ${relative(owner, path)}")
        val key = path.removePrefix(kind(owner))
        val json = pubky.get(path).getOrThrow()
        return when (kind) {
            V0::postsRoot -> movedPost(owner, key, json)
            V0::followsRoot -> movedFollow(owner, key, json)
            V0::mutesRoot -> movedMute(owner, key, json)
            else -> movedTag(owner, key, json) { uri, label -> pubky.createTagId(uri, label).getOrThrow() }
        }
    }

    /** Every record already under [owner]'s `social/v1` roots, the private mutes included. */
    private suspend fun existingV1(owner: String, sessionSecret: String): Set<String> {
        val public = pubky.listWhole(layoutRoot(V1, owner))
        val mutes = pubky.listWhole(V1.mutesRoot(owner), sessionSecret)
        return (public + mutes).filterNot { it.endsWith("/") }.toSet()
    }

    private suspend fun writeFlag(session: Session, owner: String, records: Records): Boolean {
        val flag = MigrationFlagDto(
            migrated_at = now() * MICROS_PER_MILLI,
            transform_rev = TRANSFORM_REV,
            skipped = if (records.skipped.isEmpty()) emptyMap() else mapOf(SKIP_INVALID to records.skipped.sorted()),
            migrated = records.migrated.sorted(),
        )
        // After the flip, so a failure leaves a migrated account; retried here, and by completeFlag
        // on the next run, since pubky.app may be opened first.
        val body = encoded(MigrationFlagDto.serializer(), flag)
        repeat(FLAG_ATTEMPTS) {
            pubky.putWithSession(flagPath(owner), body, session.sessionSecret)
                .onSuccess { return true }
                .onFailure { Log.w(TAG, "flag write FAILED — ${it.message}") }
        }
        return false
    }

    /**
     * Writes the flag a run of Loopky's that flipped [owner] could not. Loopky copies everything
     * before the flip, so such an account moved whole, whatever it deleted since. pubky.app's
     * migrator flips before it copies the mutes, and a run of its that stopped there must stay
     * unflagged, or it never finishes: its profile is told apart by `links` and `status`, which it
     * always writes and Loopky never does.
     */
    private suspend fun completeFlag(session: Session, owner: String) {
        if (settledLock.withLock { owner in flagChecked }) return
        val missing = pubky.getWithSession(flagPath(owner), session.sessionSecret)
            .fold(onSuccess = { false }, onFailure = { if (it.isNotFound()) true else throw it })
        val found = if (missing && pubky.flippedByLoopky(owner)) footprint.classify(owner) else null
        val done = when (found) {
            null, PubkyAppFootprint.Direct -> true
            PubkyAppFootprint.LoopkyOnly, PubkyAppFootprint.Empty -> writeFlag(session, owner, records(owner, pubky.v0Records(owner)))
            PubkyAppFootprint.Unknown -> false
        }
        if (done) settledLock.withLock { flagChecked += owner }
    }

    private companion object {
        const val TAG = "Loopky/SocialMigrator"
        val V0 = SocialLayout.Legacy
        val V1 = SocialLayout.SocialV1
        val V0_ROOTS = listOf(V0::postsRoot, V0::followsRoot, V0::mutesRoot, V0::tagsRoot)

        /** A second device's write during one pass is caught by the next; a steady stream is not. */
        const val MAX_PASSES = 3

        /** `transformRev` of pubky-social-migrator 1.0.0-rc.2: a flag at or above it is a finished run. */
        const val TRANSFORM_REV = 4
        const val SKIP_INVALID = "invalid"
        const val FLAG_ATTEMPTS = 3

        fun flagPath(owner: String) = "pubky://$owner/priv/social/v1/_migrated.json"
        fun relative(owner: String, path: String) = path.removePrefix("pubky://$owner/")
        fun isTag(path: String) = "/pub/social/v1/tags/" in path
    }
}

/** What [SocialMigrator.migrate] did. Only [Migrated] and [AlreadyV1] leave the account in `social/v1`. */
internal sealed interface MigrationOutcome {
    data object AlreadyV1 : MigrationOutcome

    /** The account was deleted while the run waited; nothing was flipped. */
    data object Erased : MigrationOutcome
    data object NexusNotReady : MigrationOutcome

    /** A session from before #621, without `/pub/social/v1/` and `/priv/social/v1/`. */
    data object MissingScope : MigrationOutcome

    /** pubky.app's to migrate, or not readable enough to tell. */
    data class NotLoopkys(val footprint: PubkyAppFootprint) : MigrationOutcome

    /** No profile, which v1 requires; the first profile Loopky writes migrates it. */
    data object NoProfile : MigrationOutcome

    /** A profile v1 refuses (a name outside 3–50 characters, a bio over 160); the next valid edit migrates it. */
    data object InvalidProfile : MigrationOutcome
    data class Migrated(val records: Int) : MigrationOutcome
}

/** `PubkySocialUser`: `name` is required in v1. Loopky never writes `links` or `status`. */
@Serializable
internal data class SocialProfileDto(
    val name: String,
    val bio: String? = null,
    val image: String? = null,
)

/** The reference migrator's `_migrated.json`; wire names are the spec's. */
@Suppress("ConstructorParameterNaming")
@Serializable
internal data class MigrationFlagDto(
    val migrated_at: Long,
    val transform_rev: Int,
    val skipped: Map<String, List<String>>,
    val migrated: List<String>,
)

/**
 * Every entry under [prefix], or a throw. A partial listing would skip copies and delete good ones
 * as stale, and the flip after it would make the loss permanent.
 */
private suspend fun PubkyClient.listWhole(prefix: String, sessionSecret: String? = null): List<String> =
    listEveryEntry(prefix, sessionSecret).getOrElse { if (it.isNotFound()) emptyList() else throw it }

/** Whether [owner]'s v1 profile is one Loopky wrote: pubky.app always spells `links` and `status`. */
private suspend fun PubkyClient.flippedByLoopky(owner: String): Boolean {
    val profile = get(SocialLayout.SocialV1.profile(owner)).getOrThrow()
    val members = runCatching { loopkyJson.parseToJsonElement(profile).jsonObject.keys }.getOrNull() ?: return false
    return "links" !in members && "status" !in members
}

private suspend fun PubkyClient.v0Records(owner: String): Set<String> =
    listWhole(layoutRoot(SocialLayout.Legacy, owner)).filterNot { it.endsWith("/") }.toSet()

private fun layoutRoot(layout: SocialLayout, owner: String) = layout.profile(owner).removeSuffix("profile.json")
