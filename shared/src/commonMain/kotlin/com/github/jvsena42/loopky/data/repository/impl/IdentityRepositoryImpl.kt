package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.MintedKeypair
import com.github.jvsena42.loopky.data.pubky.MutableSessionProvider
import com.github.jvsena42.loopky.data.pubky.ProfileDto
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.asSignupUrl
import com.github.jvsena42.loopky.data.pubky.isNoHomeserverRecord
import com.github.jvsena42.loopky.data.pubky.isNotFound
import com.github.jvsena42.loopky.data.pubky.keypairFromMnemonic
import com.github.jvsena42.loopky.data.pubky.keypairFromSecretKey
import com.github.jvsena42.loopky.data.pubky.mintValidatedKeypair
import com.github.jvsena42.loopky.data.pubky.parseSessionPayload
import com.github.jvsena42.loopky.data.pubky.redactAuthUrl
import com.github.jvsena42.loopky.data.pubky.redactSessionPayload
import com.github.jvsena42.loopky.data.pubky.toDomain
import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.AuthFlowHandle
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.SignOutOutcome
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.storage.KeyOrigin
import com.github.jvsena42.loopky.data.storage.LocalKey
import com.github.jvsena42.loopky.data.storage.LocalKeyStore
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.HomeserverLookup
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.domain.model.KeySource
import com.github.jvsena42.loopky.domain.model.LocalAccount
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.domain.model.UnbackedUpLocalKey
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.encodeUriComponent
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [IdentityRepository] backed by [PubkyClient] and [SecureSessionStore].
 *
 * Sign-in is a two-phase deeplink flow: [beginSignIn] asks the FFI for an auth URL, the caller
 * opens the URL in Pubky Ring, then [AuthFlowHandle.complete] blocks on `awaitAuthApproval` and
 * finalises the session.
 */
internal class IdentityRepositoryImpl(
    private val pubky: PubkyClient,
    private val sessionStore: SecureSessionStore,
    private val sessionProvider: MutableSessionProvider,
    private val tagRepository: TagRepository,
    /**
     * The homeserver sweep behind [deleteAccount]. Injecting it creates no Koin cycle: it reaches
     * `DeckRepository`, which depends on the session *provider* rather than on this repository.
     */
    private val eraser: AccountEraser,
    private val localKeyStore: LocalKeyStore,
    /**
     * Fire-and-forget cleanup that has to outlive its caller — see [discardUnregisteredKey].
     * Injectable because the one thing it runs is a *deletion*, and a test that cannot await it
     * cannot tell "did not delete" from "has not deleted yet".
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) : IdentityRepository {

    override val keyCustody: Flow<KeyCustody> = localKeyStore.custody

    override suspend fun currentSession(): Session? = sessionProvider.current()

    override suspend fun loadPersistedSession(): Session? {
        val persisted = sessionStore.load() ?: return null
        // Heal a session stored before the homeserver was backfilled. Every account signed in
        // through Ring before that fix has a blank one on disk, nothing prompts them to sign in
        // again, and `session_live` reports it as healthy — so the environment guard stays open for
        // exactly those installs. Persisted, so the DHT is asked once rather than on every load.
        val session = persisted.withResolvedHomeserver()
        if (session.homeserver != persisted.homeserver) {
            Log.d(TAG, "loadPersistedSession: backfilled the stored session's homeserver")
            sessionStore.save(session)
        }
        sessionProvider.set(session)
        selfTagAsLoopkyUser(session)
        return session
    }

    override suspend fun signOut(force: Boolean): Result<SignOutOutcome> = runSuspendCatching {
        // Refused rather than warned about in the dialog, so no future caller can sign out silently
        // and take the only copy of an identity with it. The UI catches this and calls back forced.
        val held = localKeyStore.current()
        if (!force && held != null && held.backedUpBy.isEmpty()) {
            throw UnbackedUpLocalKey(held.pubky)
        }

        val current = sessionProvider.current()
        // The revoke can fail and the local clear happens either way, because a user asking to sign
        // out should end up signed out here whatever the network is doing. What must *not* happen is
        // reporting that as success while the bearer token is still live, so the outcome travels back.
        val revokedRemotely = current != null &&
            pubky.signOut(current.sessionSecret)
                .onFailure { Log.w(TAG, "signOut: the homeserver did not confirm revocation", it) }
                .isSuccess
        // Caught rather than propagated: the rest of this teardown must happen whether or not the
        // store gave the credential up, and the caller needs to be *told* rather than left to
        // infer it from an exception that also skipped everything below.
        val clearedLocally = runSuspendCatching { sessionStore.clear() }
            .onFailure { Log.w(TAG, "signOut: the session store would not give the credential up", it) }
            .isSuccess
        // The key goes with the session: a signed-out device holding a secret key is a credential
        // nobody is watching. Safe unguarded *today* because the only keys that exist are restored
        // ones, whose owner demonstrably holds the phrase or file. Minting here (#147 phase 3)
        // introduces the first key nobody has a copy of — that is what the sign-out confirm is for.
        localKeyStore.clear()
        sessionProvider.set(null)
        selfTaggedThisProcess = false
        profileCacheLock.withLock { profileCache.clear() }
        SignOutOutcome(
            revokedRemotely = revokedRemotely,
            hadSession = current != null,
            clearedLocally = clearedLocally,
        )
    }

    override suspend fun revokeSession(sessionSecret: String): Result<Unit> = runSuspendCatching {
        Log.d(TAG, "revokeSession: ending a session held by its secret alone")
        pubky.signOut(sessionSecret).getOrThrow()
        // The provider only: an injected session was never on this machine's disk, and clearing the
        // store here would take a *different*, stored session with it.
        if (sessionProvider.current()?.sessionSecret == sessionSecret) sessionProvider.set(null)
    }.onFailure {
        Log.e(TAG, "revokeSession: FAILED — ${it::class.simpleName}: ${it.message}", it)
    }

    override suspend fun beginSignIn(
        capabilities: String,
        returnToApp: Boolean,
    ): Result<AuthFlowHandle> {
        Log.d(TAG, "beginSignIn: capabilities=$capabilities returnToApp=$returnToApp")
        return pubky.startAuthFlow(capabilities)
            .onSuccess { Log.d(TAG, "beginSignIn: startAuthFlow ok — authUrl=${it.redactAuthUrl()}") }
            .onFailure {
                Log.e(TAG, "beginSignIn: startAuthFlow FAILED — ${it::class.simpleName}: ${it.message}", it)
            }
            .map { authUrl ->
                RingAuthFlowHandle(if (returnToApp) authUrl.withRingCallbacks() else authUrl)
            }
    }

    override suspend fun adoptSession(sessionSecret: String): Result<Session> = runSuspendCatching {
        Log.d(TAG, "adoptSession: revalidating an injected session secret")
        val session = parseSessionPayload(pubky.revalidateSession(sessionSecret).getOrThrow(), loopkyJson)
            // The same blank the deeplink path had — and it matters more here, because an injected
            // session is the one shape a container runs on and `--env` is the only thing telling it
            // which network it is on.
            .withResolvedHomeserver()
        // Provider only, never the store — see IdentityRepository.adoptSession for why.
        sessionProvider.set(session)
        selfTagAsLoopkyUser(session)
        session
    }.onFailure {
        Log.e(TAG, "adoptSession: FAILED — ${it::class.simpleName}: ${it.message}", it)
    }

    override suspend fun beginSignUp(
        homeserverPubky: String,
        signupToken: String,
        capabilities: String,
    ): Result<AuthFlowHandle> {
        Log.d(TAG, "beginSignUp: capabilities=$capabilities homeserver=$homeserverPubky")
        return pubky.startAuthFlow(capabilities)
            // `mapCatching`, not `runSuspendCatching`: `asSignupUrl` is pure and synchronous, so it
            // cannot swallow a cancellation.
            .mapCatching { it.asSignupUrl(homeserverPubky, signupToken) }
            .onSuccess { Log.d(TAG, "beginSignUp: startAuthFlow ok — authUrl=${it.redactAuthUrl()}") }
            .onFailure {
                Log.e(TAG, "beginSignUp: FAILED — ${it::class.simpleName}: ${it.message}", it)
            }
            .map { authUrl -> RingAuthFlowHandle(authUrl.withRingCallbacks()) }
    }

    /**
     * Append Ring return-callback params so Ring re-opens Loopky after approval; the session itself
     * still arrives over the relay. [CALLBACK_URL] is registered in the platform manifest/Info.plist.
     */
    private fun String.withRingCallbacks(): String {
        val separator = if (contains('?')) "&" else "?"
        val cb = encodeUriComponent(CALLBACK_URL)
        return "$this${separator}x-success=$cb&x-cancel=$cb&x-error=$cb&x-source=$CALLBACK_SOURCE"
    }

    /**
     * Single-use: the FFI's auth flow is global state that `awaitAuthApproval` *takes*, so the first
     * poll consumes it and a second can only answer "No auth flow in progress". A failed approval
     * has no in-place retry — recovering means a fresh [beginSignIn], which mints a new secret and
     * so requires the user to approve in Ring again (#59).
     */
    private inner class RingAuthFlowHandle(override val authUrl: String) : AuthFlowHandle {
        override suspend fun complete(): Result<Session> = runSuspendCatching {
            Log.d(TAG, "complete: awaiting Pubky Ring approval")
            val sessionJson = pubky.awaitAuthApproval().getOrThrow()
            Log.d(TAG, "complete: got session payload=${sessionJson.redactSessionPayload()}")

            val session = parseSessionPayload(sessionJson, loopkyJson)
            Log.d(TAG, "complete: parsed session pubky=${session.identity.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
            // Shared with the local-key paths on purpose: everything after "we have a session" is
            // identical, and letting the two drift is how one stops self-tagging and quietly falls
            // out of the only global directory Loopky has.
            persistSession(session.withResolvedHomeserver()).also { Log.d(TAG, "complete: session saved") }
        }.onFailure {
            Log.e(TAG, "complete: FAILED — ${it::class.simpleName}: ${it.message}", it)
        }
    }

    /**
     * Fill in the homeserver the session payload does not carry.
     *
     * The FFI's session JSON has **no `homeserver` field**, so `parseSessionPayload` defaults it to
     * `""`. Every other path backfills it; the Ring deeplink path did not, so a Ring sign-in stored
     * a blank one.
     *
     * That is worse than cosmetic: the CLI refuses to run when a session and `--env` disagree (#54),
     * decided by comparing the session's homeserver against the other environment's default — which
     * a blank never matches, so the guard passed every time on exactly the sessions it was written
     * for. Settings rendering "Unknown" was the visible half.
     *
     * Best-effort: a failed DHT lookup leaves the blank rather than failing the sign-in.
     */
    private suspend fun Session.withResolvedHomeserver(): Session {
        if (homeserver.isNotBlank()) return this
        val resolved = (lookupHomeserver(identity.pubky) as? HomeserverLookup.Registered)
            ?.homeserverPubky
            ?.takeIf { it.isNotBlank() }
        if (resolved == null) {
            Log.w(TAG, "complete: session carries no homeserver and the DHT could not supply one")
            return this
        }
        Log.d(TAG, "complete: resolved homeserver for the session")
        return copy(homeserver = resolved)
    }

    // --- Local keys ------------------------------------------------------------

    override suspend fun derivePubky(source: KeySource): Result<String> =
        deriveKeypair(source).map { it.pubky }

    override suspend fun lookupHomeserver(pubky: String): HomeserverLookup {
        Log.d(TAG, "lookupHomeserver: ${pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
        val result = runSuspendCatching { this.pubky.getHomeserver(pubky).getOrThrow() }
        return result.fold(
            onSuccess = { HomeserverLookup.Registered(it.trim()) },
            onFailure = { error ->
                // The FFI reports "this pubky published no homeserver record" and "the DHT did not
                // answer" through the same call, and only the first is a fact about the key. The
                // classifier ordering in toErrorReason is what keeps them apart.
                if (error.isNoHomeserverRecord()) {
                    Log.d(TAG, "lookupHomeserver: no record for this pubky")
                    HomeserverLookup.NoRecord
                } else {
                    Log.w(TAG, "lookupHomeserver: could not check — ${error::class.simpleName}")
                    // An unclassified failure here is still a *known* one: the only call this
                    // function makes is the DHT lookup, so "we couldn't check that" is honest and
                    // "Something went wrong" is not — it tells a user staring at their own recovery
                    // file nothing to try. Specific reasons still win where the classifier has one.
                    val reason = error.toErrorReason()
                        .takeUnless { it == ErrorReason.Unknown }
                        ?: ErrorReason.HomeserverLookupFailed
                    HomeserverLookup.CouldNotCheck(reason)
                }
            },
        )
    }

    override suspend fun signInWithKey(
        source: KeySource,
        knownHomeserver: String?,
    ): Result<Session> = runSuspendCatching {
        val keypair = deriveKeypair(source).getOrThrow()
        Log.d(TAG, "signInWithKey: signing in as ${keypair.pubky.take(PUBKY_LOG_PREFIX_LEN)}…")

        val payload = pubky.signIn(keypair.secretKeyHex).getOrThrow()
        val session = parseSessionPayload(payload, loopkyJson)

        // The homeserver we landed on is not in the payload, so resolving it here keeps Settings
        // from showing "Unknown" for every restored account. Prefer what the caller already learned:
        // on the restore path the pre-flight resolved this moments ago, and asking the DHT twice
        // costs a second round trip on the critical path.
        val resolved = session.homeserver.takeIf { it.isNotBlank() }
            ?: knownHomeserver
            ?: (lookupHomeserver(keypair.pubky) as? HomeserverLookup.Registered)?.homeserverPubky
            ?: ""

        // The key is persisted only once the homeserver has accepted it. Storing it earlier would
        // leave a device holding a key for an account it could not sign into.
        localKeyStore.save(
            LocalKey(
                secretKeyHex = keypair.secretKeyHex,
                pubky = keypair.pubky,
                mnemonic = keypair.mnemonic,
                // A restored key is already backed up, and recording that is not a shortcut: the
                // user just demonstrated they hold the phrase or file. Nagging them to back up what
                // they only just typed in describes a risk that does not exist. A *minted* key is
                // the un-backed-up case.
                backedUpBy = setOf(source.backupMethod()),
                registered = true,
                origin = KeyOrigin.Restored,
            ),
        )
        persistSession(session.copy(homeserver = resolved))
    }.onFailure {
        // Never the message, and never the throwable: `toResult` wraps the FFI string verbatim, and
        // these calls were handed a mnemonic, a passphrase or a secret key.
        Log.e(TAG, "signInWithKey: FAILED — ${it::class.simpleName}")
    }

    /**
     * Derive a keypair from [source] off the caller's thread. The FFI's key calls are synchronous
     * and un-dispatched, and a recovery file is Argon2id, so this hop is what keeps a ViewModel from
     * running a key-derivation function on the main thread.
     */
    private suspend fun deriveKeypair(source: KeySource): Result<MintedKeypair> =
        withContext(Dispatchers.Default) {
            when (source) {
                is KeySource.Phrase -> pubky.keypairFromMnemonic(source.mnemonic)
                is KeySource.RecoveryFile ->
                    pubky.decryptRecoveryFile(source.base64, source.passphrase)
                        .mapCatching { pubky.keypairFromSecretKey(it.trim()).getOrThrow() }
            }
        }

    override suspend fun createLocalAccount(
        homeserverPubky: String,
        signupToken: String,
    ): Result<LocalAccount> = runSuspendCatching {
        // **Reuses an unregistered key before minting one.** Three paths land here with a key on the
        // device and no account for it: a retry after a failed `signUp`, the "start over" after a
        // refused token, and the unregistered-key screen. Minting unconditionally overwrote that key
        // in all three — the user confirmed "publish *this* pubky", got a different one, and the
        // original was stranded.
        //
        // Only an *unregistered* key is reused: one with an account is somebody's working identity.
        // And only one this flow **minted** — adopting a restored key meant "Create an account"
        // could silently register a phrase typed on the restore screen and abandoned, handing back
        // an old identity with no mnemonic to back up. Registering a restored key is `registerHeldKey`.
        val held = localKeyStore.current()?.takeIf { !it.registered && it.origin == KeyOrigin.Minted }

        val account = if (held != null) {
            Log.d(TAG, "createLocalAccount: registering the key already held")
            LocalAccount(pubky = held.pubky, mnemonic = held.mnemonic.orEmpty())
        } else {
            // Minting validates the key against its own phrase before we ever see it; a failure here
            // is terminal and is never retried with a weaker source.
            val minted = withContext(Dispatchers.Default) { pubky.mintValidatedKeypair() }.getOrThrow()
            val mnemonic = requireNotNull(minted.mnemonic) { "a minted keypair must carry its phrase" }

            // Stored *before* signUp and marked unregistered: if registration fails halfway the key
            // survives, and the next attempt finds it above rather than minting again.
            localKeyStore.save(
                LocalKey(
                    secretKeyHex = minted.secretKeyHex,
                    pubky = minted.pubky,
                    mnemonic = mnemonic,
                    registered = false,
                    origin = KeyOrigin.Minted,
                ),
            )
            LocalAccount(pubky = minted.pubky, mnemonic = mnemonic)
        }

        val secretKey = requireNotNull(localKeyStore.current()) { "the key was not stored" }.secretKeyHex
        registerKey(secretKey, account.pubky, homeserverPubky, signupToken)
        account
    }.onFailure {
        // Never the message or the throwable — see signInWithKey.
        Log.e(TAG, "createLocalAccount: FAILED — ${it::class.simpleName}")
    }

    override suspend fun registerHeldKey(
        homeserverPubky: String,
        signupToken: String,
    ): Result<Session> = runSuspendCatching {
        val held = requireNotNull(localKeyStore.current()) { "No local key to register" }
        check(!held.registered) { "This key already has an account" }
        registerKey(held.secretKeyHex, held.pubky, homeserverPubky, signupToken)
    }.onFailure {
        // Never the message or the throwable — see signInWithKey.
        Log.e(TAG, "registerHeldKey: FAILED — ${it::class.simpleName}")
    }

    /**
     * `signUp` for [secretKeyHex], then verify the account we actually landed on.
     *
     * The assertion is not paranoia: Architecture.md §7.8 records Ring quietly authorising a
     * *different* already-signed-up pubky when its own signup fails. A pubky we did not set out to
     * register is a failure to surface rather than a session to keep.
     */
    private suspend fun registerKey(
        secretKeyHex: String,
        expectedPubky: String,
        homeserverPubky: String,
        signupToken: String,
    ): Session {
        Log.d(TAG, "registerKey: registering ${expectedPubky.take(PUBKY_LOG_PREFIX_LEN)}…")
        val payload = pubky.signUp(secretKeyHex, homeserverPubky, signupToken).getOrThrow()
        val session = parseSessionPayload(payload, loopkyJson)

        check(session.identity.pubky == expectedPubky) {
            "signUp returned a different pubky than the key we registered"
        }

        // Marked here, not after persistSession. The account exists the moment signUp returns for the
        // right pubky; if the save or profile fetch below throws, a key left reading
        // `registered = false` would be re-registered — spending a second token on an existing account.
        localKeyStore.markRegistered()

        // The grant flow's session JSON has no `homeserver` field, so it is filled from the value we
        // registered against rather than left blank for Settings to render as "Unknown".
        return persistSession(session.copy(homeserver = session.homeserver.ifBlank { homeserverPubky }))
    }

    override suspend fun holdKeyForRegistration(source: KeySource): Result<String> =
        runSuspendCatching {
            val keypair = deriveKeypair(source).getOrThrow()
            // Marked unregistered on purpose: the pre-flight said no account exists. Without storing
            // it, "Register this key" on the next screen has nothing to register and fails on a
            // `requireNotNull` the user never caused.
            localKeyStore.save(
                LocalKey(
                    secretKeyHex = keypair.secretKeyHex,
                    pubky = keypair.pubky,
                    mnemonic = keypair.mnemonic,
                    backedUpBy = setOf(source.backupMethod()),
                    registered = false,
                    origin = KeyOrigin.Restored,
                ),
            )
            keypair.pubky
        }.onFailure {
            // Never the message: the FFI echoes its input back in some error strings.
            Log.e(TAG, "holdKeyForRegistration: FAILED — ${it::class.simpleName}")
        }

    override fun discardUnregisteredKey() {
        // On this repository's own scope, because the caller's is already gone — see the interface.
        scope.launch {
            val held = localKeyStore.current() ?: return@launch
            if (held.registered) return@launch
            // Restored only. A minted key exists nowhere else on earth, so an interrupted signup is
            // something to let the user finish, not to delete behind their back.
            if (held.origin != KeyOrigin.Restored) return@launch
            Log.d(TAG, "discardUnregisteredKey: dropping an unregistered restored key")
            localKeyStore.clear()
        }
    }

    /** What restoring from [this] proves the user already has a copy of. */
    private fun KeySource.backupMethod(): BackupMethod = when (this) {
        is KeySource.Phrase -> BackupMethod.RecoveryPhrase
        is KeySource.RecoveryFile -> BackupMethod.EncryptedFile
    }

    /**
     * Save a session, publish it, announce the account, and enrich it from the published profile.
     * Extracted so the Ring path and the local-key paths cannot drift — duplicating it is how one
     * ends up not self-tagging and quietly staying out of the only global directory Loopky has.
     */
    private suspend fun persistSession(session: Session): Session {
        sessionStore.save(session)
        sessionProvider.set(session)
        selfTagAsLoopkyUser(session)

        val profile = runSuspendCatching { fetchProfile(session.identity.pubky).getOrNull() }.getOrNull()
        if (profile != null && (profile.displayName != null || profile.bio != null)) {
            val enriched = session.copy(
                identity = session.identity.copy(
                    displayName = profile.displayName,
                    avatarUrl = profile.avatarUrl,
                    bio = profile.bio,
                ),
            )
            sessionStore.save(enriched)
            sessionProvider.set(enriched)
            return enriched
        }
        return session
    }

    /**
     * Announce this account as a Loopky user by tagging its own `profile.json` with
     * [ReservedTags.USER], **without anyone waiting on it**.
     *
     * The whole reason a stranger can find a brand-new account: with no backend the tag index is the
     * only global directory, and a user who tags nothing is invisible to it however many decks they
     * publish (#40). Tagger and subject are the same account, which makes the entry verifiable.
     *
     * Best-effort and idempotent — the tag id is derived from subject + label. Repeating it on each
     * login is how accounts that predate this get into the directory.
     *
     * Fire-and-forget on purpose: nothing reads the result, and awaiting it put a homeserver PUT on
     * the splash screen's critical path — a cold start sat on the branded splash for ~5.9s, because
     * this is the process's first network call and pays the FFI client build and the PKARR
     * homeserver resolve on top of the write itself (#266). Home's own listing pays those now, and
     * pays them while there is something on screen.
     *
     * [selfTaggedThisProcess] is claimed before the write rather than after, so a second
     * `loadPersistedSession` a moment later cannot start a duplicate; a failed write clears it so
     * the next sign-in tries again.
     */
    private fun selfTagAsLoopkyUser(session: Session) {
        if (selfTaggedThisProcess) return
        // The subject is a *profile*, so the record goes to `/pub/pubky.app/tags/` (§7.7) — which a
        // session scoped to `/pub/loopky/:rw` was never granted. Asked rather than attempted: the
        // headless client (#54) holds exactly that session, and firing anyway would buy a doomed
        // round trip per command plus a warning about the scope working as designed.
        if (!session.canWritePubkyApp) {
            Log.d(TAG, "selfTag: skipped — this session has no pubky.app write capability")
            return
        }
        selfTaggedThisProcess = true
        val profileUri = PubkyUri(PubkyPaths.profile(session.identity.pubky))
        scope.launch {
            // Re-checked here, not only at the call site: nothing cancels this, and
            // `AccountEraser` removes exactly this record and treats failing to as fatal, because
            // it is the only thing that takes an account out of Discover and search. A write that
            // started before a delete and landed after it would put the deleted account back.
            if (sessionProvider.current()?.identity?.pubky != session.identity.pubky) {
                Log.d(TAG, "selfTag: skipped — the session moved on before the write started")
                selfTaggedThisProcess = false
                return@launch
            }
            tagRepository.putReservedTag(profileUri, ReservedTags.USER)
                .onSuccess { Log.d(TAG, "selfTag: ${ReservedTags.USER.value} written") }
                .onFailure {
                    selfTaggedThisProcess = false
                    Log.w(TAG, "selfTag: FAILED — ${it.message}")
                }
        }
    }

    /** Reset on sign-out so the next account announces itself too. */
    private var selfTaggedThisProcess = false

    /**
     * Successful lookups only. A miss stays uncached so a profile created later still shows up
     * without restarting the app.
     */
    private val profileCache = mutableMapOf<String, PubkyIdentity>()
    private val profileCacheLock = Mutex()

    override suspend fun fetchProfile(pubky: String, forceRefresh: Boolean): Result<PubkyIdentity> {
        if (!forceRefresh) {
            profileCacheLock.withLock { profileCache[pubky] }?.let { return Result.success(it) }
        }
        return runSuspendCatching {
            Log.d(TAG, "fetchProfile: pubky=${pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
            val json = this.pubky.get(PubkyPaths.profile(pubky)).getOrThrow()
            val dto = loopkyJson.decodeFromString<ProfileDto>(json)
            dto.toDomain(pubky).also { cacheProfile(it) }
        }.onFailure {
            // A 404 means "this account has published no profile" — the ordinary state of any pubky
            // created outside pubky.app, which every caller already handles. Reporting it as an
            // error made a clean sign-in read as broken and buried the failures that matter (#174).
            if (it.isNotFound()) {
                Log.d(TAG, "fetchProfile: none published by ${pubky.take(PUBKY_LOG_PREFIX_LEN)}…")
            } else {
                Log.e(TAG, "fetchProfile: FAILED — ${it::class.simpleName}: ${it.message}", it)
            }
        }
    }

    private suspend fun cacheProfile(identity: PubkyIdentity) {
        profileCacheLock.withLock { profileCache[identity.pubky] = identity }
    }

    override suspend fun updateProfile(name: String?, bio: String?): Result<PubkyIdentity> = runSuspendCatching {
        val session = sessionProvider.current() ?: error("Not signed in")
        val currentPubky = session.identity.pubky
        Log.d(TAG, "updateProfile: pubky=${currentPubky.take(PUBKY_LOG_PREFIX_LEN)}…")

        // The picture comes off the *published* profile, not the session. Sign-in only enriches the
        // session when the profile has a name or bio, so an account with a picture and neither
        // carries avatarUrl = null — echoing that back wrote `image: null`, deleting the user's
        // avatar the first time they renamed themselves.
        val publishedAvatar = fetchProfile(currentPubky, forceRefresh = true).getOrNull()?.avatarUrl
        val dto = ProfileDto(
            name = name,
            bio = bio,
            image = publishedAvatar ?: session.identity.avatarUrl,
        )
        val json = loopkyJson.encodeToString(ProfileDto.serializer(), dto)
        val putResult = pubky.putWithSession(PubkyPaths.profile(currentPubky), json, session.sessionSecret)

        if (putResult.isFailure) {
            val err = putResult.exceptionOrNull()
            if (err?.message?.contains("403") == true || err?.message?.contains("write access") == true) {
                error("Please sign out and sign back in to enable profile editing.")
            }
            putResult.getOrThrow()
        }

        val updatedIdentity = dto.toDomain(currentPubky)
        val updatedSession = session.copy(identity = updatedIdentity)
        sessionStore.save(updatedSession)
        sessionProvider.set(updatedSession)
        // Keep the cache honest — otherwise every other screen keeps showing the old name.
        cacheProfile(updatedIdentity)
        // The other moment the profile provably exists, so the other chance to get into the
        // directory if the write at login failed.
        selfTagAsLoopkyUser(updatedSession)
        Log.d(TAG, "updateProfile: saved")
        updatedIdentity
    }.onFailure {
        Log.e(TAG, "updateProfile: FAILED — ${it::class.simpleName}: ${it.message}", it)
    }

    override suspend fun deleteAccount(onProgress: (Int, Int) -> Unit): Result<Unit> = runSuspendCatching {
        val pubky = requireNotNull(sessionProvider.current()) { "Not signed in" }.identity.pubky
        Log.d(TAG, "deleteAccount: starting for ${pubky.take(PUBKY_LOG_PREFIX_LEN)}…")

        // Throws unless every record Loopky owns is gone, which keeps the sign-out below from
        // stranding a half-deleted account: signing back in is what a retry needs.
        eraser.erase(pubky, onProgress)

        // So a later sign-in on this process announces the account again rather than believing it did.
        selfTaggedThisProcess = false
        // Forced: the records are already gone, so refusing would strand a half-deleted account
        // behind a backup prompt. The confirm that matters happens before deletion starts.
        signOut(force = true).getOrThrow()
        Log.d(TAG, "deleteAccount: done")
    }.onFailure {
        Log.e(TAG, "deleteAccount: FAILED — ${it::class.simpleName}: ${it.message}", it)
    }

    companion object {
        private const val TAG = "Loopky/IdentityRepo"
        private const val PUBKY_LOG_PREFIX_LEN = 8

        /** Deeplink Pubky Ring re-opens after approval; registered in the platform manifest. */
        private const val CALLBACK_URL = "loopky://login-callback"
        private const val CALLBACK_SOURCE = "Loopky"
    }
}
