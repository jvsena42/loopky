// The stores of a per-user graph (#214): the contracts of `FileStores.kt`, backed by fields. The
// point is isolation, not persistence — a file under a config home is readable by every graph in
// the process, a field only through the graph that owns it, and it goes with the graph.

package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.data.repository.CachedDecks
import com.github.jvsena42.loopky.domain.model.AppTheme
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.DailyStudyProgress
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

class InMemorySecureSessionStore : SecureSessionStore {

    override val location: String = "memory"

    private val session = MutableStateFlow<Session?>(null)

    override suspend fun save(session: Session) = this.session.update { session }

    override suspend fun load(): Session? = session.value

    override suspend fun clear() = session.update { null }
}

class InMemoryAppPreferences : AppPreferences {

    private val _shareOnPubky = MutableStateFlow(DEFAULT_SHARE_ON_PUBKY)
    override val shareOnPubky: Flow<Boolean> = _shareOnPubky.asStateFlow()
    override suspend fun setShareOnPubky(enabled: Boolean) = _shareOnPubky.update { enabled }

    private val _pubkyEnvironment = MutableStateFlow(DEFAULT_PUBKY_ENVIRONMENT)
    override val pubkyEnvironment: Flow<String> = _pubkyEnvironment.asStateFlow()
    override suspend fun setPubkyEnvironment(name: String) = _pubkyEnvironment.update { name }

    private val _cachedStudySettings = MutableStateFlow(DEFAULT_CACHED_STUDY_SETTINGS)
    override val cachedStudySettings: Flow<String> = _cachedStudySettings.asStateFlow()
    override suspend fun setCachedStudySettings(json: String) = _cachedStudySettings.update { json }

    private val _themeMode = MutableStateFlow(DEFAULT_THEME_MODE)
    override val themeMode: StateFlow<AppTheme> = _themeMode.asStateFlow()
    override suspend fun setThemeMode(theme: AppTheme) = _themeMode.update { theme }

    private val _nameNudgeDismissed = MutableStateFlow(DEFAULT_NAME_NUDGE_DISMISSED)
    override val nameNudgeDismissed: Flow<Boolean> = _nameNudgeDismissed.asStateFlow()
    override suspend fun setNameNudgeDismissed(dismissed: Boolean) = _nameNudgeDismissed.update { dismissed }

    private val _avatarNudgeDismissed = MutableStateFlow(DEFAULT_AVATAR_NUDGE_DISMISSED)
    override val avatarNudgeDismissed: Flow<Boolean> = _avatarNudgeDismissed.asStateFlow()
    override suspend fun setAvatarNudgeDismissed(dismissed: Boolean) = _avatarNudgeDismissed.update { dismissed }

    private val _pendingDeckFollow = MutableStateFlow(DEFAULT_PENDING_DECK_FOLLOW)
    override val pendingDeckFollow: Flow<String> = _pendingDeckFollow.asStateFlow()
    override suspend fun setPendingDeckFollow(value: String) = _pendingDeckFollow.update { value }

    private val _backupReminderDay = MutableStateFlow(NO_BACKUP_REMINDER_DAY)
    override val backupReminderDay: Flow<Int> = _backupReminderDay.asStateFlow()
    override suspend fun setBackupReminderDay(day: Int) = _backupReminderDay.update { day }

    private val _recapDismissal = MutableStateFlow(DEFAULT_RECAP_DISMISSAL)
    override val recapDismissal: Flow<String> = _recapDismissal.asStateFlow()
    override suspend fun setRecapDismissal(value: String) = _recapDismissal.update { value }

    private val _updatePromptDay = MutableStateFlow(NO_UPDATE_PROMPT_DAY)
    override val updatePromptDay: Flow<Int> = _updatePromptDay.asStateFlow()
    override suspend fun setUpdatePromptDay(day: Int) = _updatePromptDay.update { day }
}

class InMemoryPendingReviewStore : PendingReviewStore {

    private val entries = MutableStateFlow<List<PendingReview>>(emptyList())

    override suspend fun load(): List<PendingReview> = entries.value

    override suspend fun save(entries: List<PendingReview>) = this.entries.update { entries.toList() }
}

class InMemoryStudyProgressStore : StudyProgressStore {

    private val stored = MutableStateFlow<Pair<String, DailyStudyProgress>?>(null)

    override suspend fun load(ownerPubky: String): DailyStudyProgress? =
        stored.value?.takeIf { it.first == ownerPubky }?.second

    override suspend fun save(ownerPubky: String, progress: DailyStudyProgress) =
        stored.update { ownerPubky to progress }
}

class InMemoryDeckCacheStore : DeckCacheStore {

    private val stored = MutableStateFlow<Pair<String, CachedDecks>?>(null)

    override suspend fun load(ownerPubky: String): CachedDecks? =
        stored.value?.takeIf { it.first == ownerPubky }?.second

    override suspend fun save(ownerPubky: String, decks: CachedDecks) = stored.update { ownerPubky to decks }

    override suspend fun clear() = stored.update { null }
}

internal class InMemoryLocalKeyStore : LocalKeyStore {

    private val key = MutableStateFlow<LocalKey?>(null)

    private val _custody = MutableStateFlow<KeyCustody>(KeyCustody.External)
    override val custody: Flow<KeyCustody> = _custody.asStateFlow()

    override suspend fun save(key: LocalKey) {
        this.key.update { key }
        _custody.update { key.toCustody() }
    }

    override suspend fun current(): LocalKey? = key.value

    override suspend fun markBackedUp(method: BackupMethod) = mark { it.copy(backedUpBy = it.backedUpBy + method) }

    override suspend fun markRegistered() = mark { it.copy(registered = true) }

    override suspend fun clear() {
        key.update { null }
        _custody.update { KeyCustody.External }
    }

    private fun mark(change: (LocalKey) -> LocalKey) {
        val updated = key.updateAndGet { it?.let(change) } ?: return
        _custody.update { updated.toCustody() }
    }
}

class InMemorySignupTokenStore : SignupTokenStore {

    private val _pending = MutableStateFlow<PendingSignup?>(null)
    override val pending: Flow<PendingSignup?> = _pending.asStateFlow()

    override suspend fun save(pending: PendingSignup) = _pending.update { pending }

    override suspend fun clear() = _pending.update { null }
}

class InMemoryUnsplashKeyStore : UnsplashKeyStore {

    private val _key = MutableStateFlow("")
    override val key: Flow<String> = _key.asStateFlow()

    override suspend fun save(key: String) = _key.update { key }

    override suspend fun clear() = _key.update { "" }
}
