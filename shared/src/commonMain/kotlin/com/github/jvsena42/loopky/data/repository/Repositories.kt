package com.github.jvsena42.loopky.data.repository

import com.github.jvsena42.loopky.data.anki.BulkNote
import com.github.jvsena42.loopky.data.homegate.LnInvoice
import com.github.jvsena42.loopky.data.homegate.MethodAvailability
import com.github.jvsena42.loopky.data.storage.PendingSignup
import com.github.jvsena42.loopky.data.storage.SignupTokenStore
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.DailyStudyProgress
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.domain.model.DeckCounts
import com.github.jvsena42.loopky.domain.model.DeckMastery
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.ErrorReason
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface IdentityRepository {
    suspend fun currentSession(): Session?
    suspend fun loadPersistedSession(): Session?

    /**
     * Sign out, clearing the session **and** any key Loopky holds.
     *
     * Fails with [UnbackedUpLocalKey] when the key has never been backed up, so the UI must confirm
     * before destroying the only copy of an identity; pass [force] once the user has said yes. The
     * guard is here, not only in the dialog, so no caller can sign out an un-backed-up account.
     */
    suspend fun signOut(force: Boolean = false): Result<SignOutOutcome>

    /**
     * End a session held by its secret alone, without touching anything stored on this machine.
     *
     * The only revocation a `LOOPKY_SESSION` has (#54). [signOut] is wrong for it twice over: there is
     * nothing local to clear, and on a machine with its own stored session it would clear that one.
     */
    suspend fun revokeSession(sessionSecret: String): Result<Unit>

    /**
     * Who holds the key for the signed-in account, and whether it has been backed up. Emits
     * immediately, then on change. Carries no key material, so it is safe in a `UiState`.
     */
    val keyCustody: Flow<KeyCustody>

    /**
     * Derive the pubky for [source] without touching the network. The secret crosses this boundary in
     * one direction only, as part of [KeySource], and never comes back out.
     */
    suspend fun derivePubky(source: KeySource): Result<String>

    /**
     * Ask the DHT whether [pubky] has a homeserver account.
     *
     * **Call on explicit submit only** — a lookup per completed phrase makes the restore screen an
     * enumeration oracle. "No account" is [HomeserverLookup.NoRecord], a value, never a throw.
     */
    suspend fun lookupHomeserver(pubky: String): HomeserverLookup

    /**
     * Sign in with a key Loopky derives from [source], persisting both the key and the session.
     *
     * Deliberately does **not** run [lookupHomeserver] first — that would hide a DHT outage inside a
     * sign-in failure.
     *
     * @param knownHomeserver the answer a caller already has. The grant flow's session JSON carries no
     *   `homeserver` field, so without this it costs a second DHT round trip (~3s on device).
     */
    suspend fun signInWithKey(source: KeySource, knownHomeserver: String? = null): Result<Session>

    /**
     * Mint a key inside the FFI, register it against [homeserverPubky] with [signupToken], and sign in.
     *
     * Never falls back to weaker entropy: a key failing its own round-trip validation is terminal (see
     * `KeyMinting`). Asserts the returned session's pubky is the key we minted before returning.
     */
    suspend fun createLocalAccount(homeserverPubky: String, signupToken: String): Result<LocalAccount>

    /**
     * Register the key Loopky **already holds** — from a restore, or a mint whose `signUp` failed.
     *
     * Never mints. Ring's signup deeplink hardcodes minting, so redeeming through it for a pubky the
     * user already has registers a *different* identity and leaves theirs account-less forever.
     */
    suspend fun registerHeldKey(homeserverPubky: String, signupToken: String): Result<Session>

    /**
     * Store the key [source] derives, marked as having no account, and return its pubky. Without it
     * [registerHeldKey] has nothing to register.
     */
    suspend fun holdKeyForRegistration(source: KeySource): Result<String>

    /**
     * Drop a key held only so it could be registered, when the user walks away instead.
     *
     * **Only a restored key** — one with an account, or one this app minted, is untouched; a minted
     * key exists nowhere else. Otherwise an abandoned signup leaves an orphan secret in the keystore
     * that every later launch reports as `KeyCustody.Loopky` and offers to whoever signs in next.
     *
     * Not a suspend function: the caller is a ViewModel's `onCleared`, which runs after
     * `viewModelScope` is cancelled, so a suspending version was measured never to run.
     */
    fun discardUnregisteredKey()

    /**
     * Two-step Pubky Ring sign-in: [beginSignIn] returns the auth URL for the caller to hand to the
     * OS, then [AuthFlowHandle.complete] awaits approval over the relay and persists the session.
     *
     * @param returnToApp appends Ring's `x-success`/`x-cancel`/`x-error` callbacks. **A headless
     *   client must pass false** (#54): a dangling `x-success` bounces a desktop login into the mobile
     *   app. It changes nothing about how the session arrives.
     */
    suspend fun beginSignIn(
        capabilities: String = DEFAULT_CAPABILITIES,
        returnToApp: Boolean = true,
    ): Result<AuthFlowHandle>

    /**
     * Take a session secret handed in from outside — `LOOPKY_SESSION` in a container — and make it
     * this process's session. Suspends because only `revalidateSession` can supply the pubky,
     * homeserver and capabilities the secret does not carry.
     *
     * Deliberately **not persisted**: writing it out would leave a container's credential behind on a
     * machine whose own stored session it was standing in for.
     */
    suspend fun adoptSession(sessionSecret: String): Result<Session>

    /**
     * Ring-mediated **sign-up**: the same relay handshake as [beginSignIn], but the deeplink asks Ring
     * to mint a key and redeem [signupToken] against [homeserverPubky] first.
     *
     * @param homeserverPubky the homeserver the token was issued *for*, never a configured default —
     *   a token spent against the wrong one is rejected and, being single-use, is gone. Retrying with
     *   the same token is the intended recovery; Ring re-uses the key it minted against it.
     *
     * **No production caller and unproven.** Signup redeems locally now ([createLocalAccount]); this
     * is kept because bringing the Ring path back is a live option (Architecture.md §7.8), but nothing
     * has driven it against a real Ring since. The tests below prove only the string it produces.
     */
    suspend fun beginSignUp(
        homeserverPubky: String,
        signupToken: String,
        capabilities: String = DEFAULT_CAPABILITIES,
    ): Result<AuthFlowHandle>

    /**
     * Fetch the pubky.app profile for any user (public read). Session-cached, so a deck grid resolves
     * every author without a round trip per tile. Pass [forceRefresh] where staleness would show.
     */
    suspend fun fetchProfile(pubky: String, forceRefresh: Boolean = false): Result<PubkyIdentity>

    /** Update the current user's pubky.app profile via session-authenticated PUT. */
    suspend fun updateProfile(name: String?, bio: String?): Result<PubkyIdentity>

    /**
     * Erase everything Loopky has written for the signed-in user, then sign out.
     *
     * **This does not delete the Pubky account, and nothing may tell the user it does.** The keypair
     * and homeserver account belong to Ring; [PubkyClient] has no account-delete primitive.
     *
     * What goes: every owned deck via [DeckRepository.delete] (reused so the per-deck lock and tag
     * records are handled), everything else under `/pub/loopky/`, the `loopky-user` self-tag in the
     * **pubky.app** namespace (the only thing listing this account in Loopky's directory), and
     * announcement posts embedding this user's decks.
     *
     * Deliberately **kept**: any unspent signup token — it was paid for, never expires, and still
     * redeems against a new account. Deliberately **untouched**: `profile.json` and pubky.app follows,
     * which are the user's presence in the wider network and not Loopky's to erase.
     *
     * Each homeserver step is best-effort so one 404 cannot strand the rest, but the local wipe and
     * sign-out happen only if the sweep ran to the end.
     *
     * @param onProgress `(done, total)`, coarse and advisory — the sweep may find more than `total`.
     */
    suspend fun deleteAccount(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result<Unit>

    companion object {
        const val DEFAULT_CAPABILITIES = "/pub/loopky/:rw,/pub/pubky.app/:rw"
    }
}

/**
 * What a sign-out actually managed to do. The local half always happens; the **remote** half is a
 * homeserver call that can fail, and reporting success while the bearer token is still live tells
 * the user the opposite of the truth.
 */
data class SignOutOutcome(
    /** True when the homeserver confirmed the session is dead. False when there was none. */
    val revokedRemotely: Boolean,
    /** Whether there was a session at all. Sign-out is idempotent; saying so is not. */
    val hadSession: Boolean,
    /**
     * True when the stored session is gone from this device.
     *
     * The counterpart to [revokedRemotely], and it exists for the same reason: a caller must not
     * say "signed out" over a credential that is still there. False is possible wherever the
     * store can refuse — the macOS Keychain can, where a file cannot (#213) — and it is the more
     * alarming of the two when [revokedRemotely] is also false, because then the token is both
     * live and still on the machine.
     */
    val clearedLocally: Boolean,
)

/**
 * Getting a locally-held key somewhere that survives losing this device. Separate from
 * [IdentityRepository] because nothing here produces or consumes a [Session]. Every method touches
 * the secret, so nothing here logs and nothing returns key material that is not immediately bound
 * for a `FLAG_SECURE` screen or the platform's own share sheet.
 */
interface KeyBackupRepository {

    /** Who holds the key and what has been done about it. Carries no secret. */
    val custody: Flow<KeyCustody>

    /**
     * The twelve words, for a screenshot-blocked screen. Fails for a key restored from a recovery
     * file — BIP-39 runs one way, so it genuinely has no phrase and the UI must not offer one.
     */
    suspend fun revealRecoveryPhrase(): Result<String>

    /**
     * A quiz over the phrase: a few positions, four candidates each. Derived fresh each time rather
     * than stored, so the answers cannot be read out of anything.
     */
    suspend fun buildPhraseQuiz(): Result<PhraseQuiz>

    /**
     * An encrypted recovery file for [passphrase], as the FFI's Base64. The bytes written to disk
     * must be the **decoded** form — what pubky-app writes and Ring reads — so the platform layer
     * doing the saving owns that step.
     */
    suspend fun createRecoveryFile(passphrase: String): Result<RecoveryFileBlob>

    /**
     * A `pubkyring://` deeplink importing this key into Ring. Never log it and never stage it in a
     * clipboard: it carries the phrase in a URL. Hand it straight to the OS.
     */
    suspend fun ringExportUrl(): Result<String>

    /** Additive: having written the words down does not retire the file. */
    suspend fun markBackedUp(method: BackupMethod)
}

/** One question in the confirm quiz: which word belongs at [position] (1-based, as displayed). */
data class PhraseQuizQuestion(
    val position: Int,
    val options: List<String>,
    val answer: String,
)

data class PhraseQuiz(val questions: List<PhraseQuizQuestion>)

/** An encrypted recovery file, Base64 as the FFI returns it, plus the name to offer for it. */
data class RecoveryFileBlob(val base64: String, val fileName: String)

/** [chunksWritten] counts card chunks only; the manifest write is reported by [done]. */
data class PublishProgress(
    val chunksWritten: Int,
    val totalChunks: Int,
    val cardsWritten: Int,
    val totalCards: Int,
    val done: Boolean = false,
) {
    /** 0f..1f, reserving the last slice for the manifest write. */
    val fraction: Float
        get() = when {
            done -> 1f
            totalChunks <= 0 -> 0f
            else -> chunksWritten.toFloat() / (totalChunks + 1).toFloat()
        }
}

/**
 * A Pubky Ring authorisation in flight — two phases because an OS handoff sits between them.
 *
 * **The local-key paths deliberately do not use this**: they have no deeplink and no relay poll,
 * so an `authUrl` would be a field they have to lie about.
 */
interface AuthFlowHandle {
    val authUrl: String
    suspend fun complete(): Result<Session>
}

/**
 * A library split the way the screens show it — see [DeckRepository.listCached].
 *
 * The two lists are kept apart rather than merged because the library badges a followed deck and
 * Profile counts only what you own; a caller that wants the study queue merges them itself,
 * `distinctBy { it.id }`, since cloning a deck you follow puts the same id in both.
 */
data class CachedDecks(val owned: List<Deck>, val followed: List<Deck>)

/**
 * Deck persistence against the Pubky homeserver (canonical) and the local cache.
 *
 * Layout (Architecture.md §8.0):
 * ```
 * /pub/loopky/decks/{deckId}/manifest.json     — metadata + chunk table, no card index
 * /pub/loopky/decks/{deckId}/cards/{n}.json    — up to CHUNK_SIZE cards per record
 * /pub/loopky/decks/{deckId}/media/{sha256}.{ext}
 * ```
 */
@Suppress("TooManyFunctions")
interface DeckRepository {
    /**
     * Emits after every local mutation so deck lists can reload. Publish and delete happen on their
     * own destinations while the tab pages stay composed behind them, so without this a freshly
     * published deck does not appear until the process restarts.
     */
    val changes: SharedFlow<Unit>

    suspend fun getLocal(id: String): Deck?
    suspend fun fetchRemote(authorPubky: String, deckId: String): Result<Deck>
    suspend fun publish(deck: Deck, cards: List<Card>): Result<Deck>

    /**
     * [publish], reporting progress. A 20k-card import is ~201 uploads; a spinner cannot say how
     * far along that is. [onProgress] runs on the publishing coroutine, so keep it cheap.
     */
    suspend fun publish(
        deck: Deck,
        cards: List<Card>,
        onProgress: (PublishProgress) -> Unit,
    ): Result<Deck>

    /**
     * Write a deck's metadata without touching its cards — the cheap save for a rename, cover or
     * tag edit. Reconciles tag records too: they live apart from the manifest, so skipping them
     * would leave a dropped label indexed and a new one invisible (#47).
     */
    suspend fun updateMetadata(deck: Deck): Result<Deck>
    suspend fun delete(deckId: String): Result<Unit>

    /**
     * Add or replace a single card, rewriting only its chunk and patching that chunk's manifest
     * entry — ~63 KB against ~1.5 MB when the manifest carried every card. A new card appends to
     * the last chunk with room.
     */
    suspend fun upsertCard(deckId: String, card: Card): Result<Deck>

    /**
     * Append [cards] to the end of the deck, chunk by chunk rather than card by card.
     *
     * Looping [upsertCard] costs a chunk write **plus** a whole-manifest read-modify-write per
     * card, so it climbs with deck size: 60 writes for 30 cards where [publish] spends 2. That
     * made `import --resume` slower than the attempt that ran out of session (#165) — the recovery
     * mechanism made the failure it exists for more likely.
     *
     * **New cards only.** Ids already in the deck are rejected rather than duplicated; replacing a
     * card is [upsertCard].
     */
    suspend fun appendCards(deckId: String, cards: List<Card>): Result<Deck>

    /** Remove a single card, rewriting its chunk and patching the manifest. */
    suspend fun deleteCard(deckId: String, cardId: String): Result<Deck>

    /**
     * Move [cardId] to study position [toIndex] (0-based over the whole deck), rewriting only the
     * chunk it leaves and the one it lands in.
     *
     * Order travels on [Card.ord] and each chunk owns a private slice of the ord line, so a move
     * costs at most two chunk writes whatever the deck's size — the editor used to republish the
     * whole deck to move one row (#52). [toIndex] is read against the deck **without** the moved
     * card. Out of range clamps; a card the deck does not contain is a no-op.
     */
    suspend fun moveCard(deckId: String, cardId: String, toIndex: Int): Result<Deck>

    /**
     * Copy the blob [sha256] under [deckId]'s own media path and rewrite every ref carrying it, so
     * a clone stops depending on the original author's copy.
     *
     * Driven by [MediaRepository.pinnedFetches], so the bytes are already in hand. Rewriting the
     * record is the other half: without it the card keeps its `uri` and every session re-copies the
     * same blob.
     *
     * **Cache-only and best-effort** — there is no sha→card index, so finding the card any other
     * way means reading every chunk. Anything not cached is left to the deferred sweep (#53). A
     * no-op for a deck you do not own. A failure leaves a missing origin dangling rather than
     * writing it into the card, because a 404 today may be an outage tomorrow.
     */
    suspend fun rehostBlob(deckId: String, sha256: String): Result<Unit>

    /**
     * Re-host the blobs [rehostBlob] never sees, so a clone becomes fully self-contained (#53), at
     * most [maxChunks] chunks per call from the deck's persisted cursor.
     *
     * Budgeted in **chunks, not blobs**: finding media in ten of 200 chunks still costs 200 reads,
     * so a blob budget would never terminate. Resumable and idempotent — the chunk record is the
     * durable unit, the cursor is patched every [REHOST_MANIFEST_BATCH] chunks, and a re-hosted ref
     * no longer carries a `uri`. A no-op for a deck you do not own or one already
     * [Deck.mediaRehosted].
     */
    suspend fun rehostPendingMedia(
        deckId: String,
        maxChunks: Int = DEFAULT_REHOST_CHUNK_BUDGET,
    ): Result<RehostOutcome>

    /** Decks of yours whose media may still be pinned to another author. What the background job iterates. */
    suspend fun decksPendingRehost(): List<Deck>

    /**
     * Reclaim the holes card deletes leave in the chunk table, at most [maxMerges] per call (#51).
     *
     * Deletes shrink a chunk's `count` rather than resequencing, so a churned deck spreads over far
     * more records than its card count warrants — holes never break correctness, but opening the
     * deck costs a request per chunk. Each merge folds one neighbouring pair and drops the other,
     * so the pass converges; order is preserved inside the landing chunk's own `ord` range.
     *
     * Deliberately **not** on the delete path: it rewrites records followers have cached, and a
     * bulk delete would pay for a merge per card. A no-op for a deck you do not own.
     */
    suspend fun compactDeck(
        deckId: String,
        maxMerges: Int = DEFAULT_COMPACTION_MERGE_BUDGET,
    ): Result<CompactionOutcome>

    /** Answered from the manifests the listing already fetched, so asking costs no extra requests. */
    suspend fun decksPendingCompaction(): List<Deck>
    suspend fun listOwned(): List<Deck>

    /**
     * The library as this device last saw it, with no network call at all — or null before a
     * listing has ever succeeded here.
     *
     * For **first paint only**. A cold start otherwise shows a spinner for as long as a directory
     * listing plus a manifest GET per deck takes, and that is the first thing the user sees every
     * launch. The decks that come back carry no chunk table (see
     * [com.github.jvsena42.loopky.data.storage.DeckCacheStore]), so they may be rendered and never
     * written: anything that acts on one re-reads it through [listOwned] or [fetchRemote] first.
     */
    suspend fun listCached(): CachedDecks?

    /** Public decks for any author (read-only). Powers friend profiles + Discover. */
    suspend fun listByAuthor(authorPubky: String): List<Deck>

    /** Pull-only sync driven by the manifest `updated_at` diff. */
    suspend fun sync(deckId: String): Result<Deck>

    // ── Following someone else's deck (#33) ──────────────────────────────
    //
    // Here rather than on DiscoveryRepository, which owns *user* follows: [listFollowed] returns
    // decks that must merge with [listOwned] behind this one [changes] flow, [sync] resolves a
    // followed deck's author from the subscription record, and DiscoveryRepository already depends
    // on DeckRepository — so putting it there would make the cycle.
    //
    // Following subscribes to the owner's deck: you get their updates and cannot edit it. Copying
    // it into your own account is [clone], the opposite trade.

    /** Subscribe to [deck]. Idempotent: re-following overwrites one record. */
    suspend fun followDeck(deck: Deck): Result<Unit>

    /**
     * Drop the subscription, and only that. Review state stays: it is yours, not the author's, and
     * re-following must not reset your progress (Architecture.md §8.3).
     */
    suspend fun unfollowDeck(authorPubky: String, deckId: String): Result<Unit>

    suspend fun isFollowingDeck(deckId: String): Boolean

    /**
     * Decks you follow, from their authors' homeservers. An unreadable deck is dropped rather than
     * failing the call — an author deleting a deck must not empty your library — but a listing that
     * resolved nothing at all still throws.
     */
    suspend fun listFollowed(): List<Deck>

    /**
     * The decks [ownerPubky] follows, off their own `subscriptions/` records, so a visitor's profile
     * shows what that person studies and not only what they wrote. Same drop rule as [listFollowed].
     *
     * Delegates to [listFollowed] for the signed-in user, keeping the session subscription cache —
     * and a follow made a moment ago that the homeserver listing has not caught up with.
     */
    suspend fun listFollowedBy(ownerPubky: String): List<Deck>

    /** True when [deckId] is followed and its author has published changes since you last opened it. */
    suspend fun hasUpdate(deckId: String): Boolean

    /** No-op for decks you don't follow. */
    suspend fun markSeen(deck: Deck)

    /**
     * Copy [source] into your own account as an independent deck: new deck id, new card ids, you as
     * author, [title] as its name, `source` provenance pointing back.
     *
     * A fork, not a subscription — it never receives the original's later edits, and editing it
     * never touches the original. **New card ids are what keep SRS state from bleeding** between
     * the copies. Media is copied **by reference** (see [absolutizedTo], [MediaRepository.rehost]),
     * so cloning an Anki-sized deck stays instant. Commits through [publish]; unfollows [source].
     *
     * [title] is required and **never defaulted to the source's**: the copy lands in a library that
     * already holds the deck it forked, and two identical titles there are indistinguishable. A
     * blank one fails the call rather than falling back.
     */
    suspend fun clone(source: Deck, title: String): Result<Deck>
}

/**
 * Chunk reads and raw chunk writes — deliberately chunk-shaped rather than card-shaped.
 *
 * Single-card mutations are **not** here: writing a chunk also has to move the manifest's
 * `chunks[n].updated_at` and `card_count`, and splitting those across classes is what let the old
 * per-card layout drift. Use [DeckRepository.upsertCard] / [DeckRepository.deleteCard].
 */
interface CardRepository {
    /** Whatever this session has already loaded or written, in study order. Empty on a cold cache. */
    suspend fun listByDeck(deckId: String): List<Card>

    /**
     * The deck's cards from *its author's* homeserver, refreshing the cache — not limited to decks
     * you own, which is what makes a shared deck browsable. Chunks whose `updated_at` has not moved
     * are not re-fetched. One unreadable chunk keeps its cached cards; failing to read *any* fails
     * the call rather than passing off an empty deck as real.
     */
    suspend fun fetchByDeck(deck: Deck): Result<List<Card>>

    suspend fun get(deckId: String, cardId: String): Card?

    /** Overwrite one chunk record; an empty [cards] deletes it. Caller updates the manifest. */
    suspend fun writeChunk(deckId: String, chunk: Int, cards: List<Card>): Result<Unit>

    /** Read a single chunk of [deck] from its author's homeserver, caching what it finds. */
    suspend fun readChunk(deck: Deck, chunk: Int): Result<List<Card>>

    /**
     * Which chunk holds [cardId], or null if this session hasn't seen it. Recorded as chunks are
     * read or written — without it, locating a card to edit means reading every chunk in the deck.
     */
    suspend fun chunkOf(deckId: String, cardId: String): Int?

    /** Drop a card from the in-memory cache without touching the homeserver. */
    suspend fun evict(deckId: String, cardId: String)
}

/**
 * The approval half of onboarding: obtaining a signup token so a new pubky can be given an account.
 * Owns the in-flight signup as a process singleton, the way [ImportRepository] owns the paste draft.
 *
 * Stops at the token deliberately — redeeming it is [IdentityRepository.beginSignUp], whose
 * completion path is the one sign-in already uses.
 */
interface SignupRepository {
    /**
     * Which approval methods this device can use. Never fails: a method Homegate could not be asked
     * about reports [MethodAvailability.Unknown] and is offered anyway, since hiding the only route
     * into the app because a probe timed out is worse than letting the method explain itself.
     */
    suspend fun availability(): SignupAvailability

    /** Ask Homegate to text a verification code. */
    suspend fun sendSmsCode(phoneNumber: String): Result<Unit>

    /**
     * Exchange an SMS code for a token, persisting it **inside this call**: the user's SMS attempt
     * is already spent, so there must be no window where the token exists only in memory. Same for
     * [awaitInvoice] and [redeemInviteCode].
     */
    suspend fun redeemSmsCode(phoneNumber: String, code: String): Result<PendingSignup.Redeemable>

    /** Create a Lightning invoice to pay for a token. */
    suspend fun createInvoice(): Result<LnInvoice>

    /** Wait for [invoice] to be paid, persisting the resulting token before returning. */
    suspend fun awaitInvoice(invoice: LnInvoice): Result<PendingSignup.Redeemable>

    /**
     * An outstanding invoice to go back to waiting on. Paying happens in another app, so Loopky may
     * be killed for the whole of it; resuming beats issuing a second invoice, which would leave a
     * payment made with nothing listening. Null once expired, discarding the record.
     */
    suspend fun resumableInvoice(): LnInvoice?

    /**
     * The number a code was already texted to. Sending spends one of two verifications per week, so
     * a return must land on the code field with the number intact rather than invite a second one.
     */
    suspend fun resumableSmsPhoneNumber(): String?

    /**
     * Accept a hand-issued invite code. No Homegate call, so there is no homeserver to learn and
     * the configured environment's default is used. Shape is checked locally, so a typo costs no
     * round trip.
     */
    suspend fun redeemInviteCode(code: String): Result<PendingSignup.Redeemable>

    /**
     * The token waiting to be spent. The single source of truth for "a signup is in flight" — a
     * non-null value on a cold start means the user paid before dying somewhere, and should be
     * returned to the hand-off rather than charged twice.
     */
    val pending: Flow<PendingSignup?>

    /** Only on proof it was redeemed — see [SignupTokenStore.clear]. */
    suspend fun clearPending()
}

/** What [SignupRepository.availability] found. */
data class SignupAvailability(
    val sms: MethodAvailability,
    val lightning: MethodAvailability,
)

interface ImportRepository {
    fun currentDraft(): ImportDraft?

    /** [separator] overrides auto-detection (spec §5.2 "tap to change"); null auto-detects. */
    suspend fun parse(rawText: String, separator: Separator? = null): Result<ImportDraft>

    /**
     * [parse] with file-sized limits, for an exported deck rather than a paste. Anki's plain-text
     * export is tab-separated, which spec §6 rule 3 already handles — the same parser, not a second
     * one. What differs is the caps: a 20k-card export is ~2 MB and has no business in the paste box.
     *
     * The result skips swipe-triage for a summary screen; nobody swipes 20,000 cards. Rows missing
     * a side are discarded rather than kept, since there is no triage step to fix them in.
     *
     * [suggestedTitle] prefills the commit screen, travelling on the draft like the rest of this
     * handoff. [parse] takes none, which is what keeps a paste's title empty by construction.
     */
    suspend fun parseBulk(
        rawText: String,
        separator: Separator? = null,
        suggestedTitle: String? = null,
    ): Result<ImportDraft>

    /**
     * [parseBulk] for a source that already knows its own structure. An `.apkg` is typed notes with
     * named fields and media blobs; rendering those to tab-separated text threw away exactly what
     * the file knew (#96). Splitting is the only step this skips — dedupe, caps, truncation
     * reporting and the drop-incomplete policy are the same body.
     *
     * Row images are attached here rather than by the caller because dedupe renumbers rows.
     */
    suspend fun parseBulkNotes(
        notes: List<BulkNote>,
        suggestedTitle: String? = null,
        suggestedDescription: String? = null,
        suggestedTags: List<String> = emptyList(),
        suggestsReverse: Boolean = false,
    ): Result<ImportDraft>

    /** Per-row keep/discard decisions made during triage (default [TriageDecision.Keep]). */
    fun decisions(): Map<Int, TriageDecision>
    fun setDecision(rowIndex: Int, decision: TriageDecision)

    /** Override a draft row's front/back text (triage edit). */
    fun updateRow(rowIndex: Int, front: String, back: String)

    /** `null` clears it. */
    fun setRowImage(rowIndex: Int, isFront: Boolean, image: DraftCardImage?)

    /** The image attached to a draft card side during triage, if any. */
    fun rowImage(rowIndex: Int, isFront: Boolean): DraftCardImage?

    /** The rows kept after triage, with any edits applied. */
    fun keptRows(): List<ParsedRow>

    fun clear()
}

/**
 * Tagging via the pubky-app-specs tag primitive: records on the tagger's homeserver, indexed
 * network-wide by Nexus. Any URI can be a subject. Local tag filtering over visible decks stays on
 * [DiscoveryRepository.decksByTag].
 *
 * The impl picks the record's namespace from the subject, which decides how Nexus indexes it —
 * see [TagRepositoryImpl] and Architecture.md §7.7.
 */
interface TagRepository {
    /** User-authored labels. Fails for [ReservedTags] — those are Loopky's, not the user's. */
    suspend fun putTag(subjectUri: PubkyUri, tag: Tag): Result<Unit>
    suspend fun removeTag(subjectUri: PubkyUri, tag: Tag): Result<Unit>

    /**
     * Write one of Loopky's own index labels, so the reserved namespace has exactly one door a
     * user-entered label can never come through. Fails for anything outside [ReservedTags.ALL].
     * Idempotent: tag ids are content-derived.
     */
    suspend fun putReservedTag(subjectUri: PubkyUri, tag: Tag): Result<Unit>
    suspend fun removeReservedTag(subjectUri: PubkyUri, tag: Tag): Result<Unit>

    /**
     * Topic labels carried by Loopky decks network-wide, most-decks-first — the Discover chip row.
     *
     * Aggregated client-side because deck tags index as *resources* and `/v0/tags/hot` only sees
     * `Post|User` targets (§7.7 point 3). [ReservedTags] labels are excluded; never throws.
     *
     * Sees only the top [sampleSize] decks by tagger count, so a topic living solely on an unpopular
     * deck is invisible — the ceiling of aggregating client-side (#58).
     */
    suspend fun trendingDeckTags(
        sampleSize: Int = DEFAULT_DECK_TAG_SAMPLE,
        limit: Int = DEFAULT_DECK_TAG_LIMIT,
    ): List<Tag>

    /**
     * Loopky subjects carrying [tag] network-wide, most-tagged first — the read that makes a deck
     * findable by someone who follows nobody.
     *
     * Untrusted: anyone can tag any URI. Callers must verify a subject resolves to what the label
     * claims — see [DiscoveryRepository.decksByTagGlobal].
     */
    suspend fun taggedSubjects(tag: Tag, limit: Int = DEFAULT_TAGGED_LIMIT): List<TaggedSubject>

    /**
     * Pubkys whose **profile** carries [tag]; for [ReservedTags.USER] this is the account directory.
     *
     * Throws rather than swallowing, unlike the other indexer reads: "the indexer does not offer
     * this query" and "nobody carries this label" are different answers, and an indexer older than
     * pubky/pubky-nexus#1030 answers 404, which is not evidence about the network (#134).
     *
     * Untrusted: a stranger can label someone else's profile. Confirm with [isSelfTagged].
     */
    suspend fun usersTagged(tag: Tag, limit: Int = DEFAULT_TAGGERS_LIMIT): List<String>

    /**
     * Authors of the posts carrying [tag]. The post tag index is the only one of Loopky's that Nexus
     * admits to the global tag graph (§7.7 point 5), and a post key carries its author, so this
     * costs one request and no post fetches. Empty on indexer failure.
     */
    suspend fun postAuthorsTagged(tag: Tag, limit: Int = DEFAULT_POST_AUTHOR_LIMIT): List<String>

    /** Separates an account announcing itself from someone else labelling it. */
    suspend fun isSelfTagged(pubky: String, tag: Tag): Boolean

    /**
     * Distinct taggers per label on [subjectUri] — "N people follow this deck" without aggregating
     * anything. Empty when unindexed or unreachable. Approximate by nature (indexer lag, spam):
     * fine to display, never to gate on.
     */
    suspend fun taggerCounts(subjectUri: PubkyUri): Map<Tag, Int>

    companion object {
        const val DEFAULT_TAGGED_LIMIT = 30
        const val DEFAULT_TAGGERS_LIMIT = 20

        /** Many posts per author, so it is asked wider than it yields. */
        const val DEFAULT_POST_AUTHOR_LIMIT = 100

        /** One indexer page. The resource stream caps at 100; 50 is broad coverage for one request. */
        const val DEFAULT_DECK_TAG_SAMPLE = 50

        /** Chips that fill a scrollable row without becoming a wall. */
        const val DEFAULT_DECK_TAG_LIMIT = 12
    }
}

/** A URI carrying a tag, as the indexer reports it. */
data class TaggedSubject(
    val uri: PubkyUri,
    /** Capped by the indexer — count with [taggersCount], not `taggers.size`. */
    val taggers: List<String>,
    val taggersCount: Int,
)

/**
 * Social graph + discovery. Follows use the pubky.app native primitive; the repo owns follow/unfollow
 * and feed building. [decksByTag] is a local filter over decks the user can already reach;
 * [decksByTagGlobal] is the indexer-backed one.
 */
interface DiscoveryRepository {
    /** Pubkys the current user follows. */
    suspend fun following(): List<String>
    suspend fun isFollowing(pubky: String): Boolean
    suspend fun followUser(pubky: String): Result<Unit>
    suspend fun unfollowUser(pubky: String): Result<Unit>

    /**
     * Write [announcement] to the user's pubky.app feed as a post, returning the post's URI.
     *
     * **Announcing is not publishing.** The deck is already public; this only tells the user's
     * followers it exists, which is why it is opt-in per action behind [AppPreferences.shareOnPubky]
     * and never a side effect of creating, following or cloning. Fails without writing anything when
     * that is off — the gate is on the write, not a rule three ViewModels each have to remember.
     *
     * A `pubky.app` post rather than a Loopky record because Nexus indexes posts into the global
     * graph; a deck manifest can only be a generic resource, which no cross-app feed reads (§7.7).
     *
     * **Best-effort by contract**: a failure is cosmetic, and callers must never roll the action back.
     */
    suspend fun announceDeck(announcement: DeckAnnouncement): Result<PubkyUri>

    /**
     * The Loopky accounts [pubky] follows, as resolved profiles — filtered to accounts that
     * announced themselves with [ReservedTags.USER], since the follow graph is shared with the whole
     * network and most of it has never opened Loopky.
     *
     * Read from the homeserver rather than the indexer: a follow record lives on the follower's own
     * homeserver, so this direction is first-hand and reflects a follow made seconds ago.
     */
    suspend fun followingProfiles(pubky: String): List<PubkyIdentity>

    /**
     * The Loopky accounts that follow [pubky], same filter as [followingProfiles]. Indexer-backed of
     * necessity — the records live on each follower's homeserver — which makes the input untrusted,
     * so entries are verified before being returned.
     */
    suspend fun followerProfiles(pubky: String): List<PubkyIdentity>

    /**
     * Decks published by people the user follows, newest first. Never the user's own, even if they
     * follow themselves — Library is where those live.
     */
    suspend fun decksFromFollowing(): List<Deck>

    /** Visible decks (following + own) carrying [tag]. */
    suspend fun decksByTag(tag: Tag): List<Deck>

    /**
     * Decks carrying [tag] anywhere on the network, most-tagged first, no follow needed. Entries are
     * verified before being returned: the URI has to be a deck manifest, the tagger its author, and
     * the manifest has to fetch and parse. The signed-in account's own decks are dropped too — not
     * for trust, but because Library already lists them.
     *
     * Pass [ReservedTags.DECK] to browse every Loopky deck.
     */
    suspend fun decksByTagGlobal(
        tag: Tag,
        limit: Int = TagRepository.DEFAULT_TAGGED_LIMIT,
    ): List<Deck>

    /**
     * Accounts that announced themselves as Loopky users, excluding the signed-in one — the
     * suggested-people source for someone who follows nobody. Kept only if the account self-tagged
     * (tagger == subject); a profile that does not resolve downgrades to a bare pubky rather than
     * being dropped, since the self-tag is the proof and `profile.json` is a record signing up never
     * had to write. Requiring it kept 4 of 10 staging candidates.
     *
     * Candidates are unioned from three indexer reads, because no single one sees every account
     * (#134): [TagRepository.usersTagged] (the direct answer, and the only one reaching an account
     * that never published, but not deployed everywhere); deck manifests carrying
     * [ReservedTags.DECK] (free from a read global browse already makes); and
     * [TagRepository.postAuthorsTagged] (reaches someone whose manifest failed to index).
     */
    suspend fun loopkyUsers(
        limit: Int = TagRepository.DEFAULT_TAGGERS_LIMIT,
    ): List<PubkyIdentity>

    /**
     * Loopky accounts matching a typed [query]: a display-name prefix, or the first characters of a
     * pubky. Held to the same self-tag bar as [loopkyUsers] — Nexus indexes every pubky.app profile,
     * so without the filter a common name returns strangers with nothing to do on their screen. A
     * *whole* pubky needs no search and is opened directly. Never throws.
     */
    suspend fun searchPeople(
        query: String,
        limit: Int = DEFAULT_SEARCH_PEOPLE_LIMIT,
    ): List<PubkyIdentity>

    /**
     * Published decks matching a typed [query] by title, tag or author pubky, best match first.
     *
     * Nexus indexes deck *tags*, not titles — nothing crawls a manifest's contents — so this matches
     * against manifests the client has actually fetched: a sample of the network, not the whole of
     * it. A deck outside that sample is not findable until something indexes titles. Never throws.
     */
    suspend fun searchDecks(
        query: String,
        limit: Int = DEFAULT_SEARCH_DECKS_LIMIT,
    ): List<Deck>

    /**
     * People worth showing to someone who follows nobody: the [loopkyUsers] directory, then the
     * authors of [seedDecks] — pass the decks global browse already fetched.
     *
     * The seed authors are not redundant: a published author whose self-tag never got written is
     * dropped by [loopkyUsers] and picked up here. They are already corroborated by a fetched
     * manifest, so one with no profile is kept under a bare pubky rather than dropped.
     *
     * Excludes the signed-in user and anyone already followed.
     */
    suspend fun suggestedPeople(
        seedDecks: List<Deck>,
        limit: Int = DEFAULT_SUGGESTED_PEOPLE_LIMIT,
    ): List<PubkyIdentity>

    companion object {
        /** A horizontal strip; enough to scroll, few enough to resolve quickly. */
        const val DEFAULT_SUGGESTED_PEOPLE_LIMIT = 12

        /** Every candidate costs a self-tag check plus a profile fetch. */
        const val DEFAULT_SEARCH_PEOPLE_LIMIT = 10

        /** Two columns, five rows. */
        const val DEFAULT_SEARCH_DECKS_LIMIT = 10

        /**
         * How many decks the title search matches against. Each is a manifest fetch the first time a
         * session searches, so this is the wait before the first result; later queries reuse it.
         */
        const val SEARCH_DECK_SAMPLE = 30

        /** Below this a query matches nearly everything, and a pubky prefix is not yet a prefix. */
        const val MIN_SEARCH_QUERY_LENGTH = 2

        /**
         * Asked far wider than the directory it feeds: one author publishes many decks, so this
         * collapses hard.
         */
        const val DIRECTORY_DECK_SAMPLE = 100

        /**
         * Every candidate costs an indexer round-trip to decide whether it is a Loopky account, and a
         * long-standing pubky.app account can be followed by hundreds. Beyond this the tail is cut.
         */
        const val MAX_FOLLOW_CANDIDATES = 60

        /**
         * How many followed accounts [decksFromFollowing] asks for decks.
         *
         * Separate from [MAX_FOLLOW_CANDIDATES] despite the matching value: that budget buys indexer
         * queries, this one a pkarr resolution plus a homeserver `list` per author. Since Nexus
         * indexes a single homeserver, an account arriving from the shared pubky.app graph follows
         * hundreds hosted on that same server — so this also keeps opening Discover from becoming a
         * burst against one host. Beyond this the tail is cut, and [decksFromFollowing] logs it.
         */
        const val MAX_FOLLOWED_DECK_AUTHORS = 60
    }
}

// TooManyFunctions: the queue, the counters, the buffer and the flush are one subject with one
// lifecycle; splitting them would only make callers hold two handles to the same cache.
/**
 * Spaced-repetition state, Pubky-backed with an in-memory session cache. Records live at
 * `/pub/loopky/decks/{deckId}/srs/{cardId}.json`. The repo owns grading — the scheduler is invoked
 * here, not in ViewModels.
 */
@Suppress("TooManyFunctions")
interface SrsRepository {
    /**
     * Emits the deck id of every review state write, so screens showing due counts can reload.
     * Studying happens on its own destination while Home and DeckDetail stay composed behind it.
     */
    val changes: SharedFlow<String>

    /**
     * Emits when a background [flushAsync] fails, so the study screen can say so. Without it a full
     * homeserver was silent by construction: the reviews went back into an in-memory dirty set,
     * every retry hit the same 507, and the set died with the process — the user studied a whole
     * session, watched the counters move, and lost it with no error at any point (#91).
     *
     * Replayed, because the failing flush is usually the one started as the screen goes away.
     */
    val flushFailures: SharedFlow<ErrorReason>

    /**
     * The whole study queue across every studiable deck: cards actually due first, soonest first,
     * then never-seen cards.
     *
     * New cards are included but are **not** "due" (see [isNew]). Nothing here is capped by the
     * new-cards-per-day goal; that is a goal, not a limit, and withholding cards is what it must
     * not do.
     */
    suspend fun dueToday(): List<Card>

    /** One deck's study queue, ordered the same way [dueToday] orders the whole of it. */
    suspend fun dueForDeck(deckId: String): List<Card>

    /**
     * Due and new counts for one deck. The halves are separate because one number could not tell
     * "you are behind on 1669 reviews" from "this deck has 1669 cards you have never met" — and it
     * reported the second as the first, which made a fresh import unopenable (#101 §7).
     */
    suspend fun countsForDeck(deckId: String): DeckCounts

    /**
     * [countsForDeck] for every studiable deck, keyed by deck id — the same read [dueToday] performs
     * without materialising a queue of every card just to take its size.
     *
     * [decks] is the library the caller has **already** listed this pass, and passing it is what
     * stops a screen paying for that listing twice: without it this re-lists owned and followed
     * decks itself, and then re-syncs each of their manifests — on Home, the same two listings and
     * the same manifest GET per deck that `load()` had just made, doubling the round trips behind
     * the spinner. Omit it only where the caller has nothing to hand over.
     */
    suspend fun countsToday(decks: List<Deck>? = null): Map<String, DeckCounts>

    /**
     * How far [cardIds] have been carried toward maturity, or null if the state could not be read.
     *
     * Here rather than in the ViewModel because the maturity threshold is a *setting* (see
     * [maturityThresholdDays]) and this repository holds both the states and the settings. Null
     * means "the read failed" and must render as unknown, never as zero.
     */
    suspend fun mastery(deckId: String, cardIds: List<String>): DeckMastery?

    /**
     * When the soonest not-yet-due card comes up, or null if nothing is scheduled. Lets Home say
     * "next review in 4h" instead of showing an empty queue with no explanation.
     */
    suspend fun nextDueAt(): Long?

    /** Cached review state for a card, if it has been loaded this session. */
    suspend fun stateFor(deckId: String, cardId: String): SrsState?

    /**
     * The bulk form of [stateFor]: counting due or mature cards means asking about every card, and
     * doing that one [stateFor] at a time costs a lock acquisition per card.
     */
    suspend fun statesForDeck(deckId: String): Map<String, SrsState>

    /**
     * Due counts per deck id from what is already in memory — **no homeserver read**. For a caller
     * reacting to a review it just made; [dueForDeck] re-syncs the manifest, which is right when
     * opening a screen and badly wrong once per graded card.
     *
     * Empty on a cold cache, so a missing deck means "unknown", never zero.
     */
    suspend fun dueCountsCached(): Map<String, DeckCounts>

    /**
     * Today's study on this device: reviews graded and never-seen cards met, reset at local midnight.
     * Note that nothing in this interface consults [StudySettings.newCardsPerDayGoal] — reaching it
     * is announced, never enforced, and no queue-building method may take it into account.
     */
    val dailyProgress: StateFlow<DailyStudyProgress>

    /** Idempotent; rolls the counters over if the day has turned. */
    suspend fun refreshDailyProgress()

    /**
     * Record that today's goal celebration has been shown. On the repository rather than the
     * ViewModel because a flag held in memory would congratulate the user again on every reopen.
     */
    suspend fun markGoalCelebrated()

    /** Grade a card: compute the next state via the scheduler, persist it, and return it. */
    suspend fun review(card: Card, grade: SrsGrade): Result<SrsState>

    /**
     * [review], but scheduled from [base] rather than the card's current state — the second half of
     * a card studied both ways. The forward direction is graded as it happens, so an abandoned
     * session keeps it; a worse reverse must re-schedule from where the pair *started*, or two
     * reviews come out of one and `repetitions` and the ease penalty are applied twice.
     *
     * Deliberately does not touch the daily tally: the pair is one review of one card.
     */
    suspend fun reviewFrom(card: Card, base: SrsState?, grade: SrsGrade): Result<SrsState>

    /**
     * The interval each grade would produce, formatted for the grade buttons. Here because the
     * intervals are the user's own setting — the VM would otherwise need a [SettingsRepository]
     * purely to label four buttons, and the labels could drift from what [review] writes.
     */
    suspend fun previewIntervals(card: Card): Map<SrsGrade, String>

    /**
     * [previewIntervals] for what [reviewFrom] would write: computed from [base], every grade
     * ceilinged at [cap]. Labels the reverse half of a pair, where the worse direction lands.
     */
    suspend fun previewIntervalsFrom(
        card: Card,
        base: SrsState?,
        cap: SrsGrade?,
    ): Map<SrsGrade, String>

    /**
     * Buffered in memory rather than written immediately — grading 100 cards costs a handful of
     * chunk writes instead of 100 record writes. See [flush].
     */
    suspend fun upsert(deckId: String, state: SrsState): Result<Unit>

    /**
     * Write buffered reviews to the homeserver: at the end of a session, and periodically so a crash
     * costs a few cards rather than all of them. Buffered reviews are mirrored to a device-local
     * journal ([PendingReviewStore]), so a set that cannot be written survives the process; the
     * first successful flush clears it.
     */
    suspend fun flush(): Result<Unit>

    /**
     * [flush] on the repository's own scope. A ViewModel must use this rather than launching a flush
     * itself: `viewModelScope` is cancelled in `onCleared()`, so a flush started as the study screen
     * goes away would be killed before it saved the reviews it exists for.
     */
    fun flushAsync()
}

/** How far one [DeckRepository.rehostPendingMedia] pass got. */
data class RehostOutcome(
    val chunksScanned: Int,
    val rehosted: Int,
    /**
     * Origins that are gone — the author deleted the blob. Counted apart from [failed] because
     * nothing will ever fix them, and letting a 404 block completion would sweep the deck forever.
     */
    val missing: Int,
    /** Transient failures — an outage, a rate limit. These keep the deck pending. */
    val failed: Int,
    /** True when the pass reached the end of the deck with no transient failures. */
    val complete: Boolean,
)

/**
 * Chunks one [DeckRepository.rehostPendingMedia] call reads before returning, so a background run
 * killed partway keeps its progress. Well inside WorkManager's ~10 minute window.
 */
const val DEFAULT_REHOST_CHUNK_BUDGET = 25

/** Chunks processed per manifest patch. The chunk record is the durable unit. */
const val REHOST_MANIFEST_BATCH = 10

/** How far one [DeckRepository.compactDeck] pass got. */
data class CompactionOutcome(
    /** Chunk pairs folded together — also the number of records the deck no longer has. */
    val merges: Int,
    /** Cards rewritten into a neighbouring chunk. */
    val cardsMoved: Int,
    val chunksBefore: Int,
    val chunksAfter: Int,
    /**
     * True when the pass ran out of merges rather than out of budget. False means work is left.
     */
    val complete: Boolean,
)

/**
 * Merges one [DeckRepository.compactDeck] call makes before returning, so a background run killed
 * partway keeps its progress. Each merge costs two chunk reads, a write, a manifest write and a
 * delete, so this sits well inside WorkManager's ~10 minute window.
 */
const val DEFAULT_COMPACTION_MERGE_BUDGET = 20

/**
 * A blob just served from another author's homeserver on a deck the signed-in user owns — a clone
 * that has not re-hosted this one yet.
 */
data class PinnedBlob(val deckId: String, val sha256: String)

/**
 * Blob storage for card image and audio media. Blobs live under the owning deck's Pubky path so
 * they sync with the deck and dedupe by content hash.
 */
interface MediaRepository {

    /**
     * Emits after a successful [get] of a ref still pinned to another author, on a deck the user
     * owns — the moment the blob is worth copying, since the bytes are in hand.
     *
     * Only a signal: persisting the re-hosted ref is [DeckRepository.rehostBlob]'s job, because the
     * card's chunk and the manifest entry have to be written as a pair. **Best-effort** — the
     * deferred sweep (#53) is the backstop, so a dropped signal costs latency, not correctness.
     */
    val pinnedFetches: SharedFlow<PinnedBlob>

    suspend fun putImage(deckId: String, bytes: ByteArray, mime: String): Result<MediaRef.Image>
    suspend fun putAudio(deckId: String, bytes: ByteArray, mime: String): Result<MediaRef.Audio>

    /**
     * Blob bytes for [ref], fetched lazily when a card is displayed.
     *
     * [authorPubky] is the *deck's* author, not the signed-in user. Resolving against the session
     * made media on any deck you don't own unreachable, which also blocked following a deck with
     * images (#33).
     */
    suspend fun get(authorPubky: String, deckId: String, ref: MediaRef): Result<ByteArray>

    /**
     * Copy a blob referenced by absolute `pubky://` uri under [deckId]'s own media path. A no-op for
     * refs already stored locally. The second half of clone-by-reference: cloning stays instant, and
     * the copy happens as blobs are first used.
     */
    suspend fun rehost(deckId: String, ref: MediaRef): Result<MediaRef>

    suspend fun delete(deckId: String, ref: MediaRef): Result<Unit>
}

/**
 * The user's own app settings, held at `/pub/loopky/settings.json`.
 *
 * Synced rather than device-local because study settings change *scheduling*: two devices holding
 * different intervals would write `dueAt`s computed from different rules (Architecture.md §7.5).
 *
 * Reads are non-suspending by design — [SrsRepository.review] consults settings on every grade and
 * must never pay for a network call. [ensureLoaded] warms the flow.
 */
interface SettingsRepository {
    /**
     * The current study settings and, crucially, **where they came from**. Emits immediately with
     * [SettingsOrigin.Defaults] or the offline mirror, then the record. The origin is not a display
     * concern: it is what [update] is gated on.
     */
    val studySettings: StateFlow<StudySettingsSnapshot>

    /**
     * Read the record once per session. Single-flight, and it **never throws** — it sits on the
     * cold-start study path, and a settings read must not take a study session down with it.
     */
    suspend fun ensureLoaded()

    /**
     * Write new settings, to the homeserver and the offline mirror.
     *
     * **Refuses unless the record has actually been read this session.** Without that gate one
     * transient failure plus one tap in Settings would write the built-in defaults over the user's
     * real record. The gate belongs on the write, not only in the ViewModel.
     */
    suspend fun update(settings: StudySettings): Result<Unit>
}

/** Where the settings in hand came from. Decides whether a write is allowed. */
enum class SettingsOrigin {
    /** Built-in values. Nothing has been read; a write now would be guesswork. */
    Defaults,

    /** The device's copy of a record read on an earlier run. Good enough to schedule with. */
    Cached,

    /** The record itself, read this session. The only state in which [update] works. */
    Remote,
}

data class StudySettingsSnapshot(
    val settings: StudySettings = StudySettings.Default,
    val origin: SettingsOrigin = SettingsOrigin.Defaults,
) {
    /** Whether the record is known well enough to be safely overwritten. */
    val isEditable: Boolean get() = origin == SettingsOrigin.Remote
}
