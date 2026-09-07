package com.github.jvsena42.loopky.testing

import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.nexus.HttpMethod
import com.github.jvsena42.loopky.data.nexus.HttpRequest
import com.github.jvsena42.loopky.data.nexus.HttpResponse
import com.github.jvsena42.loopky.data.pubky.MutableSessionProvider
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.repository.impl.AccountEraser
import com.github.jvsena42.loopky.data.repository.impl.DeckRepositoryImpl
import com.github.jvsena42.loopky.data.repository.impl.IdentityRepositoryImpl
import com.github.jvsena42.loopky.data.storage.AppPreferences
import com.github.jvsena42.loopky.data.storage.DeckCacheStore
import com.github.jvsena42.loopky.data.storage.LocalKeyStore
import com.github.jvsena42.loopky.data.storage.PendingReviewStore
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.data.storage.StudyProgressStore
import com.github.jvsena42.loopky.data.storage.UnsplashKeyStore
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.ChunkMeta
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.platform.BackgroundTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

const val TEST_PUBKY = "ownerpk"

fun fakeSession(pubky: String = TEST_PUBKY, displayName: String? = "Tester"): Session = Session(
    identity = PubkyIdentity(
        pubky = pubky,
        displayName = displayName,
        avatarUrl = null,
        bio = null,
    ),
    sessionSecret = "session-secret-$pubky",
    capabilities = listOf(Capability("/pub/loopky/:rw"), Capability("/pub/pubky.app/:rw")),
    homeserver = "homeserverpk",
)

/** A [MutableSessionProvider] already holding a signed-in session for [pubky]. */
fun signedInProvider(pubky: String = TEST_PUBKY): MutableSessionProvider =
    MutableSessionProvider().apply { set(fakeSession(pubky)) }

/** [SessionRevalidator] that always succeeds and counts invocations. */
class CountingRevalidator(private val pubky: String = TEST_PUBKY) : SessionRevalidator {
    var invocations = 0
        private set

    override suspend fun revalidate(): Result<Session> {
        invocations++
        return Result.success(fakeSession(pubky))
    }
}

/**
 * [HttpFetcher] returning canned responses keyed by (method, url).
 *
 * Responses are queued per key: each call pops the head and the last entry repeats forever. That
 * is what makes a re-poll loop testable without a mocking library — a long-poll has to answer
 * 408, 408, then 200 on the very same URL, and there is no other way to say so here.
 */
class FakeHttpFetcher : HttpFetcher {
    private data class Key(val method: HttpMethod, val url: String)

    private val responses = mutableMapOf<Key, ArrayDeque<Result<HttpResponse>>>()

    /** Every request in order, for asserting method, body and timeout. */
    val requests = mutableListOf<HttpRequest>()

    /** Derived views, so assertions written against the GET-only fake still read the same. */
    val requestedUrls: List<String> get() = requests.map { it.url }
    val requestedHeaders: List<Map<String, String>> get() = requests.map { it.headers }

    /**
     * Canned 200 for a GET. **Replaces** whatever that URL previously answered, so a test can
     * restate one URL's response between assertions without the old one lingering.
     */
    fun respond(url: String, body: String) {
        replace(HttpMethod.GET, url, Result.success(HttpResponse(statusCode = 200, body = body)))
    }

    /** Transport failure for a GET — not a status, an unreachable server. Also replaces. */
    fun fail(url: String, error: Throwable) {
        replace(HttpMethod.GET, url, Result.failure(error))
    }

    private fun replace(method: HttpMethod, url: String, result: Result<HttpResponse>) {
        responses[Key(method, url)] = ArrayDeque(listOf(result))
    }

    fun enqueue(method: HttpMethod, url: String, vararg canned: HttpResponse) {
        val queue = responses.getOrPut(Key(method, url)) { ArrayDeque() }
        canned.forEach { queue.addLast(Result.success(it)) }
    }

    fun enqueueFailure(method: HttpMethod, url: String, error: Throwable) {
        responses.getOrPut(Key(method, url)) { ArrayDeque() }.addLast(Result.failure(error))
    }

    override suspend fun send(request: HttpRequest): Result<HttpResponse> {
        requests.add(request)
        val queue = responses[Key(request.method, request.url)]
            ?: return Result.failure(
                IllegalStateException("No canned response for ${request.method} ${request.url}"),
            )
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }
}

fun testCard(
    id: String,
    deckId: String = "deck1",
    front: String = "front of $id",
    back: String = "back of $id",
    updatedAt: Long = 1_000L,
    ord: Long = 0L,
): Card = Card(
    id = id,
    deckId = deckId,
    updatedAt = updatedAt,
    front = CardSide(text = front),
    back = CardSide(text = back),
    ord = ord,
)

/** A blob-backed cover image, as a published deck's manifest carries one. */
fun testCoverImage(sha: String = "abc123"): MediaRef.Image = MediaRef.Image(
    path = "media/$sha.png",
    mime = "image/png",
    sha256 = sha,
    width = null,
    height = null,
)

fun testDeck(
    id: String = "deck1",
    authorPubky: String = TEST_PUBKY,
    title: String = "Deck $id",
    tags: List<Tag> = emptyList(),
    cardCount: Int = 0,
    chunks: List<ChunkMeta> = emptyList(),
    createdAt: Long = 1_000L,
    updatedAt: Long = 2_000L,
    coverImageRef: MediaRef.Image? = null,
    frontLang: String? = null,
    backLang: String? = null,
    typeEnabled: Boolean = false,
    reverseEnabled: Boolean = false,
): Deck = Deck(
    id = id,
    authorPubky = authorPubky,
    title = title,
    description = null,
    coverEmoji = null,
    coverImageRef = coverImageRef,
    tags = tags,
    createdAt = createdAt,
    updatedAt = updatedAt,
    cardCount = cardCount,
    chunks = chunks,
    frontLang = frontLang,
    backLang = backLang,
    typeEnabled = typeEnabled,
    reverseEnabled = reverseEnabled,
)

/**
 * A deck whose manifest describes [cards] laid out in chunks of [chunkSize], mirroring what
 * `publish` would have written. Use with [seedChunks] to stand up a readable deck in a test.
 */
fun testDeckWithCards(
    cards: List<Card>,
    id: String = "deck1",
    authorPubky: String = TEST_PUBKY,
    chunkSize: Int = 100,
    updatedAt: Long = 2_000L,
): Deck = testDeck(
    id = id,
    authorPubky = authorPubky,
    cardCount = cards.size,
    chunks = cards.chunked(chunkSize).mapIndexed { n, batch ->
        ChunkMeta(n = n, count = batch.size, updatedAt = updatedAt)
    },
    updatedAt = updatedAt,
)

/**
 * A [DeckRepositoryImpl] with the collaborators a test rarely cares about defaulted, so a test
 * that only needs a different `cardRepo` says only that.
 */
fun deckRepository(
    pubky: PubkyClient,
    session: SessionProvider,
    cardRepo: CardRepository,
    revalidator: SessionRevalidator,
    tagRepo: TagRepository = RecordingTagRepository(),
    mediaRepo: MediaRepository = FakeMediaRepository(),
    backgroundTasks: BackgroundTasks = FakeBackgroundTasks(),
    deckCache: DeckCacheStore = FakeDeckCacheStore(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob()),
): DeckRepositoryImpl = DeckRepositoryImpl(
    pubky = pubky,
    session = session,
    cardRepo = cardRepo,
    revalidator = revalidator,
    tagRepo = tagRepo,
    mediaRepo = mediaRepo,
    backgroundTasks = backgroundTasks,
    deckCache = deckCache,
    scope = scope,
)

/**
 * Build a real [IdentityRepositoryImpl] over fakes, with everything [IdentityRepository.deleteAccount]
 * needs defaulted.
 *
 * A factory rather than ten arguments at each call site: most tests care about two or three of
 * these, and a constructor that grows again should cost one edit here instead of one per test.
 */
@Suppress("LongParameterList")
internal fun identityRepository(
    pubky: PubkyClient = FakePubkyClient(),
    sessionStore: SecureSessionStore = NoopSessionStore(),
    sessionProvider: MutableSessionProvider = signedInProvider(),
    tagRepository: TagRepository = RecordingTagRepository(),
    deckRepository: DeckRepository = FakeDeckRepository(),
    revalidator: SessionRevalidator = CountingRevalidator(),
    pendingReviews: PendingReviewStore = FakePendingReviewStore(),
    studyProgress: StudyProgressStore = FakeStudyProgressStore(),
    preferences: AppPreferences = FakeAppPreferences(),
    unsplashKeyStore: UnsplashKeyStore = FakeUnsplashKeyStore(),
    localKeyStore: LocalKeyStore = FakeLocalKeyStore(),
    deckCache: DeckCacheStore = FakeDeckCacheStore(),
    /** Defaults to the caller's scope so `runTest` can await the fire-and-forget cleanup. */
    scope: CoroutineScope = CoroutineScope(SupervisorJob()),
): IdentityRepositoryImpl = IdentityRepositoryImpl(
    pubky = pubky,
    sessionStore = sessionStore,
    sessionProvider = sessionProvider,
    tagRepository = tagRepository,
    localKeyStore = localKeyStore,
    scope = scope,
    eraser = AccountEraser(
        pubky = pubky,
        session = sessionProvider,
        revalidator = revalidator,
        decks = deckRepository,
        tags = tagRepository,
        pendingReviews = pendingReviews,
        studyProgress = studyProgress,
        preferences = preferences,
        unsplashKeyStore = unsplashKeyStore,
        deckCache = deckCache,
    ),
)

/** A [SecureSessionStore] that remembers nothing, for tests that never read the session back. */
class NoopSessionStore : SecureSessionStore {
    override val location: String = "nowhere"
    override suspend fun save(session: Session) = Unit
    override suspend fun load(): Session? = null
    override suspend fun clear() = Unit
}
