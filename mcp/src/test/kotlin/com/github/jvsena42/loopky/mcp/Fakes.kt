package com.github.jvsena42.loopky.mcp

import com.github.jvsena42.loopky.cli.FakeCardRepository
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.FakeMediaRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.data.nexus.NexusResourceSorting
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.repository.TaggedSubject
import com.github.jvsena42.loopky.data.repository.impl.ImportRepositoryImpl
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.mcp.tenant.Tenant
import com.github.jvsena42.loopky.mcp.tenant.TenantFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Tokens to principals. A token absent here is "not live". */
class FakeVerifier(private val tokens: Map<String, Principal>) : AccessTokenVerifier {
    override suspend fun verify(accessToken: String): Principal? = tokens[accessToken]
}

/** Pubkys to secrets, and a record of every revocation reported. */
class FakeSecrets(secrets: Map<String, String> = emptyMap()) : SessionSecrets {
    val secrets = ConcurrentHashMap(secrets)
    val revoked = mutableListOf<String>()

    override suspend fun sessionSecretFor(pubky: String): String? = secrets[pubky]

    override suspend fun revoked(pubky: String) {
        revoked += pubky
        secrets.remove(pubky)
    }
}

fun sessionFor(pubky: String, secret: String = "secret-$pubky"): Session = Session(
    identity = PubkyIdentity(pubky = pubky, displayName = null, avatarUrl = null, bio = null),
    sessionSecret = secret,
    capabilities = listOf(Capability(LOOPKY_CAPABILITIES)),
    homeserver = "homeserverpk",
)

/** A graph over the `:cli-core` fakes, remembering which secret opened it and whether it was closed. */
class FakeTenant(
    val secret: String,
    override val session: Session,
    override val decks: DeckRepository = FakeDeckRepository(testDeck()),
    override val cards: CardRepository = FakeCardRepository(),
    override val imports: ImportRepository = ImportRepositoryImpl(),
    override val media: MediaRepository = FakeMediaRepository(),
    override val tags: TagRepository = FakeTagRepository(),
) : Tenant {
    @Volatile var closed = false

    override suspend fun close() {
        closed = true
    }
}

/**
 * Opens a [FakeTenant] per secret, the pubky read back out of the secret's `secret-<pubky>` shape
 * so a test can ask whose graph a call ran on. [build] lets a test hand in richer repositories.
 */
class FakeTenantFactory(
    private val build: (pubky: String, secret: String) -> Tenant = { pubky, secret ->
        FakeTenant(secret, sessionFor(pubky, secret))
    },
) : TenantFactory {
    val opened = mutableListOf<Tenant>()
    val opens = AtomicInteger()

    override suspend fun open(sessionSecret: String): Tenant {
        opens.incrementAndGet()
        val pubky = sessionSecret.removePrefix("secret-")
        return build(pubky, sessionSecret).also { synchronized(opened) { opened += it } }
    }
}

class FakeTagRepository(private val trending: List<String> = listOf("spanish", "language")) : TagRepository {
    override suspend fun trendingDeckTags(sampleSize: Int, limit: Int): Result<List<Tag>> =
        Result.success(trending.take(limit).map(::Tag))

    private fun no(name: String): Nothing = error("FakeTagRepository.$name is not part of this test")

    override suspend fun putTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> = no("putTag")
    override suspend fun removeTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> = no("removeTag")
    override suspend fun putReservedTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> = no("putReservedTag")
    override suspend fun removeReservedTag(subjectUri: PubkyUri, tag: Tag): Result<Unit> = no("removeReservedTag")
    override suspend fun taggedSubjects(tag: Tag, limit: Int, skip: Int, sorting: NexusResourceSorting): List<TaggedSubject> =
        no("taggedSubjects")
    override suspend fun usersTagged(tag: Tag, limit: Int): List<String> = no("usersTagged")
    override suspend fun postAuthorsTagged(tag: Tag, limit: Int): List<String> = no("postAuthorsTagged")
    override suspend fun isSelfTagged(pubky: String, tag: Tag): Boolean = no("isSelfTagged")
    override suspend fun taggerCounts(subjectUri: PubkyUri): Map<Tag, Int> = no("taggerCounts")
}

/** A deck owned by [pubky], the way `deck list` would report it. */
fun ownedDeck(pubky: String, id: String = "deck-$pubky"): Deck = testDeck(id).copy(authorPubky = pubky, title = "Deck of $pubky")
