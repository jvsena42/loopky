package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.AccountStamp
import com.github.jvsena42.loopky.data.pubky.AppSettingsDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.SCHEMA_VERSION
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.StudySettingsDto
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.putWithSessionRetry
import com.github.jvsena42.loopky.data.pubky.toDomain
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.SettingsOrigin
import com.github.jvsena42.loopky.data.repository.SettingsRepository
import com.github.jvsena42.loopky.data.repository.StudySettingsSnapshot
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.domain.model.StudySettings
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.epochMillis
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * [SettingsRepository] over a single `settings.json` record on the user's own homeserver.
 *
 * Three things here are load-bearing rather than incidental:
 *
 * 1. **[update] is gated on having read the record.** The failure it prevents is quiet and
 *    destructive: read fails → flow holds defaults → user taps once in Settings → their real
 *    intervals are overwritten with 1/3/7, with nothing on screen saying anything happened.
 * 2. **A 404 counts as a successful read.** Every new user has no record. Treating "absent" as
 *    "unread" would leave Settings permanently disabled for everyone on their first run.
 * 3. **The record is written whole**, like a deck manifest, so [update] re-reads inside the lock
 *    rather than patching a copy the caller captured. Cheap while there is one section; the moment
 *    a second one exists, not doing it drops it.
 */
class SettingsRepositoryImpl(
    private val pubky: PubkyClient,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
    private val preferences: AppPreferences,
) : SettingsRepository {

    private val _studySettings = MutableStateFlow(StudySettingsSnapshot())
    override val studySettings: StateFlow<StudySettingsSnapshot> = _studySettings.asStateFlow()

    /** Serializes the read-modify-write, and makes [ensureLoaded] single-flight. */
    private val lock = Mutex()

    /** Guarded by [lock]. Study settings are one account's; the snapshot must not outlive it. */
    private val account = AccountStamp(session)

    /**
     * Drop a snapshot belonging to a previous account. Caller holds [lock].
     *
     * Back to defaults rather than straight to a re-read, because the origin *is* the gate: at
     * `Defaults` [update] refuses, which is exactly right until this account's own record has been
     * read. Leaving it at `Remote` was what let the previous account's intervals through the
     * check and into a second user's `settings.json`.
     */
    private fun resetOnAccountChangeLocked() {
        if (account.changed()) _studySettings.update { StudySettingsSnapshot() }
        account.mark()
    }

    override suspend fun ensureLoaded() {
        // Cheap pre-check outside the lock: after the first load this is every call, and grading a
        // card must not queue behind a mutex to learn that nothing needs doing. The account has to
        // be part of it — after a switch the snapshot is still `Remote`, but it is the previous
        // account's. Reading the stamp unlocked is only ever an optimisation: it can send us to
        // the lock we did not need, never past the one we did, because the check is made again
        // inside it.
        if (!account.changed() && _studySettings.value.origin == SettingsOrigin.Remote) return
        lock.withLock {
            resetOnAccountChangeLocked()
            if (_studySettings.value.origin == SettingsOrigin.Remote) return
            // The mirror first, so an offline session schedules with the user's own intervals
            // rather than silently reverting to the defaults.
            if (_studySettings.value.origin == SettingsOrigin.Defaults) restoreMirror()
            readLocked()
        }
    }

    override suspend fun restoreCachedSettings() {
        lock.withLock {
            resetOnAccountChangeLocked()
            // `Defaults` only: anything else is this account's real record, or the mirror already
            // in place, and both are better than what this would put there.
            if (_studySettings.value.origin != SettingsOrigin.Defaults) return
            restoreMirror()
        }
    }

    override suspend fun update(settings: StudySettings): Result<Unit> = runSuspendCatching {
        val sanitized = settings.sanitized()
        lock.withLock {
            // Before the gate, not after: a snapshot left by the previous account would otherwise
            // satisfy it, and this write would put their intervals in this user's record.
            resetOnAccountChangeLocked()
            // Not merely "we have not loaded yet" — see the class header. Refusing is the only
            // safe answer, because the alternative writes a guess over the real thing.
            check(_studySettings.value.origin == SettingsOrigin.Remote) {
                "Study settings have not been read from the homeserver yet — refusing to overwrite them"
            }
            val owner = session.current()?.identity?.pubky
            requireNotNull(owner) { "Not signed in" }

            // Re-read inside the lock, and patch at the JSON level rather than round-tripping
            // through the DTO. The record is written whole, so re-encoding a decoded copy would
            // silently drop any section a newer client added — `ignoreUnknownKeys` throws those
            // away on the way in, and the write would then throw them away for good.
            val body = patchLocked(owner, sanitized)
            pubky.putWithSessionRetry(
                url = PubkyPaths.settings(owner),
                content = body,
                session = session,
                revalidator = revalidator,
            ).getOrThrow()

            _studySettings.update { StudySettingsSnapshot(sanitized, SettingsOrigin.Remote) }
            cacheMirror(sanitized.toDto())
        }
    }

    /**
     * Caller holds [lock]. The record with its `study` section replaced, as JSON.
     *
     * Everything else in the object is carried across untouched, including keys this build has
     * never heard of — the whole point of patching the [JsonObject] instead of the DTO.
     */
    private suspend fun patchLocked(owner: String, settings: StudySettings): String {
        val existing = pubky.get(PubkyPaths.settings(owner))
            .getOrNull()
            ?.let { runCatching { loopkyJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            .orEmpty()
        val patched = buildJsonObject {
            existing.forEach { (key, value) -> put(key, value) }
            put("schema_version", JsonPrimitive(SCHEMA_VERSION))
            put("study", loopkyJson.encodeToJsonElement(settings.toDto()))
            put("updated_at", JsonPrimitive(epochMillis()))
        }
        return loopkyJson.encodeToString(JsonObject.serializer(), patched)
    }

    /** Caller holds [lock]. Never throws — a settings read must not fail a study session. */
    private suspend fun readLocked() {
        val owner = session.current()?.identity?.pubky
        if (owner == null) {
            Log.d(TAG, "ensureLoaded: not signed in — keeping ${_studySettings.value.origin}")
            return
        }
        fetchLocked(owner)
            .onSuccess { dto ->
                _studySettings.update { StudySettingsSnapshot(dto.study.toDomain(), SettingsOrigin.Remote) }
                cacheMirror(dto.study)
                Log.d(TAG, "ensureLoaded: settings=${dto.study}")
            }
            .onFailure { err ->
                // Deliberately leaves the origin below Remote, which is what keeps `update` shut.
                Log.e(TAG, "ensureLoaded: FAILED — ${err.message}", err)
            }
    }

    /**
     * The record, or defaults if there is none yet.
     *
     * A 404 is success: an account that has never opened Settings has no record, and that is the
     * normal state, not an error. Anything else is a real failure and is propagated.
     */
    private suspend fun fetchLocked(owner: String): Result<AppSettingsDto> =
        pubky.get(PubkyPaths.settings(owner))
            .fold(
                onSuccess = { body ->
                    runCatching { loopkyJson.decodeFromString<AppSettingsDto>(body) }
                },
                onFailure = { err ->
                    if (err.isNotFound()) Result.success(AppSettingsDto()) else Result.failure(err)
                },
            )

    private suspend fun restoreMirror() {
        val owner = session.current()?.identity?.pubky ?: return
        val json = runSuspendCatching { preferences.cachedStudySettings.first() }.getOrNull()
        if (json.isNullOrBlank()) return
        val cached = runCatching { loopkyJson.decodeFromString<MirroredStudySettings>(json) }.getOrNull()
            ?: return
        // One device-wide key, so it may hold someone else's intervals. Ignoring theirs costs an
        // offline session its defaults; using them would schedule this user's cards on a stranger's
        // numbers, and the mirror exists precisely to be trusted before the record answers.
        if (cached.ownerPubky != owner) return
        _studySettings.update { StudySettingsSnapshot(cached.study.toDomain(), SettingsOrigin.Cached) }
        Log.d(TAG, "ensureLoaded: using the device's cached copy until the record answers")
    }

    /** Best-effort: a cache that fails to save costs an offline session its intervals, nothing more. */
    private suspend fun cacheMirror(dto: StudySettingsDto) {
        val owner = session.current()?.identity?.pubky ?: return
        val mirrored = loopkyJson.encodeToString(MirroredStudySettings(owner, dto))
        runSuspendCatching { preferences.setCachedStudySettings(mirrored) }
            .onFailure { Log.w(TAG, "cacheMirror: FAILED — ${it.message}") }
    }

    private companion object {
        private const val TAG = "Loopky/SettingsRepo"
    }
}

/**
 * The device's copy of one account's study settings.
 *
 * The pubky rides along because the mirror is a single device-wide key while the settings in it
 * belong to whoever was signed in when they were cached. A mirror written by an older build has no
 * owner and no longer decodes into this shape, so it is ignored — one offline session on the
 * defaults, once, which is what the mirror is a best-effort improvement on anyway.
 */
@Serializable
private data class MirroredStudySettings(
    val ownerPubky: String,
    val study: StudySettingsDto,
)
