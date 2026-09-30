package com.github.jvsena42.loopky.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.pubky.PubkyLinks
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.auth.SignInReason
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Another user's public profile: their pubky.app profile (display name, bio, avatar initial), one
 * grid of decks, and a Follow / Unfollow toggle. Reads are public; the follow toggle writes to the
 * current user's pubky.app follows via [DiscoveryRepository].
 *
 * The grid holds what they wrote *and* what they follow, in one list. Both belong on a profile —
 * on a network this young most people have published nothing, so a grid of authored decks alone
 * was empty for almost everyone it described — and splitting them into two sections asked the
 * reader to care about a distinction the author caption already makes. What tells them apart is
 * the name under each tile, which is why it is a name rather than a tag.
 */
class FriendProfileViewModel(
    private val targetPubky: String,
    private val identityRepository: IdentityRepository,
    private val discoveryRepository: DiscoveryRepository,
    private val deckRepository: DeckRepository,
    private val pubkyEnvironment: PubkyEnvironment,
) : ViewModel() {
    private val _state = MutableStateFlow(
        FriendProfileUiState(
            identity = PubkyIdentity(targetPubky, displayName = null, avatarUrl = null, bio = null),
        ),
    )
    val state: StateFlow<FriendProfileUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FriendProfileEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<FriendProfileEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var followJob: Job? = null
    private var followCountsJob: Job? = null

    init {
        load()
    }

    fun onRefresh() = load(forceRefresh = true, silent = true)

    /**
     * [forceRefresh] reads the profile past the shared cache, for an explicit pull-to-refresh.
     * [silent] keeps the profile on screen while that runs — swapping it for the full-screen
     * spinner would make a pull-to-refresh look like the screen had been thrown away.
     */
    private fun load(forceRefresh: Boolean = false, silent: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update {
                if (silent) it.copy(isRefreshing = true) else it.copy(isLoading = true)
            }

            val profile = identityRepository.fetchProfile(targetPubky, forceRefresh).getOrNull()
            // Concurrent: each is a directory listing plus a manifest fetch per deck, so running
            // them one after the other would double the wait before anything renders. Each
            // degrades to empty on its own failure — a grid that could not be read is a strip
            // this profile does without, not a screen-wide error.
            val (decks, followed) = coroutineScope {
                val authored = async {
                    runSuspendCatching { deckRepository.listByAuthor(targetPubky) }.getOrElse {
                        Log.e(TAG, "load: listByAuthor failed — ${it.message}", it)
                        emptyList()
                    }
                }
                val subscribed = async {
                    runSuspendCatching { deckRepository.listFollowedBy(targetPubky) }.getOrElse {
                        Log.e(TAG, "load: listFollowedBy failed — ${it.message}", it)
                        emptyList()
                    }
                }
                authored.await() to subscribed.await()
            }
            val isFollowing = runSuspendCatching { discoveryRepository.isFollowing(targetPubky) }
                .getOrDefault(false)
            // Pasting your own pubky into the add-friend sheet used to open this screen with a
            // live Follow button — you could follow yourself.
            val myPubky = runSuspendCatching { identityRepository.currentSession()?.identity?.pubky }
                .getOrNull()
            val isSelf = myPubky != null && myPubky == targetPubky

            val identity = profile ?: bareIdentity(targetPubky)
            // Theirs first, then what they follow. A deck they wrote *and* follow would otherwise
            // appear twice; authorship is the more specific claim, so it keeps the entry.
            val merged = decks + followed.filterNot { deck -> decks.any { it.id == deck.id } }
            authors[targetPubky] = identity
            _state.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    identity = identity,
                    isFollowing = isFollowing,
                    isSignedIn = myPubky != null,
                    isSelf = isSelf,
                    decks = merged.map { it.toCard() },
                    // Counted off the authored list alone: "Decks" and "Cards" are a claim about
                    // what this person published, and somebody else's deck must not inflate it.
                    deckCount = decks.size,
                    cardCount = decks.sumOf { deck -> deck.cardCount },
                )
            }
            Log.d(TAG, "load: decks=${decks.size} followed=${followed.size} following=$isFollowing")
            loadAuthorProfiles(merged)
            loadFollowCounts()
        }
    }

    /** Resolved author profiles, so a re-render reuses names already in hand. */
    private val authors = mutableMapOf<String, PubkyIdentity>()

    /**
     * Names for the authors of the decks they follow, after first paint.
     *
     * A followed deck belongs to somebody this screen has never fetched, and its caption is the
     * only thing separating it from a deck they wrote — so the name matters, but not enough to
     * hold the whole profile behind one round-trip per stranger. Until it lands the tile shows a
     * truncated pubky, and an author with no published profile simply keeps it.
     */
    private suspend fun loadAuthorProfiles(decks: List<Deck>) {
        val pending = decks.map { it.authorPubky }.distinct().filterNot { it in authors }
        if (pending.isEmpty()) return
        val resolved = coroutineScope {
            pending.map { pubky -> async { identityRepository.fetchProfile(pubky).getOrNull() } }
                .awaitAll()
        }
        resolved.filterNotNull().forEach { authors[it.pubky] = it }
        if (resolved.all { it == null }) return
        _state.update { current ->
            current.copy(decks = current.decks.map { it.copy(author = authors[it.authorPubky] ?: it.author) })
        }
        Log.d(TAG, "loadAuthorProfiles: resolved=${resolved.count { it != null }}/${pending.size}")
    }

    /**
     * The people counts, on their own job — the same split the signed-in user's own profile makes.
     * Resolving a follow graph costs an indexer round-trip per person, so folding this into
     * [load] would hold the whole profile behind it, and a count that cannot be fetched stays
     * null rather than reading as zero.
     */
    private fun loadFollowCounts() {
        followCountsJob?.cancel()
        followCountsJob = viewModelScope.launch {
            val following = runSuspendCatching { discoveryRepository.followingProfiles(targetPubky) }
                .onFailure { Log.w(TAG, "loadFollowCounts: following FAILED — ${it.message}") }
                .getOrNull()
            val followers = runSuspendCatching { discoveryRepository.followerProfiles(targetPubky) }
                .onFailure { Log.w(TAG, "loadFollowCounts: followers FAILED — ${it.message}") }
                .getOrNull()

            _state.update {
                it.copy(followingCount = following?.size, followerCount = followers?.size)
            }
            Log.d(TAG, "loadFollowCounts: following=${following?.size} followers=${followers?.size}")
        }
    }

    fun onToggleFollow() {
        if (followJob?.isActive == true || _state.value.isSelf) return
        // A follow is a record under the reader's own pubky, so a guest has nowhere to write it.
        // The button stays live rather than greyed: reaching for it is the moment worth
        // explaining what an account is for.
        if (!_state.value.isSignedIn) {
            _state.update { it.copy(signInPrompt = SignInReason.FollowPerson) }
            return
        }
        followJob = viewModelScope.launch {
            val wasFollowing = _state.value.isFollowing
            // Optimistic flip; revert on failure. The previous attempt's error goes with it —
            // it used to survive a later success, leaving a stale failure under a live Following.
            _state.update {
                it.copy(isFollowing = !wasFollowing, isProcessingFollow = true, errorReason = null)
            }

            val result = if (wasFollowing) {
                discoveryRepository.unfollowUser(targetPubky)
            } else {
                discoveryRepository.followUser(targetPubky)
            }
            result
                .onSuccess { _state.update { it.copy(isProcessingFollow = false) } }
                .onFailure { err ->
                    Log.e(TAG, "onToggleFollow: FAILED — ${err.message}", err)
                    _state.update { it.copy(isFollowing = wasFollowing, isProcessingFollow = false) }
                    // Was emitted into an empty lambda whose comment claimed it was
                    // "surfaced inline below"; nothing rendered it at all.
                    _state.update { it.copy(errorReason = err.toErrorReason()) }
                }
        }
    }

    /** The visitor read the prompt and stayed. Nothing was written either way. */
    fun onDismissSignInPrompt() {
        _state.update { it.copy(signInPrompt = null) }
    }

    fun onCopyPubky() {
        viewModelScope.launch { _effects.emit(FriendProfileEffect.CopyToClipboard(targetPubky)) }
    }

    /**
     * Passing someone else on is the point of a public profile, and this screen had no way to do
     * it — the pubky could only be copied, which puts a bare key on the clipboard rather than
     * something a recipient can tap.
     */
    fun onShareClick() {
        val identity = _state.value.identity
        viewModelScope.launch {
            _effects.emit(
                FriendProfileEffect.ShareProfile(identity, PubkyLinks.profileWebUrl(targetPubky)),
            )
        }
    }

    /**
     * Open this person on the pubky.app web client, where the rest of their Pubky life is —
     * posts, tags, the follow graph Loopky only shows the Loopky half of. Environment-scoped for
     * the same reason the indexer is: a staging account does not exist on production (#42).
     */
    fun onOpenOnPubkyApp() {
        viewModelScope.launch {
            _effects.emit(FriendProfileEffect.OpenUrl(pubkyEnvironment.profileUrl(targetPubky)))
        }
    }

    /**
     * [authorPubky] comes from the tile, not from [targetPubky]: a followed deck belongs to
     * somebody else, and opening it against this profile's owner reads a manifest that is not
     * there.
     */
    fun onOpenDeck(authorPubky: String, deckId: String) {
        viewModelScope.launch { _effects.emit(FriendProfileEffect.OpenDeck(authorPubky, deckId)) }
    }

    /**
     * The name under a followed deck's tile is the way on to whoever wrote it — the same hop
     * Discover's tiles offer, and the reason the caption is a name rather than a tag.
     *
     * Refuses [targetPubky]: their own decks are captioned with the profile you are already
     * reading, so honouring that tap would push a second copy of this screen onto the back stack.
     */
    fun onOpenAuthor(authorPubky: String) {
        if (authorPubky == targetPubky) return
        viewModelScope.launch { _effects.emit(FriendProfileEffect.OpenProfile(authorPubky)) }
    }

    private fun Deck.toCard(): FriendDeck = FriendDeck(
        id = id,
        authorPubky = authorPubky,
        title = title,
        cardCount = cardCount,
        coverEmoji = coverEmoji ?: title.firstOrNull()?.toString() ?: "📚",
        coverImage = coverImageRef,
        author = authors[authorPubky] ?: bareIdentity(authorPubky),
    )

    companion object {
        private const val TAG = "Loopky/FriendProfileVM"
    }
}

data class FriendProfileUiState(
    val isLoading: Boolean = true,
    /** A pull-to-refresh in flight — unlike [isLoading], the profile stays on screen. */
    val isRefreshing: Boolean = false,
    /** Who this profile belongs to — the name, avatar and pubky all read off this one value. */
    val identity: PubkyIdentity,
    val isFollowing: Boolean = false,
    val isProcessingFollow: Boolean = false,
    /**
     * Whether anyone is signed in. A profile and its decks are public records, so this screen
     * reads in full without an account; only Follow needs one.
     */
    val isSignedIn: Boolean = true,
    /** A guest reached for Follow. Held over the profile, which stays readable underneath. */
    val signInPrompt: SignInReason? = null,
    /** True when this is the signed-in user's own profile — no Follow button. */
    val isSelf: Boolean = false,
    /** Set when a follow/unfollow fails; rendered inline. */
    val errorReason: ErrorReason? = null,
    /** What they wrote and what they follow, one list, theirs first. */
    val decks: List<FriendDeck> = emptyList(),
    /**
     * Counted off their *authored* decks only — not [decks], which also holds decks they follow.
     * Derived here rather than in the UI, so the screen stays a dumb renderer.
     */
    val deckCount: Int = 0,
    val cardCount: Int = 0,
    /** Null until the follow graph resolves, and after it fails — never a stand-in zero. */
    val followingCount: Int? = null,
    val followerCount: Int? = null,
)

data class FriendDeck(
    val id: String,
    /** Whose homeserver the deck — and its cover blob — lives on. */
    val authorPubky: String,
    val title: String,
    val cardCount: Int,
    val coverEmoji: String,
    /** The deck's cover art, when it has one. Renders over [coverEmoji]; null falls back to it. */
    val coverImage: MediaRef.Image? = null,
    /**
     * Who wrote it, for the caption under the title. This is what tells a deck they follow from
     * one they wrote now that both share a grid — a tag could not, since it says nothing about
     * whose deck it is.
     */
    val author: PubkyIdentity,
)

private fun bareIdentity(pubky: String) =
    PubkyIdentity(pubky, displayName = null, avatarUrl = null, bio = null)

sealed interface FriendProfileEffect {
    data class CopyToClipboard(val text: String) : FriendProfileEffect
    data class OpenDeck(val authorPubky: String, val deckId: String) : FriendProfileEffect

    /** Another person's profile — the author of a deck this one follows. */
    data class OpenProfile(val pubky: String) : FriendProfileEffect

    /** Hand [url] to the browser — the pubky.app profile, never an in-app destination. */
    data class OpenUrl(val url: String) : FriendProfileEffect

    /** [identity] names the person in the shared message; [uri] is what opens Loopky on them. */
    data class ShareProfile(val identity: PubkyIdentity, val uri: String) : FriendProfileEffect
}
