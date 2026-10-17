package com.github.jvsena42.loopky.di

import com.github.jvsena42.loopky.data.homegate.HomegateClient
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.MutableSessionProvider
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.SettingsRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.data.storage.DeckCacheStore
import com.github.jvsena42.loopky.data.storage.InMemoryAppPreferences
import com.github.jvsena42.loopky.data.storage.InMemoryDeckCacheStore
import com.github.jvsena42.loopky.data.storage.InMemoryLocalKeyStore
import com.github.jvsena42.loopky.data.storage.InMemoryPendingReviewStore
import com.github.jvsena42.loopky.data.storage.InMemorySecureSessionStore
import com.github.jvsena42.loopky.data.storage.InMemorySignupTokenStore
import com.github.jvsena42.loopky.data.storage.InMemoryStudyProgressStore
import com.github.jvsena42.loopky.data.storage.InMemoryUnsplashKeyStore
import com.github.jvsena42.loopky.data.storage.LocalKeyStore
import com.github.jvsena42.loopky.data.storage.PendingReviewStore
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.data.storage.SignupTokenStore
import com.github.jvsena42.loopky.data.storage.StudyProgressStore
import com.github.jvsena42.loopky.data.storage.UnsplashKeyStore
import com.github.jvsena42.loopky.data.unsplash.UnsplashClient
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.platform.PassThroughMediaProcessor
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * What every [TenantGraph] in a process shares: the collaborators that hold no user state.
 *
 * [pubky] is one instance for the process because the FFI underneath it is — every call takes
 * its session secret as an argument, so the client itself has nothing to keep apart. A server
 * constructs it with its own client id (`mcp.loopky.app`, #214) so Ring lists it separately from
 * the phone. [http], [nexus] and [homegate] are a fetcher and two base URLs. [mediaProcessor] is
 * typed as the pass-through on purpose: a server has no business decoding users' images (the
 * `.apkg` path is the one that would, and #486 leaves it out of v1), and a `MediaProcessor`-typed
 * parameter is how `JvmMediaProcessor` would come back.
 *
 * One [PubkyEnvironment] per process, never per request — `switchNetwork` is global to the FFI,
 * so a deployment serves one network (#214).
 */
class TenantServices(
    val environment: PubkyEnvironment,
    val pubky: PubkyClient,
    val http: HttpFetcher,
    val nexus: NexusClient = NexusClient(http = http, baseUrl = environment.nexusBaseUrl),
    val homegate: HomegateClient = HomegateClient(http = http, baseUrl = environment.homegateBaseUrl),
    val mediaProcessor: PassThroughMediaProcessor = PassThroughMediaProcessor(),
)

/**
 * One user's repository graph, in a process that hosts many (#214, order of work step 3).
 *
 * The repositories cache per session — decks, cards, review state, settings, the session itself
 * — and they were written for a process with one user in it. The isolation here is therefore
 * structural rather than a key on every cache: each graph is its own Koin instance, built from
 * `sharedModule` plus [tenantPlatformModule], with nothing but [TenantServices] in common.
 * Nothing in it touches a file: every store is in memory, so there is no path under a config
 * home that a second graph could read.
 *
 * **One graph, one session, adopted once.** [adoptSession] refuses a second adoption rather than
 * check the pubky, because a check runs after the session is already in place and the window
 * between the two is where a request could write as the wrong user. A rotated credential for the
 * same user means a new graph; they are cheap.
 *
 * Not for signing in. `startAuthFlow`/`awaitAuthApproval` are one global slot in the FFI (§13.10),
 * so the server runs its Ring flow outside any tenant graph and hands the resulting secret in.
 *
 * [close] clears the session, cancels the repositories' scope and closes the Koin instance.
 * The repository references stay callable afterwards and fail with "Not signed in"; nothing
 * about them is torn down beyond that, so a caller that keeps using a closed graph is a bug
 * rather than a leak.
 */
class TenantGraph private constructor(private val koin: Koin) : AutoCloseable {

    // Not public: signInWithKey, registerHeldKey and loadPersistedSession all set the session,
    // and any of them would switch a graph's user under caches warm with the last one's data.
    // adoptSession is the one way in, and it refuses a second.
    internal val identity: IdentityRepository = koin.get()
    val decks: DeckRepository = koin.get()
    val cards: CardRepository = koin.get()
    val imports: ImportRepository = koin.get()
    val tags: TagRepository = koin.get()
    val media: MediaRepository = koin.get()
    val settings: SettingsRepository = koin.get()
    val srs: SrsRepository = koin.get()
    val discovery: DiscoveryRepository = koin.get()

    private val sessionProvider: MutableSessionProvider = koin.get()
    private val adoptLock = Mutex()

    @Volatile
    var isClosed: Boolean = false
        private set

    /** The session this graph runs as — null before [adoptSession] and after [close]. */
    val session: Session? get() = sessionProvider.current()

    /**
     * Make [sessionSecret] this graph's session, proving it live in the same round trip
     * ([IdentityRepository.adoptSession]). Refused when the graph already has one or is closed.
     */
    suspend fun adoptSession(sessionSecret: String): Result<Session> = adoptLock.withLock {
        runSuspendCatching {
            check(!isClosed) { "This tenant graph is closed" }
            check(session == null) { "This tenant graph already holds a session; open another graph" }
            identity.adoptSession(sessionSecret).getOrThrow().also {
                check(!isClosed) { "This tenant graph was closed while its session was being adopted" }
            }
        }
    }

    /**
     * Pending study grades are not flushed: they live in this graph's in-memory journal and go
     * with it. The server serves no grading tool; a caller that grades calls `srs.flush()` first.
     */
    override fun close() {
        if (isClosed) return
        isClosed = true
        sessionProvider.close()
        koin.close()
    }

    companion object {
        fun open(services: TenantServices): TenantGraph = TenantGraph(
            koinApplication { modules(sharedModule(), tenantPlatformModule(services)) }.koin,
        )

        /** [open] then [adoptSession], closing the graph again when the secret is not live. */
        suspend fun forSession(services: TenantServices, sessionSecret: String): Result<TenantGraph> =
            forSession(services, sessionSecret, ::open)

        internal suspend fun forSession(
            services: TenantServices,
            sessionSecret: String,
            open: (TenantServices) -> TenantGraph,
        ): Result<TenantGraph> {
            val graph = open(services)
            var adopted = false
            // `finally`, not `onFailure`: a cancelled adoption throws past a Result, and the graph's
            // scope (with DeckRepositoryImpl's collector on it) would never be closed.
            try {
                return graph.adoptSession(sessionSecret).map { graph }.also { adopted = it.isSuccess }
            } finally {
                if (!adopted) graph.close()
            }
        }
    }
}

/** The per-user half of a [TenantGraph]: shared services bound as-is, every store in memory. */
internal fun tenantPlatformModule(services: TenantServices): Module = module {
    includes(headlessPlatformModule(services.mediaProcessor))

    single<PubkyClient> { services.pubky }
    single<HttpFetcher> { services.http }
    single { services.environment }
    single { services.nexus }
    single { services.homegate }

    single<SecureSessionStore> { InMemorySecureSessionStore() }
    single<LocalKeyStore> { InMemoryLocalKeyStore() }
    single<SignupTokenStore> { InMemorySignupTokenStore() }
    single<UnsplashKeyStore> { InMemoryUnsplashKeyStore() }
    single<AppPreferences> { InMemoryAppPreferences() }
    single<PendingReviewStore> { InMemoryPendingReviewStore() }
    single<StudyProgressStore> { InMemoryStudyProgressStore() }
    single<DeckCacheStore> { InMemoryDeckCacheStore() }

    // No fallback key: a server has no Unsplash account of its own to spend on users' searches.
    single { UnsplashClient(http = get(), keyStore = get()) }
}
