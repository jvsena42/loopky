package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.data.repository.CachedDecks
import com.github.jvsena42.loopky.domain.model.AppTheme
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.DailyStudyProgress
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The desktop JVM's eight stores, all over [JsonFileStore].
 *
 * Each is the same shape as its Android counterpart and shares its serialisation helpers from
 * `commonMain`, so a value written by one is readable by the other — which is what makes it
 * possible to say the CLI and the app are the same client rather than two that agree by luck.
 *
 * The `Flow` fields mirror into a `StateFlow` for the reason `AndroidAppPreferences` does: only
 * these classes write the file, so the flow is the cheaper source of truth and a collector gets
 * the current value without touching disk.
 */
internal class FileSecureSessionStore(private val store: JsonFileStore) : SecureSessionStore {

    override val location: String = store.file.toString()

    override suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        store.set(SESSION_STORAGE_KEY, sessionStoreJson.encodeToString(StoredSession.fromDomain(session)))
    }

    override suspend fun load(): Session? = withContext(Dispatchers.IO) {
        val json = store.string(SESSION_STORAGE_KEY) ?: return@withContext null
        runCatching { sessionStoreJson.decodeFromString<StoredSession>(json).toDomain() }.getOrNull()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { store.remove(SESSION_STORAGE_KEY) }
}

internal class FileAppPreferences(private val store: JsonFileStore) : AppPreferences {

    private val _shareOnPubky = MutableStateFlow(
        store.string(KEY_SHARE_ON_PUBKY)?.toBooleanStrictOrNull() ?: DEFAULT_SHARE_ON_PUBKY,
    )
    override val shareOnPubky: Flow<Boolean> = _shareOnPubky.asStateFlow()

    override suspend fun setShareOnPubky(enabled: Boolean) {
        withContext(Dispatchers.IO) { store.set(KEY_SHARE_ON_PUBKY, enabled.toString()) }
        _shareOnPubky.update { enabled }
    }

    private val _pubkyEnvironment = MutableStateFlow(
        store.string(KEY_PUBKY_ENVIRONMENT) ?: DEFAULT_PUBKY_ENVIRONMENT,
    )
    override val pubkyEnvironment: Flow<String> = _pubkyEnvironment.asStateFlow()

    override suspend fun setPubkyEnvironment(name: String) {
        withContext(Dispatchers.IO) { store.set(KEY_PUBKY_ENVIRONMENT, name) }
        _pubkyEnvironment.update { name }
    }

    private val _cachedStudySettings = MutableStateFlow(
        store.string(KEY_CACHED_STUDY_SETTINGS) ?: DEFAULT_CACHED_STUDY_SETTINGS,
    )
    override val cachedStudySettings: Flow<String> = _cachedStudySettings.asStateFlow()

    override suspend fun setCachedStudySettings(json: String) {
        withContext(Dispatchers.IO) { store.set(KEY_CACHED_STUDY_SETTINGS, json) }
        _cachedStudySettings.update { json }
    }

    private val _themeMode = MutableStateFlow(
        store.string(KEY_THEME_MODE)?.let(AppTheme::fromNameOrSystem) ?: DEFAULT_THEME_MODE,
    )
    override val themeMode: StateFlow<AppTheme> = _themeMode.asStateFlow()

    override suspend fun setThemeMode(theme: AppTheme) {
        withContext(Dispatchers.IO) { store.set(KEY_THEME_MODE, theme.name) }
        _themeMode.update { theme }
    }

    private val _nameNudgeDismissed = MutableStateFlow(
        store.string(KEY_NAME_NUDGE_DISMISSED)?.toBooleanStrictOrNull() ?: DEFAULT_NAME_NUDGE_DISMISSED,
    )
    override val nameNudgeDismissed: Flow<Boolean> = _nameNudgeDismissed.asStateFlow()

    override suspend fun setNameNudgeDismissed(dismissed: Boolean) {
        withContext(Dispatchers.IO) { store.set(KEY_NAME_NUDGE_DISMISSED, dismissed.toString()) }
        _nameNudgeDismissed.update { dismissed }
    }

    private val _avatarNudgeDismissed = MutableStateFlow(
        store.string(KEY_AVATAR_NUDGE_DISMISSED)?.toBooleanStrictOrNull()
            ?: DEFAULT_AVATAR_NUDGE_DISMISSED,
    )
    override val avatarNudgeDismissed: Flow<Boolean> = _avatarNudgeDismissed.asStateFlow()

    override suspend fun setAvatarNudgeDismissed(dismissed: Boolean) {
        withContext(Dispatchers.IO) { store.set(KEY_AVATAR_NUDGE_DISMISSED, dismissed.toString()) }
        _avatarNudgeDismissed.update { dismissed }
    }
}

internal class FilePendingReviewStore(private val store: JsonFileStore) : PendingReviewStore {

    override suspend fun load(): List<PendingReview> = withContext(Dispatchers.IO) {
        decodePendingReviews(store.string(KEY_PENDING_REVIEWS))
    }

    override suspend fun save(entries: List<PendingReview>) = withContext(Dispatchers.IO) {
        // [JsonFileStore] writes through to disk on every call, so there is no deferred-write
        // window here to close — the distinction Android draws between commit() and apply() has
        // no counterpart. This is still the journal of reviews that have not reached the
        // homeserver, which is why that write is atomic.
        if (entries.isEmpty()) {
            store.remove(KEY_PENDING_REVIEWS)
        } else {
            store.set(KEY_PENDING_REVIEWS, encodePendingReviews(entries))
        }
    }
}

internal class FileStudyProgressStore(private val store: JsonFileStore) : StudyProgressStore {

    override suspend fun load(ownerPubky: String): DailyStudyProgress? = withContext(Dispatchers.IO) {
        decodeStudyProgress(store.string(KEY_STUDY_PROGRESS), ownerPubky)
    }

    override suspend fun save(ownerPubky: String, progress: DailyStudyProgress) {
        withContext(Dispatchers.IO) {
            store.set(KEY_STUDY_PROGRESS, encodeStudyProgress(ownerPubky, progress))
        }
    }
}

internal class FileDeckCacheStore(private val store: JsonFileStore) : DeckCacheStore {

    override suspend fun load(ownerPubky: String): CachedDecks? = withContext(Dispatchers.IO) {
        decodeDeckCache(store.string(KEY_DECK_CACHE), ownerPubky)
    }

    override suspend fun save(ownerPubky: String, decks: CachedDecks) {
        withContext(Dispatchers.IO) { store.set(KEY_DECK_CACHE, encodeDeckCache(ownerPubky, decks)) }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { store.remove(KEY_DECK_CACHE) }
}

internal class FileLocalKeyStore(private val store: JsonFileStore) : LocalKeyStore {

    private val _custody = MutableStateFlow(readKey()?.toCustody() ?: KeyCustody.External)
    override val custody: Flow<KeyCustody> = _custody.asStateFlow()

    override suspend fun save(key: LocalKey) {
        withContext(Dispatchers.IO) {
            store.set(LOCAL_KEY_STORAGE_KEY, sessionStoreJson.encodeToString(key))
        }
        _custody.update { key.toCustody() }
    }

    override suspend fun current(): LocalKey? = withContext(Dispatchers.IO) { readKey() }

    override suspend fun markBackedUp(method: BackupMethod) {
        val updated = withContext(Dispatchers.IO) {
            val existing = readKey() ?: return@withContext null
            if (method in existing.backedUpBy) return@withContext existing
            existing.copy(backedUpBy = existing.backedUpBy + method).also {
                store.set(LOCAL_KEY_STORAGE_KEY, sessionStoreJson.encodeToString(it))
            }
        } ?: return
        _custody.update { updated.toCustody() }
    }

    override suspend fun markRegistered() {
        val updated = withContext(Dispatchers.IO) {
            val existing = readKey() ?: return@withContext null
            if (existing.registered) return@withContext existing
            existing.copy(registered = true).also {
                store.set(LOCAL_KEY_STORAGE_KEY, sessionStoreJson.encodeToString(it))
            }
        } ?: return
        _custody.update { updated.toCustody() }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { store.remove(LOCAL_KEY_STORAGE_KEY) }
        _custody.update { KeyCustody.External }
    }

    private fun readKey(): LocalKey? {
        val json = store.string(LOCAL_KEY_STORAGE_KEY) ?: return null
        return runCatching { sessionStoreJson.decodeFromString<LocalKey>(json) }.getOrNull()
    }
}

internal class FileSignupTokenStore(private val store: JsonFileStore) : SignupTokenStore {

    private val _pending = MutableStateFlow(readPending())
    override val pending: Flow<PendingSignup?> = _pending.asStateFlow()

    override suspend fun save(pending: PendingSignup) {
        withContext(Dispatchers.IO) {
            store.set(SIGNUP_TOKEN_STORAGE_KEY, sessionStoreJson.encodeToString(pending))
        }
        _pending.update { pending }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { store.remove(SIGNUP_TOKEN_STORAGE_KEY) }
        _pending.update { null }
    }

    private fun readPending(): PendingSignup? {
        val json = store.string(SIGNUP_TOKEN_STORAGE_KEY) ?: return null
        return runCatching { sessionStoreJson.decodeFromString<PendingSignup>(json) }.getOrNull()
    }
}

internal class FileUnsplashKeyStore(private val store: JsonFileStore) : UnsplashKeyStore {

    private val _key = MutableStateFlow(store.string(UNSPLASH_KEY_STORAGE_KEY).orEmpty())
    override val key: Flow<String> = _key.asStateFlow()

    override suspend fun save(key: String) {
        withContext(Dispatchers.IO) { store.set(UNSPLASH_KEY_STORAGE_KEY, key) }
        _key.update { key }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { store.remove(UNSPLASH_KEY_STORAGE_KEY) }
        _key.update { "" }
    }
}
