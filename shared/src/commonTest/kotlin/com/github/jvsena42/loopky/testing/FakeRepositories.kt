package com.github.jvsena42.loopky.testing

import com.github.jvsena42.loopky.data.anki.BulkNote
import com.github.jvsena42.loopky.data.homegate.LnInvoice
import com.github.jvsena42.loopky.data.homegate.MethodAvailability
import com.github.jvsena42.loopky.data.pubky.CardChunking
import com.github.jvsena42.loopky.data.repository.AuthFlowHandle
import com.github.jvsena42.loopky.data.repository.CachedDecks
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.CompactionOutcome
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.PinnedBlob
import com.github.jvsena42.loopky.data.repository.PublishProgress
import com.github.jvsena42.loopky.data.repository.RehostOutcome
import com.github.jvsena42.loopky.data.repository.SettingsOrigin
import com.github.jvsena42.loopky.data.repository.SettingsRepository
import com.github.jvsena42.loopky.data.repository.SignOutOutcome
import com.github.jvsena42.loopky.data.repository.SignupAvailability
import com.github.jvsena42.loopky.data.repository.SignupRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.data.repository.StudySettingsSnapshot
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.repository.TaggedSubject
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.data.storage.DeckCacheStore
import com.github.jvsena42.loopky.data.storage.PendingReview
import com.github.jvsena42.loopky.data.storage.PendingReviewStore
import com.github.jvsena42.loopky.data.storage.PendingSignup
import com.github.jvsena42.loopky.data.storage.SignupTokenStore
import com.github.jvsena42.loopky.data.storage.StudyProgressStore
import com.github.jvsena42.loopky.data.storage.UnsplashKeyStore
import com.github.jvsena42.loopky.data.storage.decodeDeckCache
import com.github.jvsena42.loopky.data.storage.encodeDeckCache
import com.github.jvsena42.loopky.domain.model.AppTheme
import com.github.jvsena42.loopky.domain.model.BACK_FIELD
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.DailyStudyProgress
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.domain.model.DeckCounts
import com.github.jvsena42.loopky.domain.model.DeckMastery
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.FRONT_FIELD
import com.github.jvsena42.loopky.domain.model.HomeserverLookup
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.domain.model.KeySource
import com.github.jvsena42.loopky.domain.model.LocalAccount
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.ParsedRow
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Separator
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.SrsState
import com.github.jvsena42.loopky.domain.model.StudySettings
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.TriageDecision
import com.github.jvsena42.loopky.domain.model.inStudyOrder
import com.github.jvsena42.loopky.domain.model.isFullyMastered
import com.github.jvsena42.loopky.domain.model.masteryShare
import com.github.jvsena42.loopky.domain.model.maturityThresholdDays
import com.github.jvsena42.loopky.domain.model.ordForIndex
import com.github.jvsena42.loopky.domain.model.previewIntervals
import com.github.jvsena42.loopky.domain.model.review
import com.github.jvsena42.loopky.platform.BackgroundTasks
import com.github.jvsena42.loopky.platform.MediaProcessor
import com.github.jvsena42.loopky.platform.ProcessedImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeIdentityRepository(var session: Session? = fakeSession()) : IdentityRepository {
    var signOutCount = 0

    /** What [beginSignIn] hands back, and what the returned handle's `complete()` answers. */
    var authUrl: String = "pubkyauth:///?caps=&secret=test"
    var beginSignInError: Throwable? = null
    var completionResult: Result<Session> = Result.success(fakeSession())
    var beginSignInCount = 0

    /**
     * Makes the handle's `complete()` never return, modelling the real failure where Pubky Ring
     * declines on its own side and posts nothing to the relay — the FFI's `await_auth_approval`
     * has no timeout of its own, so it simply blocks forever.
     */
    var completionNeverReturns = false

    /** Profiles served by [fetchProfile]; a pubky that is absent fails as an unpublished one would. */
    val profiles = mutableMapOf<String, PubkyIdentity>()
    val fetchedProfiles = mutableListOf<String>()

    // --- Local keys ------------------------------------------------------------

    /** Custody reported to the UI. Defaults to Ring, which is every account predating #147. */
    val custodyFlow = MutableStateFlow<KeyCustody>(KeyCustody.External)
    override val keyCustody: Flow<KeyCustody> = custodyFlow

    /** Pubkys derived per source, so a test can model a typo deriving a stranger's key. */
    var derivedPubky: Result<String> = Result.success(fakePubkyFor(fakeSecretKeyFor(VALID_TEST_MNEMONIC)))

    /**
     * What the pkarr pre-flight answers. Defaults to registered — the boring case — so a test that
     * cares about the other two has to say so.
     */
    var homeserverLookup: HomeserverLookup = HomeserverLookup.Registered("homeserver-pubky")

    /** Counts lookups, so a test can assert none happen before an explicit submit. */
    var lookupCount = 0

    /** Records what was handed to [signInWithKey], and how often. */
    val signInWithKeyCalls = mutableListOf<KeySource>()
    var signInWithKeyResult: Result<Session> = Result.success(fakeSession())

    override suspend fun derivePubky(source: KeySource): Result<String> = derivedPubky

    override suspend fun lookupHomeserver(pubky: String): HomeserverLookup {
        lookupCount++
        return homeserverLookup
    }

    override suspend fun signInWithKey(source: KeySource, knownHomeserver: String?): Result<Session> {
        signInWithKeyCalls.add(source)
        return signInWithKeyResult.onSuccess { session = it }
    }

    override suspend fun currentSession(): Session? = session
    override suspend fun loadPersistedSession(): Session? = session

    /** When set, a non-forced sign-out is refused, as an un-backed-up local key makes it. */
    var signOutRefusal: Throwable? = null
    val forcedSignOuts = mutableListOf<Boolean>()

    /** Whether the homeserver confirms revocation. False models a revoke that could not be made. */
    var revokesRemotely = true

    /** Secrets handed to [revokeSession], and what it should answer with. */
    val revokedSecrets = mutableListOf<String>()
    var revokeSessionResult: Result<Unit> = Result.success(Unit)

    override suspend fun signOut(force: Boolean): Result<SignOutOutcome> {
        forcedSignOuts.add(force)
        signOutRefusal?.takeIf { !force }?.let { return Result.failure(it) }
        signOutCount++
        val had = session != null
        session = null
        return Result.success(
            SignOutOutcome(revokedRemotely = revokesRemotely && had, hadSession = had, clearedLocally = true),
        )
    }

    override suspend fun revokeSession(sessionSecret: String): Result<Unit> {
        revokedSecrets += sessionSecret
        return revokeSessionResult
    }

    /** Records what [createLocalAccount] was asked for, and how many keys were minted. */
    val createLocalAccountCalls = mutableListOf<Pair<String, String>>()
    var createLocalAccountResult: Result<LocalAccount> =
        Result.success(LocalAccount(pubky = "pkminted", mnemonic = VALID_TEST_MNEMONIC))

    val registerHeldKeyCalls = mutableListOf<Pair<String, String>>()
    var registerHeldKeyResult: Result<Session> = Result.success(fakeSession())

    /** Records what [holdKeyForRegistration] was asked to store. */
    val heldForRegistration = mutableListOf<KeySource>()
    var holdKeyResult: Result<String>? = null

    override suspend fun holdKeyForRegistration(source: KeySource): Result<String> {
        heldForRegistration.add(source)
        return holdKeyResult ?: derivedPubky
    }

    var discardedUnregisteredKeys = 0

    override fun discardUnregisteredKey() {
        discardedUnregisteredKeys++
    }

    override suspend fun createLocalAccount(
        homeserverPubky: String,
        signupToken: String,
    ): Result<LocalAccount> {
        createLocalAccountCalls.add(homeserverPubky to signupToken)
        return createLocalAccountResult
    }

    override suspend fun registerHeldKey(
        homeserverPubky: String,
        signupToken: String,
    ): Result<Session> {
        registerHeldKeyCalls.add(homeserverPubky to signupToken)
        return registerHeldKeyResult
    }

    var deleteAccountCount = 0
    var deleteAccountResult: Result<Unit> = Result.success(Unit)

    /** Progress reported by the last [deleteAccount], so a ViewModel test can drive the dialog. */
    var deleteAccountProgress: List<Pair<Int, Int>> = emptyList()

    /**
     * Suspends for this long after reporting progress, so a test can observe the in-flight state.
     * `StateFlow` conflates, and a sweep that reports and returns without ever suspending leaves a
     * collector nothing but the final value.
     */
    var deleteAccountDelayMs: Long = 0

    override suspend fun deleteAccount(onProgress: (Int, Int) -> Unit): Result<Unit> {
        deleteAccountCount++
        deleteAccountProgress.forEach { (done, total) -> onProgress(done, total) }
        if (deleteAccountDelayMs > 0) delay(deleteAccountDelayMs)
        // Only a success signs the user out, mirroring the real one: an interrupted sweep has to
        // leave them signed in, because that is the only state a retry is possible from.
        if (deleteAccountResult.isSuccess) session = null
        return deleteAccountResult
    }

    /** What the last [beginSignIn] asked for, so a caller can be shown to have suppressed them. */
    var lastBeginSignInReturnToApp: Boolean? = null
        private set

    override suspend fun beginSignIn(
        capabilities: String,
        returnToApp: Boolean,
    ): Result<AuthFlowHandle> {
        beginSignInCount++
        lastBeginSignInReturnToApp = returnToApp
        beginSignInError?.let { return Result.failure(it) }
        return Result.success(
            object : AuthFlowHandle {
                override val authUrl = this@FakeIdentityRepository.authUrl
                override suspend fun complete(): Result<Session> {
                    if (completionNeverReturns) awaitCancellation()
                    return completionResult
                }
            },
        )
    }

    /** Secrets handed to [adoptSession], and what it should answer with. */
    val adoptedSecrets = mutableListOf<String>()
    var adoptSessionResult: Result<Session>? = null

    override suspend fun adoptSession(sessionSecret: String): Result<Session> {
        adoptedSecrets += sessionSecret
        val result = adoptSessionResult ?: completionResult
        result.onSuccess { session = it }
        return result
    }

    /** Records what [beginSignUp] was asked for, so a retry can be shown to reuse the token. */
    val signUpCalls = mutableListOf<Pair<String, String>>()

    override suspend fun beginSignUp(
        homeserverPubky: String,
        signupToken: String,
        capabilities: String,
    ): Result<AuthFlowHandle> {
        signUpCalls += homeserverPubky to signupToken
        return beginSignIn(capabilities)
    }

    override suspend fun fetchProfile(pubky: String, forceRefresh: Boolean): Result<PubkyIdentity> {
        fetchedProfiles += pubky
        return profiles[pubky]?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("no profile for $pubky"))
    }

    override suspend fun updateProfile(name: String?, bio: String?): Result<PubkyIdentity> =
        Result.failure(UnsupportedOperationException("Not used in tests"))
}

class FakeDeckRepository : DeckRepository {
    val decks = mutableMapOf<String, Deck>()

    /** What [listCached] answers with. Null — no snapshot yet — is the default, as on a fresh install. */
    var cached: CachedDecks? = null

    /** Holds [listOwned] open, so a test can assert on what is on screen before it answers. */
    var listOwnedGate: CompletableDeferred<Unit>? = null

    override suspend fun listCached(): CachedDecks? = cached

    val published = mutableListOf<Pair<Deck, List<Card>>>()
    val deleted = mutableListOf<String>()
    val rehostedBlobs = mutableListOf<Pair<String, String>>()

    override suspend fun rehostBlob(deckId: String, sha256: String): Result<Unit> {
        rehostedBlobs.add(deckId to sha256)
        return Result.success(Unit)
    }

    val sweeps = mutableListOf<String>()

    override suspend fun rehostPendingMedia(deckId: String, maxChunks: Int): Result<RehostOutcome> {
        sweeps.add(deckId)
        return Result.success(RehostOutcome(0, 0, 0, 0, complete = true))
    }

    override suspend fun decksPendingRehost(): List<Deck> =
        decks.values.filter { it.source?.kind == DeckSource.Kind.Clone && !it.mediaRehosted }

    val compactions = mutableListOf<String>()

    override suspend fun compactDeck(deckId: String, maxMerges: Int): Result<CompactionOutcome> {
        compactions.add(deckId)
        val chunks = decks[deckId]?.chunks?.size ?: 0
        return Result.success(CompactionOutcome(0, 0, chunks, chunks, complete = true))
    }

    override suspend fun decksPendingCompaction(): List<Deck> =
        decks.values.filter { CardChunking.isSparse(it.chunks) }

    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /** Emit a change as the real repository would, without going through a mutation. */
    fun emitChange() {
        _changes.tryEmit(Unit)
    }

    /** When set, [listOwned] throws (HomeViewModel wraps the call in runSuspendCatching). */
    var listOwnedError: Throwable? = null
    var publishError: Throwable? = null

    /** When set, [publish] blocks on it, so a test can act while the upload is still in flight. */
    var publishGate: CompletableDeferred<Unit>? = null

    override suspend fun getLocal(id: String): Deck? = decks[id] ?: followedDecks[id]

    override suspend fun fetchRemote(authorPubky: String, deckId: String): Result<Deck> =
        getLocal(deckId)?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("deck $deckId not found"))

    override suspend fun publish(deck: Deck, cards: List<Card>): Result<Deck> =
        publish(deck, cards, onProgress = {})

    val progressReports = mutableListOf<PublishProgress>()

    override suspend fun publish(
        deck: Deck,
        cards: List<Card>,
        onProgress: (PublishProgress) -> Unit,
    ): Result<Deck> {
        publishError?.let { return Result.failure(it) }
        // Held open so a test can catch a publish mid-flight, the way cancelling a real upload
        // does. The real publish() is a runSuspendCatching, so cancellation propagates rather than
        // coming back as a failed Result — mirrored here by not catching it.
        publishGate?.await()
        published.add(deck to cards)
        decks[deck.id] = deck
        val progress = PublishProgress(1, 1, cards.size, cards.size, done = true)
        progressReports.add(progress)
        onProgress(progress)
        _changes.tryEmit(Unit)
        return Result.success(deck)
    }

    override suspend fun updateMetadata(deck: Deck): Result<Deck> {
        decks[deck.id] = deck
        _changes.tryEmit(Unit)
        return Result.success(deck)
    }

    /** When set, every [delete] fails — a homeserver that went away mid-sweep. */
    var deleteError: Throwable? = null

    override suspend fun delete(deckId: String): Result<Unit> {
        deleted.add(deckId)
        deleteError?.let { return Result.failure(it) }
        decks.remove(deckId)
        _changes.tryEmit(Unit)
        return Result.success(Unit)
    }

    /** How often the deck list was re-read — a review must not cost one (#102). */
    var listOwnedCount = 0
        private set

    override suspend fun listOwned(): List<Deck> {
        listOwnedCount++
        listOwnedGate?.await()
        listOwnedError?.let { throw it }
        return decks.values.toList()
    }

    override suspend fun listByAuthor(authorPubky: String): List<Deck> =
        decks.values.filter { it.authorPubky == authorPubky }

    override suspend fun sync(deckId: String): Result<Deck> =
        decks[deckId]?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("deck $deckId not found"))

    val upsertedCards = mutableListOf<Pair<String, Card>>()
    val deletedCards = mutableListOf<Pair<String, String>>()
    var upsertCardError: Throwable? = null

    override suspend fun upsertCard(deckId: String, card: Card): Result<Deck> {
        upsertCardError?.let { return Result.failure(it) }
        upsertedCards.add(deckId to card)
        val deck = decks[deckId] ?: return Result.failure(IllegalStateException("deck $deckId not found"))
        val updated = deck.copy(cardCount = deck.cardCount + 1)
        decks[deckId] = updated
        _changes.tryEmit(Unit)
        return Result.success(updated)
    }

    /** Batches handed to [appendCards], so a caller can be shown to send one rather than a loop. */
    val appendedBatches = mutableListOf<Pair<String, List<Card>>>()
    var appendCardsError: Throwable? = null

    override suspend fun appendCards(deckId: String, cards: List<Card>): Result<Deck> {
        appendCardsError?.let { return Result.failure(it) }
        appendedBatches.add(deckId to cards)
        val deck = decks[deckId] ?: return Result.failure(IllegalStateException("deck $deckId not found"))
        val updated = deck.copy(cardCount = deck.cardCount + cards.size)
        decks[deckId] = updated
        _changes.tryEmit(Unit)
        return Result.success(updated)
    }

    override suspend fun deleteCard(deckId: String, cardId: String): Result<Deck> {
        deletedCards.add(deckId to cardId)
        val deck = decks[deckId] ?: return Result.failure(IllegalStateException("deck $deckId not found"))
        val updated = deck.copy(cardCount = (deck.cardCount - 1).coerceAtLeast(0))
        decks[deckId] = updated
        _changes.tryEmit(Unit)
        return Result.success(updated)
    }

    val movedCards = mutableListOf<CardMove>()
    var moveCardError: Throwable? = null

    /**
     * Set to have [moveCard] actually re-stamp the cards' `ord`, so a test can reload the deck and
     * see the new order. Left null when a test only cares that the move was requested.
     */
    var cardRepository: FakeCardRepository? = null

    override suspend fun moveCard(deckId: String, cardId: String, toIndex: Int): Result<Deck> {
        moveCardError?.let { return Result.failure(it) }
        movedCards.add(CardMove(deckId, cardId, toIndex))
        val deck = decks[deckId] ?: return Result.failure(IllegalStateException("deck $deckId not found"))
        cardRepository?.reorder(deckId, cardId, toIndex)
        _changes.tryEmit(Unit)
        return Result.success(deck)
    }

    // ── Follow deck (#33) ────────────────────────────────────────────────

    /** Decks followed rather than owned. Kept apart from [decks] so a test can tell them apart. */
    val followedDecks = mutableMapOf<String, Deck>()
    val seen = mutableListOf<String>()
    var followError: Throwable? = null
    var listFollowedError: Throwable? = null

    /** Deck ids whose author has published changes since they were last opened. */
    val updatedDecks = mutableSetOf<String>()

    override suspend fun followDeck(deck: Deck): Result<Unit> {
        followError?.let { return Result.failure(it) }
        followedDecks[deck.id] = deck
        _changes.tryEmit(Unit)
        return Result.success(Unit)
    }

    override suspend fun unfollowDeck(authorPubky: String, deckId: String): Result<Unit> {
        followError?.let { return Result.failure(it) }
        followedDecks.remove(deckId)
        _changes.tryEmit(Unit)
        return Result.success(Unit)
    }

    override suspend fun isFollowingDeck(deckId: String): Boolean = deckId in followedDecks

    /** How often the followed list was read — asserted where it must run beside [listOwned]. */
    var listFollowedCount = 0
        private set

    override suspend fun listFollowed(): List<Deck> {
        listFollowedCount++
        listFollowedError?.let { throw it }
        return followedDecks.values.toList()
    }

    /**
     * What each pubky follows, for [listFollowedBy]. Kept apart from [followedDecks] — that one is
     * the signed-in user's, and a test needs to give a stranger a different set.
     */
    val followedDecksByOwner = mutableMapOf<String, List<Deck>>()
    var listFollowedByError: Throwable? = null

    override suspend fun listFollowedBy(ownerPubky: String): List<Deck> {
        listFollowedByError?.let { throw it }
        return followedDecksByOwner[ownerPubky] ?: emptyList()
    }

    override suspend fun hasUpdate(deckId: String): Boolean = deckId in updatedDecks

    override suspend fun markSeen(deck: Deck) {
        seen.add(deck.id)
        updatedDecks.remove(deck.id)
    }

    val cloned = mutableListOf<Deck>()
    var cloneError: Throwable? = null

    /** Held open so a test can act while a clone is still in flight. */
    var cloneGate: CompletableDeferred<Unit>? = null

    /** Titles handed to [clone], so a test can assert the copy was renamed rather than duplicated. */
    val cloneTitles = mutableListOf<String>()

    override suspend fun clone(source: Deck, title: String): Result<Deck> {
        cloneError?.let { return Result.failure(it) }
        cloneGate?.await()
        cloned.add(source)
        cloneTitles.add(title)
        val copy = source.copy(
            id = "clone-of-${source.id}",
            title = title,
            authorPubky = TEST_PUBKY,
            source = DeckSource(kind = DeckSource.Kind.Clone, uri = source.pubkyUri.value),
        )
        decks[copy.id] = copy
        followedDecks.remove(source.id)
        _changes.tryEmit(Unit)
        return Result.success(copy)
    }
}

class FakeCardRepository : CardRepository {
    /** Keyed by deck id, then card id. */
    val cards = mutableMapOf<String, MutableMap<String, Card>>()

    /**
     * Cards only [fetchByDeck] can see, keyed by deck id — the test stand-in for records that
     * live on a homeserver but have not been read into the session cache yet.
     */
    val remoteCards = mutableMapOf<String, MutableMap<String, Card>>()

    /** When set, [fetchByDeck] fails (an unreachable homeserver). */
    var fetchError: Throwable? = null

    var fetchCount = 0
        private set

    fun seed(vararg seeded: Card) {
        seeded.forEach { cards.getOrPut(it.deckId) { mutableMapOf() }[it.id] = it }
    }

    /** Seed cards that are only reachable through [fetchByDeck], not through the cache. */
    fun seedRemote(vararg seeded: Card) {
        seeded.forEach { remoteCards.getOrPut(it.deckId) { mutableMapOf() }[it.id] = it }
    }

    override suspend fun listByDeck(deckId: String): List<Card> =
        cards[deckId]?.values?.toList().orEmpty().inStudyOrder()

    override suspend fun fetchByDeck(deck: Deck): Result<List<Card>> {
        fetchCount++
        fetchError?.let { return Result.failure(it) }
        remoteCards[deck.id]?.forEach { (id, card) ->
            cards.getOrPut(deck.id) { mutableMapOf() }[id] = card
        }
        return Result.success(cards[deck.id]?.values?.toList().orEmpty().inStudyOrder())
    }

    override suspend fun get(deckId: String, cardId: String): Card? = cards[deckId]?.get(cardId)

    override suspend fun writeChunk(deckId: String, chunk: Int, cards: List<Card>): Result<Unit> {
        writtenChunks.add(deckId to chunk)
        val deckCache = this.cards.getOrPut(deckId) { mutableMapOf() }
        cards.forEach { deckCache[it.id] = it }
        return Result.success(Unit)
    }

    /**
     * Slices this deck's cards the way the manifest's chunk table says they are stored, so a test
     * of a paged reader sees page boundaries rather than the whole deck on every read. A deck with
     * no chunk table has no boundaries to honour and comes back whole.
     */
    override suspend fun readChunk(deck: Deck, chunk: Int): Result<List<Card>> {
        readChunks.add(deck.id to chunk)
        readChunkError?.let { return Result.failure(it) }
        val all = cards[deck.id]?.values?.toList().orEmpty().inStudyOrder()
        val ordered = deck.chunks.sortedBy { it.n }
        if (ordered.isEmpty()) return Result.success(all)
        var start = 0
        for (meta in ordered) {
            if (meta.n == chunk) return Result.success(all.drop(start).take(meta.count))
            start += meta.count
        }
        return Result.success(emptyList())
    }

    /** Re-stamp `ord` so [cardId] sits at [toIndex] of this deck's study order. */
    fun reorder(deckId: String, cardId: String, toIndex: Int) {
        val deckCards = cards[deckId] ?: return
        val ordered = deckCards.values.toList().inStudyOrder().toMutableList()
        val from = ordered.indexOfFirst { it.id == cardId }
        if (from < 0) return
        val card = ordered.removeAt(from)
        ordered.add(toIndex.coerceIn(0, ordered.size), card)
        ordered.forEachIndexed { index, moved -> deckCards[moved.id] = moved.copy(ord = ordForIndex(index)) }
    }

    override suspend fun chunkOf(deckId: String, cardId: String): Int? =
        if (cards[deckId]?.containsKey(cardId) == true) 0 else null

    override suspend fun evict(deckId: String, cardId: String) {
        cards[deckId]?.remove(cardId)
    }

    val writtenChunks = mutableListOf<Pair<String, Int>>()

    /** (deckId, chunk) pairs [readChunk] was asked for, in order — the paging assertion surface. */
    val readChunks = mutableListOf<Pair<String, Int>>()

    /** When set, [readChunk] fails (a chunk record that will not load). */
    var readChunkError: Throwable? = null
}

/** One [FakeDeckRepository.moveCard] call. */
data class CardMove(val deckId: String, val cardId: String, val toIndex: Int)

/**
 * In-memory [PendingReviewStore]. Shared between two repo instances in a test, it stands in for
 * the journal outliving the process.
 */
class FakePendingReviewStore : PendingReviewStore {
    var entries: List<PendingReview> = emptyList()
        private set

    val saves = mutableListOf<List<PendingReview>>()

    /** Pre-load a journal, as a previous process — or another account — would have left one. */
    fun seed(vararg entries: PendingReview) {
        this.entries = entries.toList()
    }

    override suspend fun load(): List<PendingReview> = entries

    override suspend fun save(entries: List<PendingReview>) {
        this.entries = entries
        saves.add(entries)
    }
}

/** Grades through the real scheduler so VM tests see realistic state transitions. */
class FakeSrsRepository : SrsRepository {
    var due: List<Card> = emptyList()
    val reviews = mutableListOf<Pair<Card, SrsGrade>>()
    val states = mutableMapOf<String, SrsState>()

    private val _changes = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val changes: SharedFlow<String> = _changes.asSharedFlow()

    private val _flushFailures = MutableSharedFlow<ErrorReason>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val flushFailures: SharedFlow<ErrorReason> = _flushFailures.asSharedFlow()

    /** Drive the study screen's sync warning without a real failing homeserver. */
    fun emitFlushFailure(reason: ErrorReason) {
        _flushFailures.tryEmit(reason)
    }

    /**
     * Deck ids this fake's "cache" has been asked about, mirroring `SrsRepositoryImpl.deckAuthors`.
     * [dueCountsCached] reports a real zero for these and says nothing at all about the rest, which
     * is the distinction its callers depend on.
     */
    private val knownDecks = mutableSetOf<String>()

    /** cardId → deckId, learned the same way the real repo learns it: from the write. */
    private val stateDecks = mutableMapOf<String, String>()

    override suspend fun dueToday(): List<Card> {
        knownDecks += due.map { it.deckId }
        return due
    }

    override suspend fun dueForDeck(deckId: String): List<Card> {
        knownDecks += deckId
        return due.filter { it.deckId == deckId }
    }

    var nextDue: Long? = null
    override suspend fun nextDueAt(): Long? = nextDue
    override suspend fun stateFor(deckId: String, cardId: String): SrsState? = states[cardId]

    override suspend fun statesForDeck(deckId: String): Map<String, SrsState> =
        states.filterKeys { stateDecks[it] == deckId }

    /**
     * Same rule the real repository uses: a queued card with review state is due, one without has
     * never been seen. Tests that want a *due* card therefore have to seed [states] for it — which
     * is the distinction the production code now turns on.
     */
    private fun countsFor(cards: List<Card>) = DeckCounts(
        due = cards.count { states[it.id] != null },
        new = cards.count { states[it.id] == null },
    )

    override suspend fun countsForDeck(deckId: String): DeckCounts {
        knownDecks += deckId
        return countsFor(due.filter { it.deckId == deckId })
    }

    /**
     * [decks] is a **scope**, not a hint: the real implementation answers for exactly the decks it
     * is handed. Unioning them into [knownDecks] and answering for all of those instead is a fake
     * answering politely — it made a caller that passed the wrong set indistinguishable from one
     * that passed the right set, which is the only thing a test here can check.
     */
    override suspend fun countsToday(decks: List<Deck>?): Map<String, DeckCounts> {
        val scope = decks?.map { it.id } ?: (knownDecks + due.map { it.deckId })
        return scope.associateWith { deckId -> countsFor(due.filter { it.deckId == deckId }) }
    }

    override suspend fun mastery(deckId: String, cardIds: List<String>): DeckMastery? {
        if (masteryUnavailable) return null
        if (cardIds.isEmpty()) return null
        val threshold = studySettings.maturityThresholdDays
        val forDeck = statesForDeck(deckId)
        return DeckMastery(
            share = masteryShare(cardIds, forDeck, threshold),
            isComplete = isFullyMastered(cardIds, forDeck, threshold),
        )
    }

    /** Drives the "the review state could not be read" branch, which must render "—", not 0%. */
    var masteryUnavailable = false

    private val _dailyProgress = MutableStateFlow(DailyStudyProgress())
    override val dailyProgress: StateFlow<DailyStudyProgress> = _dailyProgress.asStateFlow()

    override suspend fun refreshDailyProgress() = Unit

    override suspend fun markGoalCelebrated() {
        _dailyProgress.update { it.copy(goalCelebrated = true) }
    }

    /** Put today's tally where a test needs it, e.g. one card short of the goal. */
    fun setDailyProgress(newCards: Int = 0, reviews: Int = 0, goalCelebrated: Boolean = false) {
        _dailyProgress.value = DailyStudyProgress(
            dayIndex = 0,
            newCards = newCards,
            reviews = reviews,
            goalCelebrated = goalCelebrated,
        )
    }

    /**
     * Mark [cardIds] as already graded in [deckId], so the counters read them as **due** rather
     * than new.
     *
     * Seeding a card into [due] alone now makes it a *new* card, which is the whole point of the
     * split — a test that means "overdue" has to say so.
     */
    suspend fun seedDue(deckId: String, vararg cardIds: String) {
        cardIds.forEach { id ->
            upsert(
                deckId,
                SrsState(
                    cardId = id,
                    dueAt = 0L,
                    intervalDays = 3,
                    easeFactor = 2.5,
                    repetitions = 1,
                    lastGrade = SrsGrade.Good,
                ),
            )
        }
    }

    /** Lets a test move the maturity line the way a user changing their intervals would. */
    var studySettings: StudySettings = StudySettings.Default

    override suspend fun dueCountsCached(): Map<String, DeckCounts> {
        val byDeck = due.groupingBy { it.deckId }.eachCount()
        return (knownDecks + byDeck.keys).associateWith { deckId ->
            countsFor(due.filter { it.deckId == deckId })
        }
    }

    override suspend fun review(card: Card, grade: SrsGrade): Result<SrsState> {
        reviews.add(card to grade)
        val wasNew = states[card.id] == null
        _dailyProgress.update {
            it.copy(newCards = it.newCards + if (wasNew) 1 else 0, reviews = it.reviews + 1)
        }
        val next = states[card.id].review(card.id, grade, now = 0L, settings = studySettings)
        upsert(card.deckId, next)
        return Result.success(next)
    }

    /** The reverse half of a pair: schedules from [base], and never touches the daily tally. */
    override suspend fun reviewFrom(
        card: Card,
        base: SrsState?,
        grade: SrsGrade,
    ): Result<SrsState> {
        reviewsFrom.add(Triple(card, base, grade))
        val next = base.review(card.id, grade, now = 0L, settings = studySettings)
        upsert(card.deckId, next)
        return Result.success(next)
    }

    /** Every [reviewFrom] call, so a test can assert what the pair was re-scheduled from. */
    val reviewsFrom = mutableListOf<Triple<Card, SrsState?, SrsGrade>>()

    override suspend fun previewIntervals(card: Card): Map<SrsGrade, String> =
        states[card.id].previewIntervals(card.id, now = 0L, settings = studySettings)

    override suspend fun previewIntervalsFrom(
        card: Card,
        base: SrsState?,
        cap: SrsGrade?,
    ): Map<SrsGrade, String> =
        base.previewIntervals(card.id, now = 0L, settings = studySettings, cap = cap)

    var flushes = 0
        private set

    override suspend fun flush(): Result<Unit> {
        flushes++
        return Result.success(Unit)
    }

    override fun flushAsync() {
        flushes++
    }

    override suspend fun upsert(deckId: String, state: SrsState): Result<Unit> {
        states[state.cardId] = state
        stateDecks[state.cardId] = deckId
        knownDecks += deckId
        _changes.tryEmit(deckId)
        return Result.success(Unit)
    }
}

class FakeDiscoveryRepository : DiscoveryRepository {
    var feed: List<Deck> = emptyList()
    var feedError: Throwable? = null
    val follows = mutableListOf<String>()

    override suspend fun following(): List<String> = follows.toList()
    override suspend fun isFollowing(pubky: String): Boolean = pubky in follows

    /** When set, follow/unfollow fails — the optimistic pill must revert. */
    var followError: Throwable? = null

    override suspend fun followUser(pubky: String): Result<Unit> {
        followError?.let { return Result.failure(it) }
        follows.add(pubky)
        return Result.success(Unit)
    }

    override suspend fun unfollowUser(pubky: String): Result<Unit> {
        followError?.let { return Result.failure(it) }
        follows.remove(pubky)
        return Result.success(Unit)
    }

    /** Announcements written, in order — the assertion surface for the #39 consent prompt. */
    val announcements = mutableListOf<DeckAnnouncement>()
    var announceError: Throwable? = null

    override suspend fun announceDeck(announcement: DeckAnnouncement): Result<PubkyUri> {
        announceError?.let { return Result.failure(it) }
        announcements.add(announcement)
        return Result.success(PubkyUri("pubky://author/pub/pubky.app/posts/0032TESTPOST0"))
    }

    /** The Loopky accounts each follow list resolves to, keyed by whose list it is. */
    var followingByUser: Map<String, List<PubkyIdentity>> = emptyMap()
    var followersByUser: Map<String, List<PubkyIdentity>> = emptyMap()
    var followListError: Throwable? = null

    /** Held open, this lets a test assert the screen renders before the counts land. */
    var followListGate: CompletableDeferred<Unit>? = null

    override suspend fun followingProfiles(pubky: String): List<PubkyIdentity> {
        followListGate?.await()
        followListError?.let { throw it }
        return followingByUser[pubky].orEmpty()
    }

    override suspend fun followerProfiles(pubky: String): List<PubkyIdentity> {
        followListGate?.await()
        followListError?.let { throw it }
        return followersByUser[pubky].orEmpty()
    }

    override suspend fun decksFromFollowing(): List<Deck> {
        feedGate?.await()
        feedError?.let { throw it }
        return feed
    }

    override suspend fun decksByTag(tag: Tag): List<Deck> = feed.filter { tag in it.tags }

    /** Decks reachable only through the indexer, i.e. not from anyone the user follows. */
    var globalDecks: List<Deck> = emptyList()
    var loopkyUsers: List<PubkyIdentity> = emptyList()

    /** What each strip asked for, so a test can pin the cost budget. */
    val globalRequests = mutableListOf<Pair<Tag, Int>>()
    val suggestedRequests = mutableListOf<Int>()

    /**
     * Held open, these let a test assert the other strips still settle while one is in flight —
     * the whole point of loading Discover section by section.
     */
    var globalGate: CompletableDeferred<Unit>? = null
    var feedGate: CompletableDeferred<Unit>? = null
    var peopleGate: CompletableDeferred<Unit>? = null

    /** Exact per-label results, when a test needs finer control than [globalDecks] gives. */
    var globalDecksByTag: Map<Tag, List<Deck>>? = null

    override suspend fun decksByTagGlobal(tag: Tag, limit: Int): List<Deck> {
        globalRequests.add(tag to limit)
        globalGate?.await()
        globalDecksByTag?.let { return it[tag].orEmpty().take(limit) }
        return globalDecks.filter { tag in it.tags || tag == ReservedTags.DECK }.take(limit)
    }

    override suspend fun loopkyUsers(limit: Int): List<PubkyIdentity> = loopkyUsers.take(limit)

    /** Search results, canned per query. */
    var peopleByQuery: Map<String, List<PubkyIdentity>> = emptyMap()
    var decksByQuery: Map<String, List<Deck>> = emptyMap()

    /** What each half of search was asked, so a test can prove a query was — or was not — run. */
    val peopleQueries = mutableListOf<String>()
    val deckQueries = mutableListOf<String>()

    /** Held open, this lets a test assert the screen shows it is searching. */
    var searchGate: CompletableDeferred<Unit>? = null

    override suspend fun searchPeople(query: String, limit: Int): List<PubkyIdentity> {
        peopleQueries.add(query)
        searchGate?.await()
        return peopleByQuery[query].orEmpty().take(limit)
    }

    override suspend fun searchDecks(query: String, limit: Int): List<Deck> {
        deckQueries.add(query)
        searchGate?.await()
        return decksByQuery[query].orEmpty().take(limit)
    }

    /** Mirrors the real union: directory first, then deck authors, minus self and follows. */
    override suspend fun suggestedPeople(seedDecks: List<Deck>, limit: Int): List<PubkyIdentity> {
        suggestedRequests.add(limit)
        peopleGate?.await()
        val directory = loopkyUsers.filterNot { it.pubky in follows }
        val seen = directory.mapTo(mutableSetOf()) { it.pubky }
        val authors = seedDecks.map { it.authorPubky }
            .distinct()
            .filter { it !in follows && seen.add(it) }
            .map { PubkyIdentity(it, displayName = null, avatarUrl = null, bio = null) }
        return (directory + authors).take(limit)
    }
}

class RecordingTagRepository : TagRepository {
    val putTags = mutableListOf<Pair<PubkyUri, Tag>>()
    val removedTags = mutableListOf<Pair<PubkyUri, Tag>>()

    /** Reserved writes are recorded apart so a test can assert Loopky's own index labels. */
    val putReservedTags = mutableListOf<Pair<PubkyUri, Tag>>()
    val removedReservedTags = mutableListOf<Pair<PubkyUri, Tag>>()

    /** When set, every write fails with it — publish and sign-in must survive that. */
    var failWith: Throwable? = null

    override suspend fun putTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> {
        putTags.add(subjectUri to tag)
        return failWith?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override suspend fun removeTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> {
        removedTags.add(subjectUri to tag)
        return failWith?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override suspend fun putReservedTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> {
        putReservedTags.add(subjectUri to tag)
        return failWith?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override suspend fun removeReservedTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> {
        removedReservedTags.add(subjectUri to tag)
        return failWith?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /** Deck topics the indexer would aggregate to; recorded so a test can pin the ask. */
    var deckTags: List<Tag> = emptyList()
    val deckTagRequests = mutableListOf<Pair<Int, Int>>()

    override suspend fun trendingDeckTags(sampleSize: Int, limit: Int): List<Tag> {
        deckTagRequests.add(sampleSize to limit)
        return deckTags.take(limit)
    }

    /** Indexer reads, canned per label. */
    var subjectsByTag: Map<Tag, List<TaggedSubject>> = emptyMap()

    /** Profiles carrying a label — `/v0/search/users/by_tags`. */
    var usersByTag: Map<Tag, List<String>> = emptyMap()

    /** Authors of posts carrying a label — `/v0/search/posts/by_tag/{label}`. */
    var postAuthorsByTag: Map<Tag, List<String>> = emptyMap()

    /**
     * When set, [usersTagged] throws it — the live prod case, where the endpoint 404s on an
     * indexer that predates it and the caller has to fall back rather than read it as "nobody".
     */
    var usersTaggedError: Throwable? = null
    var selfTaggers: Set<String> = emptySet()
    var counts: Map<PubkyUri, Map<Tag, Int>> = emptyMap()

    /** Every indexer read, so a test can pin how often a caller asks for the same thing. */
    val taggedRequests = mutableListOf<Pair<Tag, Int>>()

    override suspend fun taggedSubjects(tag: Tag, limit: Int): List<TaggedSubject> {
        taggedRequests.add(tag to limit)
        return subjectsByTag[tag].orEmpty().take(limit)
    }

    override suspend fun usersTagged(tag: Tag, limit: Int): List<String> {
        usersTaggedError?.let { throw it }
        return usersByTag[tag].orEmpty().take(limit)
    }

    override suspend fun postAuthorsTagged(tag: Tag, limit: Int): List<String> =
        postAuthorsByTag[tag].orEmpty().take(limit)

    /** Every self-tag lookup, so a test can prove a repeated question is answered from a cache. */
    val selfTagChecks = mutableListOf<String>()

    override suspend fun isSelfTagged(pubky: String, tag: Tag): Boolean {
        selfTagChecks.add(pubky)
        return pubky in selfTaggers
    }

    /** How often the indexer was asked — a review must not cost a round trip (#102). */
    var taggerCountsCalls = 0
        private set

    override suspend fun taggerCounts(subjectUri: PubkyUri): Map<Tag, Int> {
        taggerCountsCalls++
        return counts[subjectUri].orEmpty()
    }
}

class FakeImportRepository(var draft: ImportDraft? = null) : ImportRepository {
    var clearCount = 0
    private val triageDecisions = mutableMapOf<Int, TriageDecision>()
    private val rowEdits = mutableMapOf<Int, Pair<String, String>>()

    override fun currentDraft(): ImportDraft? =
        draft?.let { d -> if (rowEdits.isEmpty()) d else d.copy(rows = d.rows.map(::applyEdit)) }

    override suspend fun parse(rawText: String, separator: Separator?): Result<ImportDraft> =
        draft?.let { Result.success(it) } ?: Result.failure(IllegalStateException("no draft"))

    override suspend fun parseBulk(
        rawText: String,
        separator: Separator?,
        suggestedTitle: String?,
    ): Result<ImportDraft> =
        parse(rawText, separator).map { draft ->
            draft.copy(suggestedTitle = suggestedTitle)
                .also { this.draft = it }
                .also(::discardIncompleteRows)
        }

    /** Notes handed to [parseBulkNotes], so a test can assert on what the reader produced. */
    var bulkNotes: List<BulkNote> = emptyList()
        private set

    override suspend fun parseBulkNotes(
        notes: List<BulkNote>,
        suggestedTitle: String?,
        suggestedDescription: String?,
        suggestedTags: List<String>,
        suggestsReverse: Boolean,
    ): Result<ImportDraft> {
        bulkNotes = notes
        val built = ImportDraft(
            rawText = "",
            separator = Separator.Tab,
            rows = notes.mapIndexed { index, note ->
                ParsedRow(
                    index = index,
                    fields = listOf(note.front, note.back),
                    isValid = note.front.isNotBlank() || note.back.isNotBlank(),
                )
            },
            duplicatesCollapsed = 0,
            suggestedTitle = suggestedTitle,
            suggestedDescription = suggestedDescription,
            suggestsReverse = suggestsReverse,
            suggestedTags = suggestedTags,
            structured = true,
        )
        notes.forEachIndexed { index, note ->
            note.frontImage?.let { setRowImage(index, isFront = true, image = it) }
            note.backImage?.let { setRowImage(index, isFront = false, image = it) }
        }
        draft = built
        discardIncompleteRows(built)
        return Result.success(built)
    }

    /**
     * Mirrors the real repository's bulk policy.
     *
     * Without it this fake reports every row as kept, so a test asserting on the summary's skipped
     * count passes against a fake that cannot produce one.
     */
    private fun discardIncompleteRows(draft: ImportDraft) {
        draft.rows.forEach { row ->
            val front = row.fields.getOrElse(0) { "" }.isBlank() &&
                rowImage(row.index, isFront = true) == null
            val back = row.fields.getOrElse(1) { "" }.isBlank() &&
                rowImage(row.index, isFront = false) == null
            if (front || back) setDecision(row.index, TriageDecision.Discard)
        }
    }

    override fun decisions(): Map<Int, TriageDecision> = triageDecisions.toMap()

    override fun setDecision(rowIndex: Int, decision: TriageDecision) {
        triageDecisions[rowIndex] = decision
    }

    override fun updateRow(rowIndex: Int, front: String, back: String) {
        rowEdits[rowIndex] = front to back
    }

    private val rowImages = mutableMapOf<Pair<Int, Boolean>, DraftCardImage>()

    override fun setRowImage(rowIndex: Int, isFront: Boolean, image: DraftCardImage?) {
        if (image == null) rowImages.remove(rowIndex to isFront) else rowImages[rowIndex to isFront] = image
    }

    override fun rowImage(rowIndex: Int, isFront: Boolean): DraftCardImage? = rowImages[rowIndex to isFront]

    override fun keptRows(): List<ParsedRow> =
        draft?.rows
            ?.filter { triageDecisions[it.index] != TriageDecision.Discard }
            ?.map(::applyEdit)
            ?: emptyList()

    /** Mirrors `ImportRepositoryImpl.applyEdit`. A fake that ignores edits tests nothing about them. */
    private fun applyEdit(row: ParsedRow): ParsedRow {
        val (front, back) = rowEdits[row.index] ?: return row
        val fields = row.fields.toMutableList()
        while (fields.size <= BACK_FIELD) fields.add("")
        fields[FRONT_FIELD] = front
        fields[BACK_FIELD] = back
        return row.copy(fields = fields, isValid = front.isNotBlank() || back.isNotBlank())
    }

    override fun clear() {
        clearCount++
        draft = null
        triageDecisions.clear()
        rowEdits.clear()
        rowImages.clear()
    }
}

/** Hands the bytes back untouched: what matters in a test is which blob arrived, not its size. */
class FakeMediaProcessor : MediaProcessor {
    override suspend fun compressImage(
        bytes: ByteArray,
        maxDimension: Int,
        quality: Int,
    ): ProcessedImage = ProcessedImage(bytes = bytes, mime = "image/jpeg", width = 1, height = 1)
}

class FakeMediaRepository : MediaRepository {
    val putImages = mutableListOf<Triple<String, ByteArray, String>>()

    private val _pinnedFetches = MutableSharedFlow<PinnedBlob>(replay = 16, extraBufferCapacity = 16)
    override val pinnedFetches: SharedFlow<PinnedBlob> = _pinnedFetches.asSharedFlow()

    /** Drive the collector under test without going through a real fetch. */
    fun emitPinnedFetch(deckId: String, sha256: String) {
        _pinnedFetches.tryEmit(PinnedBlob(deckId, sha256))
    }

    /** When set, [putImage] fails with this from the [failPutImageFromCall]th call onwards. */
    var failPutImageWith: Throwable? = null

    /** Which upload starts failing, 1-based — so a test can let earlier blobs land first. */
    var failPutImageFromCall: Int = 1

    override suspend fun putImage(deckId: String, bytes: ByteArray, mime: String): Result<MediaRef.Image> {
        putImages.add(Triple(deckId, bytes, mime))
        failPutImageWith?.takeIf { putImages.size >= failPutImageFromCall }
            ?.let { return Result.failure(it) }
        return Result.success(
            MediaRef.Image(
                // Distinct per upload, so a test can tell which blobs a failed publish swept.
                path = "media/fake${putImages.size}.jpg",
                mime = mime,
                sha256 = "fake${putImages.size}",
                width = null,
                height = null,
            ),
        )
    }

    override suspend fun putAudio(deckId: String, bytes: ByteArray, mime: String): Result<MediaRef.Audio> =
        Result.success(MediaRef.Audio(path = "media/fake.m4a", mime = mime, sha256 = "fake", durationMs = null))

    val gets = mutableListOf<Triple<String, String, MediaRef>>()

    /** Blob bytes by sha256, for a test that cares what [get] actually returns. */
    val blobs = mutableMapOf<String, ByteArray>()

    /** When set, [get] fails with this instead of returning bytes. */
    var failGetWith: Throwable? = null

    /** When set, [get] blocks on it — so a test can act while a fetch is in flight. */
    var getGate: CompletableDeferred<Unit>? = null

    override suspend fun get(
        authorPubky: String,
        deckId: String,
        ref: MediaRef,
    ): Result<ByteArray> {
        gets.add(Triple(authorPubky, deckId, ref))
        getGate?.await()
        failGetWith?.let { return Result.failure(it) }
        return Result.success(blobs[ref.sha256] ?: ByteArray(0))
    }

    val rehosts = mutableListOf<Pair<String, MediaRef>>()

    /** When set, the next [rehost] fails with this instead of copying. */
    var failRehostWith: Throwable? = null

    /**
     * Mirrors the real implementation: the copy lands under [deckId] and the ref stops pointing
     * at the origin. An identity pass-through would make any write-back logic driven through this
     * fake invisible.
     */
    override suspend fun rehost(deckId: String, ref: MediaRef): Result<MediaRef> {
        rehosts.add(deckId to ref)
        failRehostWith?.let { return Result.failure(it) }
        if (ref.uri == null) return Result.success(ref)
        return Result.success(
            when (ref) {
                is MediaRef.Image -> ref.copy(uri = null)
                is MediaRef.Audio -> ref.copy(uri = null)
            },
        )
    }

    val deletes = mutableListOf<Pair<String, MediaRef>>()

    override suspend fun delete(deckId: String, ref: MediaRef): Result<Unit> {
        deletes.add(deckId to ref)
        return Result.success(Unit)
    }
}

fun testDraft(vararg pairs: Pair<String, String>): ImportDraft = ImportDraft(
    rawText = pairs.joinToString("\n") { "${it.first} — ${it.second}" },
    separator = Separator.EmDash,
    rows = pairs.mapIndexed { index, (front, back) ->
        ParsedRow(index = index, fields = listOf(front, back), isValid = true)
    },
    duplicatesCollapsed = 0,
)

/**
 * Delegates to a real [CardRepository] but fails once it has written [failAfter] chunks, standing
 * in for a publish that dies part-way through uploading a large deck.
 */
class FailingChunkCardRepository(
    private val delegate: CardRepository,
    private val failAfter: Int = 1,
) : CardRepository by delegate {
    private var written = 0

    override suspend fun writeChunk(deckId: String, chunk: Int, cards: List<Card>): Result<Unit> {
        if (written >= failAfter) return Result.failure(IllegalStateException("upload died"))
        written++
        return delegate.writeChunk(deckId, chunk, cards)
    }
}

/** [AppPreferences] in memory, starting from the production defaults. */
class FakeAppPreferences(
    shareOnPubky: Boolean = true,
    pubkyEnvironment: String = "",
    cachedStudySettings: String = "",
    themeMode: AppTheme = AppTheme.System,
    nameNudgeDismissed: Boolean = false,
    avatarNudgeDismissed: Boolean = false,
) : AppPreferences {
    private val _shareOnPubky = MutableStateFlow(shareOnPubky)
    override val shareOnPubky: Flow<Boolean> = _shareOnPubky.asStateFlow()

    /** The current value, for a test that asserts on it without collecting. */
    val shareOnPubkyValue: Boolean get() = _shareOnPubky.value

    override suspend fun setShareOnPubky(enabled: Boolean) {
        _shareOnPubky.update { enabled }
    }

    private val _pubkyEnvironment = MutableStateFlow(pubkyEnvironment)
    override val pubkyEnvironment: Flow<String> = _pubkyEnvironment.asStateFlow()

    /** The current value, for a test that asserts on it without collecting. */
    val pubkyEnvironmentValue: String get() = _pubkyEnvironment.value

    override suspend fun setPubkyEnvironment(name: String) {
        _pubkyEnvironment.update { name }
    }

    private val _cachedStudySettings = MutableStateFlow(cachedStudySettings)
    override val cachedStudySettings: Flow<String> = _cachedStudySettings.asStateFlow()

    /** The current value, for a test that asserts on it without collecting. */
    val cachedStudySettingsValue: String get() = _cachedStudySettings.value

    override suspend fun setCachedStudySettings(json: String) {
        _cachedStudySettings.update { json }
    }

    private val _themeMode = MutableStateFlow(themeMode)
    override val themeMode: StateFlow<AppTheme> = _themeMode.asStateFlow()

    override suspend fun setThemeMode(theme: AppTheme) {
        _themeMode.update { theme }
    }

    private val _nameNudgeDismissed = MutableStateFlow(nameNudgeDismissed)
    override val nameNudgeDismissed: Flow<Boolean> = _nameNudgeDismissed.asStateFlow()

    /** The current value, for a test that asserts on it without collecting. */
    val nameNudgeDismissedValue: Boolean get() = _nameNudgeDismissed.value

    override suspend fun setNameNudgeDismissed(dismissed: Boolean) {
        _nameNudgeDismissed.update { dismissed }
    }

    private val _avatarNudgeDismissed = MutableStateFlow(avatarNudgeDismissed)
    override val avatarNudgeDismissed: Flow<Boolean> = _avatarNudgeDismissed.asStateFlow()

    /** The current value, for a test that asserts on it without collecting. */
    val avatarNudgeDismissedValue: Boolean get() = _avatarNudgeDismissed.value

    override suspend fun setAvatarNudgeDismissed(dismissed: Boolean) {
        _avatarNudgeDismissed.update { dismissed }
    }
}

/** [SignupRepository] in memory. Counts gate calls, so a test can prove a retry did not re-mint. */
class FakeSignupRepository(pending: PendingSignup? = null) : SignupRepository {
    private val _pending = MutableStateFlow(pending)
    override val pending: Flow<PendingSignup?> = _pending.asStateFlow()

    val stored: PendingSignup? get() = _pending.value

    var clearCount: Int = 0
        private set

    /** How many times a *new* token was requested from Homegate. */
    var mintCount: Int = 0
        private set

    var availability: SignupAvailability = SignupAvailability(
        sms = MethodAvailability.Unknown,
        lightning = MethodAvailability.Unknown,
    )
    var availabilityError: Throwable? = null
    var redeemResult: Result<PendingSignup.Redeemable>? = null
    var sendSmsResult: Result<Unit> = Result.success(Unit)
    var invoiceResult: Result<LnInvoice> = Result.failure(IllegalStateException("no invoice set"))

    /** How many times Homegate was asked which methods are on offer. */
    var availabilityCount: Int = 0
        private set

    override suspend fun availability(): SignupAvailability {
        availabilityCount++
        return availabilityError?.let { throw it } ?: availability
    }

    override suspend fun sendSmsCode(phoneNumber: String): Result<Unit> = sendSmsResult

    override suspend fun redeemSmsCode(phoneNumber: String, code: String): Result<PendingSignup.Redeemable> = mint()

    override suspend fun createInvoice(): Result<LnInvoice> = invoiceResult

    override suspend fun awaitInvoice(invoice: LnInvoice): Result<PendingSignup.Redeemable> = mint()

    override suspend fun redeemInviteCode(code: String): Result<PendingSignup.Redeemable> = mint()

    var resumableInvoice: LnInvoice? = null
    var resumableSmsPhoneNumber: String? = null

    override suspend fun resumableInvoice(): LnInvoice? = resumableInvoice

    override suspend fun resumableSmsPhoneNumber(): String? = resumableSmsPhoneNumber

    override suspend fun clearPending() {
        clearCount++
        _pending.update { null }
    }

    private fun mint(): Result<PendingSignup.Redeemable> {
        mintCount++
        val result = redeemResult ?: Result.failure(IllegalStateException("no redeem result set"))
        result.getOrNull()?.let { granted -> _pending.update { granted } }
        return result
    }
}

/** [SignupTokenStore] in memory — no keychain, and nothing that outlives the test. */
class FakeSignupTokenStore(pending: PendingSignup? = null) : SignupTokenStore {
    private val _pending = MutableStateFlow(pending)
    override val pending: Flow<PendingSignup?> = _pending.asStateFlow()

    /** The current value, for a test that asserts on it without collecting. */
    val stored: PendingSignup? get() = _pending.value

    /** How many times the token was cleared — clearing it wrongly loses the user's payment. */
    var clearCount: Int = 0
        private set

    override suspend fun save(pending: PendingSignup) {
        _pending.update { pending }
    }

    override suspend fun clear() {
        clearCount++
        _pending.update { null }
    }
}

/** [UnsplashKeyStore] in memory — no keystore, and nothing that outlives the test. */
class FakeUnsplashKeyStore(key: String = "") : UnsplashKeyStore {
    private val _key = MutableStateFlow(key)
    override val key: Flow<String> = _key.asStateFlow()

    /** The stored key, for a test that asserts on it without collecting. */
    val storedKey: String get() = _key.value

    override suspend fun save(key: String) {
        _key.update { key }
    }

    override suspend fun clear() {
        _key.update { "" }
    }
}

class FakeBackgroundTasks : BackgroundTasks {
    var scheduled = 0
        private set

    var compactionsScheduled = 0
        private set

    override fun scheduleMediaRehost() {
        scheduled++
    }

    override fun scheduleDeckCompaction() {
        compactionsScheduled++
    }
}

/**
 * In-memory [SettingsRepository]. [origin] is the interesting knob: it is what decides whether
 * [update] is allowed, so a test can reproduce "the record never loaded" without a failing client.
 */
class FakeSettingsRepository(
    settings: StudySettings = StudySettings.Default,
    origin: SettingsOrigin = SettingsOrigin.Remote,
) : SettingsRepository {
    private val _studySettings = MutableStateFlow(StudySettingsSnapshot(settings, origin))
    override val studySettings: StateFlow<StudySettingsSnapshot> = _studySettings.asStateFlow()

    var loads = 0
        private set

    /** When set, [ensureLoaded] leaves the snapshot alone — the offline / unreadable case. */
    var loadFails = false

    override suspend fun ensureLoaded() {
        loads++
        if (loadFails) return
        _studySettings.update { it.copy(origin = SettingsOrigin.Remote) }
    }

    /**
     * The device mirror, as [SettingsRepository.restoreCachedSettings] serves it. Null means this
     * device has none, which is what a first-ever launch has.
     */
    var mirrored: StudySettings? = null

    var mirrorRestores = 0
        private set

    override suspend fun restoreCachedSettings() {
        mirrorRestores++
        val cached = mirrored ?: return
        if (_studySettings.value.origin != SettingsOrigin.Defaults) return
        _studySettings.update { StudySettingsSnapshot(cached.sanitized(), SettingsOrigin.Cached) }
    }

    /** Set the settings directly, as a homeserver record already holding them would. */
    fun setStudySettings(settings: StudySettings) {
        _studySettings.update { it.copy(settings = settings.sanitized()) }
    }

    override suspend fun update(settings: StudySettings): Result<Unit> {
        if (_studySettings.value.origin != SettingsOrigin.Remote) {
            return Result.failure(IllegalStateException("Study settings have not been read yet"))
        }
        _studySettings.update { StudySettingsSnapshot(settings.sanitized(), SettingsOrigin.Remote) }
        return Result.success(Unit)
    }
}

/** In-memory [StudyProgressStore]. */
/**
 * In-memory [DeckCacheStore] that holds the *encoded* payload, exactly as every real
 * implementation does.
 *
 * Storing the objects instead would be the shorter fake and a misleading one: the chunk table is
 * dropped by `encodeDeckCache`, so a fake that skips the round trip hands back a snapshot carrying
 * one — the single thing this store must never do.
 */
class FakeDeckCacheStore(stored: CachedDecks? = null) : DeckCacheStore {
    private var payload: String? = stored?.let { encodeDeckCache(TEST_PUBKY, it) }
    val saved = mutableListOf<CachedDecks>()

    override suspend fun load(ownerPubky: String): CachedDecks? = decodeDeckCache(payload, ownerPubky)

    override suspend fun save(ownerPubky: String, decks: CachedDecks) {
        payload = encodeDeckCache(ownerPubky, decks)
        saved.add(decks)
    }

    override suspend fun clear() {
        payload = null
        cleared = true
    }

    /** So a test can tell "cleared" from "saved empty" — the distinction the eraser now draws. */
    var cleared = false
        private set
}

class FakeStudyProgressStore(private var stored: DailyStudyProgress? = null) : StudyProgressStore {
    val saved = mutableListOf<DailyStudyProgress>()

    /** Whose tally [stored] is; defaults to the standard test pubky. */
    var storedOwner: String = TEST_PUBKY

    override suspend fun load(ownerPubky: String): DailyStudyProgress? =
        stored?.takeIf { storedOwner == ownerPubky }

    override suspend fun save(ownerPubky: String, progress: DailyStudyProgress) {
        stored = progress
        storedOwner = ownerPubky
        saved.add(progress)
    }
}
