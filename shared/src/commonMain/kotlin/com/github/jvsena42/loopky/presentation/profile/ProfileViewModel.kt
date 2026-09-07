package com.github.jvsena42.loopky.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.pubky.PubkyLinks
import com.github.jvsena42.loopky.data.pubky.requiresReauth
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// TooManyFunctions: this screen is one subject — your own account — and its handlers are one
// tap each. Splitting them across two ViewModels over the same state would cost more than it buys.
@Suppress("TooManyFunctions")
class ProfileViewModel(
    private val identityRepository: IdentityRepository,
    private val deckRepository: DeckRepository,
    private val srsRepository: SrsRepository,
    private val discoveryRepository: DiscoveryRepository,
    private val appPreferences: AppPreferences,
    private val pubkyEnvironment: PubkyEnvironment,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ProfileEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<ProfileEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var followsJob: Job? = null

    init {
        load()
        // Custody drives the backup card above sign-out, and it is watched here rather than only
        // in Settings because this is the screen that signs you out — the warning belongs next to
        // the button that can destroy the key it is warning about.
        viewModelScope.launch {
            identityRepository.keyCustody.collect { custody ->
                _state.update { it.copy(keyCustody = custody) }
            }
        }
        // The deck/card counters shown here go stale the moment a deck is published or deleted.
        viewModelScope.launch {
            deckRepository.changes.collect { load(silent = true) }
        }
        // Same for the due counter: studying runs on its own destination while this tab stays
        // composed, so without this it keeps the value it had before the session. The due counter
        // is the *only* thing a review can move, so it gets the cache-only recount — see
        // [refreshDueCount].
        viewModelScope.launch {
            srsRepository.changes.collect { refreshDueCount() }
        }
        viewModelScope.launch {
            appPreferences.nameNudgeDismissed.collect { dismissed ->
                _state.update { it.copy(nameNudgeDismissed = dismissed) }
            }
        }
        viewModelScope.launch {
            appPreferences.avatarNudgeDismissed.collect { dismissed ->
                _state.update { it.copy(avatarNudgeDismissed = dismissed) }
            }
        }
    }

    /**
     * Recompute the due counter from the SRS repository's in-memory state.
     *
     * Once per graded card, this used to be a full [load]: a `forceRefresh` profile GET, a re-list
     * of every owned deck, a `dueToday()` that re-synced each of their manifests, and a pair of
     * Nexus calls for the follow counts — none of which a review can change (#102).
     */
    private suspend fun refreshDueCount() {
        val counts = runSuspendCatching { srsRepository.dueCountsCached() }
            .onFailure { Log.e(TAG, "refreshDueCount: FAILED — ${it.message}", it) }
            .getOrNull() ?: return
        // Empty means a cold cache, not "nothing due" — leave the last real number alone.
        if (counts.isEmpty()) return
        _state.update { it.copy(dueCount = counts.values.sumOf { c -> c.due }) }
    }

    /**
     * Fill the deck and card counters from the library this device last saw, so the header is not
     * blank while a profile GET and a deck listing run. The due counter stays out of it: review
     * state is not cached across processes, and this screen's own number would be the guess.
     */
    private suspend fun paintFromCache() {
        val owned = runSuspendCatching { deckRepository.listCached() }.getOrNull()?.owned
        if (owned.isNullOrEmpty()) {
            _state.update { it.copy(isLoading = true) }
            return
        }
        _state.update {
            it.copy(
                isLoading = true,
                deckCount = owned.size,
                cardCount = owned.sumOf { deck -> deck.cardCount },
            )
        }
    }

    fun onRefresh() = load()

    /** [silent] keeps the profile on screen while a background refresh runs. */
    private fun load(silent: Boolean = false) {
        // Cancel rather than bail out: a change that lands mid-load must not be dropped.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            Log.d(TAG, "load: fetching profile + stats (silent=$silent)")
            if (!silent) paintFromCache()

            val session = runSuspendCatching { identityRepository.currentSession() }.getOrNull()
                ?: runSuspendCatching { identityRepository.loadPersistedSession() }.getOrNull()

            if (session == null) {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }

            val pubky = session.identity.pubky

            // Fetch fresh profile from homeserver — this screen is where the name is edited, so it
            // reads past the cache the rest of the app shares.
            val profile = runSuspendCatching {
                identityRepository.fetchProfile(pubky, forceRefresh = true).getOrNull()
            }.getOrNull() ?: session.identity

            // Fetch deck stats
            val decksResult = runSuspendCatching { deckRepository.listOwned() }
            if (decksResult.exceptionOrNull()?.requiresReauth() == true) {
                handleSessionExpired()
                return@launch
            }
            val decks = decksResult.getOrElse { emptyList() }
            val deckCount = decks.size
            val cardCount = decks.sumOf { it.cardCount }
            // Followed decks are studiable (#33) and their review state lands on your own
            // homeserver, so they count toward what you are behind on — the counters above are
            // owned-only because those say what you have *written*. Handing `countsToday` the
            // owned half alone made this screen and Home report two different totals to the same
            // user, and neither said so.
            val followed = runSuspendCatching { deckRepository.listFollowed() }
                .onFailure { Log.e(TAG, "load: followed decks unavailable — ${it.message}", it) }
                .getOrDefault(emptyList())
            val studiable = (decks + followed).distinctBy { it.id }
            // Degrade to 0 rather than failing the whole profile load if the SRS read fails.
            // The due half only, consistent with Deck Detail: cards you have never met are not
            // something you are behind on (#101 §7).
            val dueCount = runSuspendCatching {
                srsRepository.countsToday(studiable).values.sumOf { it.due }
            }.getOrDefault(0)

            // Fall back field by field rather than whole-identity: a published profile that only
            // sets a picture must not blank the name the session already knows.
            val identity = PubkyIdentity(
                pubky = pubky,
                displayName = profile.displayName ?: session.identity.displayName,
                avatarUrl = profile.avatarUrl ?: session.identity.avatarUrl,
                bio = profile.bio ?: session.identity.bio,
            )
            _state.update {
                it.copy(
                    isLoading = false,
                    identity = identity,
                    deckCount = deckCount,
                    cardCount = cardCount,
                    dueCount = dueCount,
                )
            }
            Log.d(TAG, "load: done — decks=$deckCount cards=$cardCount due=$dueCount")
            loadFollowCounts(pubky)
        }
    }

    /**
     * The people counts, on their own job.
     *
     * Deciding which of your follows are Loopky accounts costs an indexer round-trip each, so
     * folding this into [load] would hold the whole profile behind the slowest of them. It runs
     * after, and a failure leaves the counts null rather than taking the screen down — a stat you
     * cannot fetch is not an error worth a snackbar.
     */
    private fun loadFollowCounts(pubky: String) {
        followsJob?.cancel()
        followsJob = viewModelScope.launch {
            val following = runSuspendCatching { discoveryRepository.followingProfiles(pubky) }
                .onFailure { Log.w(TAG, "loadFollowCounts: following FAILED — ${it.message}") }
                .getOrNull()
            val followers = runSuspendCatching { discoveryRepository.followerProfiles(pubky) }
                .onFailure { Log.w(TAG, "loadFollowCounts: followers FAILED — ${it.message}") }
                .getOrNull()

            _state.update {
                it.copy(followingCount = following?.size, followerCount = followers?.size)
            }
            Log.d(TAG, "loadFollowCounts: following=${following?.size} followers=${followers?.size}")
        }
    }

    fun onEditProfileClick() {
        val identity = _state.value.identity
        _state.update {
            it.copy(
                showEditSheet = true,
                editName = identity?.displayName.orEmpty(),
                editBio = identity?.bio.orEmpty(),
            )
        }
    }

    /**
     * Wave away the "add a name" prompt for good, on this device.
     *
     * Persisted rather than kept in state: the prompt is on the tab the reader visits most, so a
     * dismissal that lasted only until the next launch would be a nag they can never finish
     * refusing.
     */
    fun onDismissNameNudge() {
        viewModelScope.launch { appPreferences.setNameNudgeDismissed(true) }
    }

    /**
     * Wave away the "add a photo" prompt for good, on this device — see [onDismissNameNudge].
     */
    fun onDismissAvatarNudge() {
        viewModelScope.launch { appPreferences.setAvatarNudgeDismissed(true) }
    }

    fun onDismissEditSheet() {
        _state.update { it.copy(showEditSheet = false) }
    }

    fun onEditNameChanged(text: String) {
        _state.update { it.copy(editName = text) }
    }

    fun onEditBioChanged(text: String) {
        _state.update { it.copy(editBio = text) }
    }

    fun onSaveClick() {
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            val current = _state.value
            _state.update { it.copy(isSaving = true) }
            Log.d(TAG, "onSaveClick: saving profile")

            identityRepository.updateProfile(
                name = current.editName.ifBlank { null },
                bio = current.editBio.ifBlank { null },
            ).onSuccess { identity ->
                Log.d(TAG, "onSaveClick: saved")
                _state.update {
                    it.copy(
                        isSaving = false,
                        showEditSheet = false,
                        identity = identity,
                    )
                }
            }.onFailure { err ->
                Log.e(TAG, "onSaveClick: FAILED — ${err.message}", err)
                _state.update { it.copy(isSaving = false) }
                if (err.requiresReauth()) {
                    handleSessionExpired()
                } else {
                    _effects.emit(ProfileEffect.ShowError(err.message ?: "Could not save profile."))
                }
            }
        }
    }

    fun onShareClick() {
        val identity = _state.value.identity ?: return
        viewModelScope.launch {
            _effects.emit(
                ProfileEffect.ShareProfile(identity, PubkyLinks.profileUri(identity.pubky)),
            )
        }
    }

    /**
     * Open this account's profile on the pubky.app web client.
     *
     * A Loopky profile *is* a pubky.app profile — the same `profile.json`, the same follow graph —
     * and nothing in the app said so. The URL comes from [PubkyEnvironment] rather than a constant
     * because a staging account has no production profile to open (#42).
     */
    fun onOpenOnPubkyApp() {
        val pubky = _state.value.identity?.pubky ?: return
        viewModelScope.launch {
            _effects.emit(ProfileEffect.OpenUrl(pubkyEnvironment.profileUrl(pubky)))
        }
    }

    /** The pubky chip is the copy control, the same one someone else's profile carries. */
    fun onCopyPubky() {
        val pubky = _state.value.identity?.pubky ?: return
        viewModelScope.launch { _effects.emit(ProfileEffect.CopyToClipboard(pubky)) }
    }

    /** Best-effort sign-out + redirect to onboarding when the session can't be refreshed. */
    private suspend fun handleSessionExpired() {
        Log.d(TAG, "handleSessionExpired: session expired — signing out")
        runSuspendCatching { identityRepository.signOut() }
        _state.update { it.copy(isLoading = false, isSaving = false, showEditSheet = false) }
        _effects.emit(ProfileEffect.NavigateToOnboarding)
    }

    fun onSignOutClick() {
        viewModelScope.launch {
            Log.d(TAG, "onSignOutClick: signing out")
            identityRepository.signOut()
            _effects.emit(ProfileEffect.NavigateToOnboarding)
        }
    }

    companion object {
        private const val TAG = "Loopky/ProfileVM"
    }
}

data class ProfileUiState(
    val isLoading: Boolean = true,
    /**
     * The whole identity rather than a name and an initial: the avatar slot needs
     * [PubkyIdentity.avatarUrl] to draw a picture at all, and this screen used to keep only a
     * `Char`, which is why the signed-in user was the one person in the app whose photo never
     * appeared.
     */
    val identity: PubkyIdentity? = null,
    val deckCount: Int = 0,
    val cardCount: Int = 0,
    val dueCount: Int = 0,
    /**
     * People counts, null until they resolve — and they resolve later than the rest of the screen.
     * Both are counts of *Loopky* accounts, matching the lists they open, so they are smaller than
     * whatever pubky.app reports for the same graph.
     */
    val followingCount: Int? = null,
    val followerCount: Int? = null,
    /**
     * What key this *device* holds — not necessarily the signed-in account's. Read it through
     * [needsBackup] rather than directly. [KeyCustody.External] is the safe default: a Ring-held
     * key has nothing on this device to lose, so nothing must flash while custody resolves.
     */
    val keyCustody: KeyCustody = KeyCustody.External,
    /**
     * Whether this device has been told not to ask for a display name again — see [showNameNudge].
     *
     * Starts suppressed and is corrected by the stored value, which is the safe direction: a
     * prompt someone already refused must not flash on screen while the preference resolves.
     */
    val nameNudgeDismissed: Boolean = true,
    /** Whether this device has been told not to ask for a photo again — see [showAvatarNudge]. */
    val avatarNudgeDismissed: Boolean = true,
    val showEditSheet: Boolean = false,
    val editName: String = "",
    val editBio: String = "",
    val isSaving: Boolean = false,
) {
    /**
     * Whether to warn that **the account on screen** has no copy of its key anywhere else.
     *
     * Three conditions, and the pubky comparison is the one that is easy to miss. [keyCustody]
     * answers "what key is in the vault", which is not the same question: `createLocalAccount`
     * stores a minted key before `signUp`, an interrupted registration deliberately leaves it
     * there so the signup can be resumed, and signing in with Pubky Ring afterwards never touches
     * the key store. Without the comparison, that stranded key raises a warning on a Ring
     * account's profile about an identity the reader does not hold — and offers to back up
     * somebody else's key.
     *
     * False while [identity] is still null, which is the safe direction: a nag that has not
     * resolved yet is better than one aimed at the wrong account.
     */
    val needsBackup: Boolean
        get() = (keyCustody as? KeyCustody.Loopky)
            ?.let { !it.isBackedUp && it.pubky == identity?.pubky } == true

    /**
     * Whether to invite this account to introduce itself.
     *
     * A nameless profile falls back to its pubky everywhere it appears — on its own decks, in
     * someone's follower list — and nothing in the app said so. Publishing a name retires the
     * prompt on its own, so [nameNudgeDismissed] exists only for the reader who would rather stay
     * a key.
     */
    val showNameNudge: Boolean
        get() = !isLoading &&
            !nameNudgeDismissed &&
            identity != null &&
            identity.displayName.isNullOrBlank()

    /**
     * Whether to invite this account to put a face to the name.
     *
     * Yields to [showNameNudge] rather than stacking under it: a hero followed by two cards asking
     * for two different things is a wall of chores, and a name is the one that changes how this
     * account reads everywhere. The photo is asked for on the next visit, once the name is in.
     *
     * Unlike the name, there is nothing in Loopky that can set one — the card points at pubky.app,
     * which is the same account behind the same key.
     */
    val showAvatarNudge: Boolean
        get() = !isLoading &&
            !avatarNudgeDismissed &&
            !showNameNudge &&
            identity != null &&
            identity.avatarUrl.isNullOrBlank()
}

sealed interface ProfileEffect {
    data object NavigateToOnboarding : ProfileEffect

    /** [identity] names the person in the shared message; [uri] is what opens Loopky on them. */
    data class ShareProfile(val identity: PubkyIdentity, val uri: String) : ProfileEffect
    data class CopyToClipboard(val text: String) : ProfileEffect

    /** Hand [url] to the browser — the pubky.app profile, never an in-app destination. */
    data class OpenUrl(val url: String) : ProfileEffect
    data class ShowError(val message: String) : ProfileEffect
}
