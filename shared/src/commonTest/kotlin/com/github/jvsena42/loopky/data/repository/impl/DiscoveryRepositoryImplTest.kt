package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.nexus.HttpError
import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.FollowDto
import com.github.jvsena42.loopky.data.pubky.PostDto
import com.github.jvsena42.loopky.data.pubky.PostKinds
import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.TaggedSubject
import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeBackgroundTasks
import com.github.jvsena42.loopky.testing.FakeDeckCacheStore
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.fakeSession
import com.github.jvsena42.loopky.testing.identityRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCoverImage
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryRepositoryImplTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val deckRepo = DeckRepositoryImpl(
        pubky = pubky,
        session = session,
        cardRepo = cardRepo,
        revalidator = revalidator,
        tagRepo = RecordingTagRepository(),
        mediaRepo = FakeMediaRepository(),
        backgroundTasks = FakeBackgroundTasks(),
        deckCache = FakeDeckCacheStore(),
    )
    private val tagRepo = RecordingTagRepository()
    private val identityRepo = identityRepository(
        pubky = pubky,
        sessionProvider = session,
        tagRepository = tagRepo,
    )
    private val http = FakeHttpFetcher()
    private val preferences = FakeAppPreferences()
    private val repo = DiscoveryRepositoryImpl(
        pubky = pubky,
        session = session,
        revalidator = revalidator,
        deckRepository = deckRepo,
        tagRepository = tagRepo,
        identityRepository = identityRepo,
        nexus = NexusClient(http = http, baseUrl = NEXUS_BASE),
        preferences = preferences,
    )

    private fun followUrl(followee: String) =
        "pubky://$TEST_PUBKY/pub/pubky.app/follows/$followee"

    private fun putRemoteManifest(
        author: String,
        deckId: String,
        updatedAt: Long,
        title: String = "Deck $deckId",
        tags: List<Tag> = emptyList(),
    ) {
        val dto = testDeck(
            id = deckId,
            authorPubky = author,
            title = title,
            tags = tags,
            updatedAt = updatedAt,
        ).toDto()
        pubky.store["pubky://$author/pub/loopky/decks/$deckId/manifest.json"] =
            loopkyJson.encodeToString(dto)
    }

    // ── follow / unfollow ────────────────────────────────────────────────

    @Test
    fun followUserWritesTheFollowRecord() = runTest {
        repo.followUser("friendpk").getOrThrow()

        val body = pubky.store.getValue(followUrl("friendpk"))
        assertTrue(loopkyJson.decodeFromString<FollowDto>(body).created_at > 0)
        assertTrue(repo.isFollowing("friendpk"))
    }

    @Test
    fun unfollowUserDeletesTheFollowRecord() = runTest {
        repo.followUser("friendpk").getOrThrow()

        repo.unfollowUser("friendpk").getOrThrow()

        assertTrue(followUrl("friendpk") !in pubky.store)
        assertEquals(listOf(followUrl("friendpk")), pubky.deletes)
        assertFalse(repo.isFollowing("friendpk"))
    }

    @Test
    fun followingParsesTheListPayloadOnAColdCache() = runTest {
        pubky.store[followUrl("friend1")] = """{"created_at":1}"""
        pubky.store[followUrl("friend2")] = """{"created_at":2}"""

        val following = repo.following()

        assertEquals(
            listOf("pubky://$TEST_PUBKY/pub/pubky.app/follows/"),
            pubky.listedPrefixes,
        )
        // Exact equality on purpose: the old substring scan cut each id at the *next* url's
        // "pubky://" and returned `friend1","pubky:`, which a startsWith assertion happily
        // accepted. Anything looser than this cannot tell the fix from the bug.
        assertEquals(listOf("friend1", "friend2"), following)
    }

    @Test
    fun followingIsEmptyWhenSignedOut() = runTest {
        session.set(null)

        assertEquals(emptyList(), repo.following())
    }

    // ── follow lists ─────────────────────────────────────────────────────

    private fun publishProfile(author: String, name: String) {
        pubky.store[PubkyPaths.profile(author)] = """{"name":"$name","bio":null,"image":null}"""
    }

    @Test
    fun followingProfilesKeepsOnlyTheLoopkyAccounts() = runTest {
        // The follow graph is pubky.app's, so most of it is people who never opened Loopky —
        // there is nothing to show them for, and a list of them is not a list of anything.
        repo.followUser("loopkypk").getOrThrow()
        repo.followUser("strangerpk").getOrThrow()
        tagRepo.selfTaggers = setOf("loopkypk")
        publishProfile("loopkypk", "Ada")
        publishProfile("strangerpk", "Someone Else")

        val following = repo.followingProfiles(TEST_PUBKY)

        assertEquals(listOf("loopkypk"), following.map { it.pubky })
        assertEquals("Ada", following.single().displayName)
    }

    @Test
    fun followingProfilesReadsAnotherUsersHomeserverRatherThanTheIndexer() = runTest {
        pubky.store["pubky://friendpk/pub/pubky.app/follows/loopkypk"] = """{"created_at":1}"""
        tagRepo.selfTaggers = setOf("loopkypk")
        publishProfile("loopkypk", "Ada")

        assertEquals(listOf("loopkypk"), repo.followingProfiles("friendpk").map { it.pubky })
        // Whose follows those are is a fact their own homeserver holds first-hand.
        assertTrue(http.requestedUrls.isEmpty())
    }

    @Test
    fun followingProfilesLeavesYouOutOfYourOwnList() = runTest {
        repo.followUser(TEST_PUBKY).getOrThrow()
        tagRepo.selfTaggers = setOf(TEST_PUBKY)
        publishProfile(TEST_PUBKY, "Me")

        assertEquals(emptyList(), repo.followingProfiles(TEST_PUBKY))
    }

    @Test
    fun followingProfilesKeepsALoopkyAccountWhoseProfileIsMissing() = runTest {
        // The self-tag already proved them real; dropping them would hide a genuine account.
        repo.followUser("loopkypk").getOrThrow()
        tagRepo.selfTaggers = setOf("loopkypk")

        val following = repo.followingProfiles(TEST_PUBKY)

        assertEquals(listOf("loopkypk"), following.map { it.pubky })
        assertNull(following.single().displayName)
    }

    @Test
    fun followerProfilesComesFromTheIndexerAndIsVerified() = runTest {
        // Nobody's homeserver holds "who follows me" — the records live on each follower's.
        http.respond(
            "$NEXUS_BASE/v0/user/$TEST_PUBKY/followers?limit=60",
            """["loopkypk","strangerpk"]""",
        )
        tagRepo.selfTaggers = setOf("loopkypk")
        publishProfile("loopkypk", "Ada")

        assertEquals(listOf("loopkypk"), repo.followerProfiles(TEST_PUBKY).map { it.pubky })
    }

    @Test
    fun followerProfilesDegradesToEmptyWhenTheIndexerIsDown() = runTest {
        // No canned response — an unreachable indexer must not take the profile screen with it.
        assertEquals(emptyList(), repo.followerProfiles(TEST_PUBKY))
    }

    @Test
    fun aLoopkyAccountIsCheckedAgainstTheIndexerOnlyOnce() = runTest {
        repo.followUser("loopkypk").getOrThrow()
        tagRepo.selfTaggers = setOf("loopkypk")
        publishProfile("loopkypk", "Ada")

        repo.followingProfiles(TEST_PUBKY)
        repo.followingProfiles(TEST_PUBKY)

        // The profile screen's counts and the list screen ask about the same people moments apart.
        assertEquals(expected = 1, actual = tagRepo.selfTagChecks.count { it == "loopkypk" })
    }

    // ── feed ─────────────────────────────────────────────────────────────

    @Test
    fun decksFromFollowingAggregatesNewestFirst() = runTest {
        repo.followUser("friend1").getOrThrow()
        repo.followUser("friend2").getOrThrow()
        putRemoteManifest(author = "friend1", deckId = "older", updatedAt = 100L)
        putRemoteManifest(author = "friend2", deckId = "newest", updatedAt = 300L)
        putRemoteManifest(author = "friend1", deckId = "middle", updatedAt = 200L)

        val feed = repo.decksFromFollowing()

        assertEquals(listOf("newest", "middle", "older"), feed.map { it.id })
    }

    @Test
    fun decksFromFollowingIsEmptyWithNoFollows() = runTest {
        assertEquals(emptyList(), repo.decksFromFollowing())
    }

    @Test
    fun decksFromFollowingSurvivesOneUnreachableAuthor() = runTest {
        // Every author is a different homeserver, and the fan-out is concurrent — which fails
        // fast, so a single dead one would cancel everybody else's request if the per-author
        // catch ever moved outside the transform.
        repo.followUser("friend1").getOrThrow()
        repo.followUser("friend2").getOrThrow()
        putRemoteManifest(author = "friend1", deckId = "unreachable", updatedAt = 300L)
        putRemoteManifest(author = "friend2", deckId = "reachable", updatedAt = 100L)
        pubky.failListWhenUrlContains = "pubky://friend1/"

        assertEquals(listOf("reachable"), repo.decksFromFollowing().map { it.id })
    }

    @Test
    fun decksFromFollowingCutsTheTailOfALongFollowList() = runTest {
        // Loopky follows are the pubky.app follow graph, so this is an ordinary account, not a
        // pathological one. Uncapped, each of these is a homeserver round-trip.
        val followees = (1..DiscoveryRepository.MAX_FOLLOWED_DECK_AUTHORS + 5)
            .map { "friend${it.toString().padStart(3, '0')}" }
        followees.forEach { repo.followUser(it).getOrThrow() }
        pubky.listedPrefixes.clear()

        repo.decksFromFollowing()

        val queried = pubky.listedPrefixes.filter { it.endsWith("/pub/loopky/decks/") }.distinct()
        assertEquals(DiscoveryRepository.MAX_FOLLOWED_DECK_AUTHORS, queried.size)
    }

    @Test
    fun decksFromFollowingSkipsYourself() = runTest {
        // Nothing stops an account following itself, and Discover is not where your own decks go.
        repo.followUser(TEST_PUBKY).getOrThrow()
        repo.followUser("friend1").getOrThrow()
        putRemoteManifest(author = TEST_PUBKY, deckId = "mine", updatedAt = 300L)
        putRemoteManifest(author = "friend1", deckId = "theirs", updatedAt = 100L)

        assertEquals(listOf("theirs"), repo.decksFromFollowing().map { it.id })
    }

    @Test
    fun followingThrowsWhenTheHomeserverIsUnreachable() = runTest {
        // Must not degrade to "you follow nobody" — that is indistinguishable from the real
        // empty state and hides the fact that the device is offline.
        pubky.failListWith = PubkyError("HTTP transport error: error sending request for url (...)")

        assertFailsWith<PubkyError> { repo.following() }
    }

    @Test
    fun followingReturnsEmptyWhenTheFollowsPathDoesNotExist() = runTest {
        pubky.failListWith = PubkyError("not found: pubky://$TEST_PUBKY/pub/pubky.app/follows/")

        assertEquals(emptyList(), repo.following())
    }

    // ── global browse (indexer-backed, everything untrusted) ─────────────

    private fun manifestUri(author: String, deckId: String) =
        PubkyUri("pubky://$author/pub/loopky/decks/$deckId/manifest.json")

    private fun tagged(uri: PubkyUri, taggers: List<String>) =
        TaggedSubject(uri = uri, taggers = taggers, taggersCount = taggers.size)

    @Test
    fun globalBrowseFindsADeckFromSomeoneYouDoNotFollow() = runTest {
        putRemoteManifest(author = "strangerpk", deckId = "deck1", updatedAt = 100L)
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                tagged(manifestUri("strangerpk", "deck1"), taggers = listOf("strangerpk")),
            ),
        )

        val decks = repo.decksByTagGlobal(ReservedTags.DECK)

        assertEquals(listOf("deck1"), decks.map { it.id })
        assertTrue(repo.following().isEmpty(), "found without following anyone")
    }

    @Test
    fun globalBrowseDropsATagPointedAtSomethingThatIsNotADeck() = runTest {
        // A forged entry is only useless if it has to resolve to a real deck to be shown.
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                tagged(PubkyUri("pubky://strangerpk/pub/pubky.app/posts/abc"), listOf("strangerpk")),
                tagged(PubkyUri("https://example.com/free-decks"), listOf("strangerpk")),
                tagged(manifestUri("strangerpk", "deck1"), listOf("strangerpk")),
            ),
        )
        putRemoteManifest(author = "strangerpk", deckId = "deck1", updatedAt = 100L)

        assertEquals(listOf("deck1"), repo.decksByTagGlobal(ReservedTags.DECK).map { it.id })
    }

    @Test
    fun globalBrowseDropsADeckTaggedBySomeoneOtherThanItsAuthor() = runTest {
        putRemoteManifest(author = "strangerpk", deckId = "deck1", updatedAt = 100L)
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                tagged(manifestUri("strangerpk", "deck1"), taggers = listOf("impostorpk")),
            ),
        )

        assertEquals(emptyList(), repo.decksByTagGlobal(ReservedTags.DECK))
    }

    @Test
    fun globalBrowseDropsAManifestThatDoesNotFetch() = runTest {
        // Right shape, nothing behind it — the manifest was never published or has been deleted.
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                tagged(manifestUri("strangerpk", "ghost"), taggers = listOf("strangerpk")),
            ),
        )

        assertEquals(emptyList(), repo.decksByTagGlobal(ReservedTags.DECK))
    }

    @Test
    fun globalBrowseDropsYourOwnDeck() = runTest {
        // Your decks are already in Library; repeating them here is what made Discover look empty
        // of anything new on a young network.
        putRemoteManifest(author = TEST_PUBKY, deckId = "mine", updatedAt = 200L)
        putRemoteManifest(author = "strangerpk", deckId = "deck1", updatedAt = 100L)
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                tagged(manifestUri(TEST_PUBKY, "mine"), taggers = listOf(TEST_PUBKY)),
                tagged(manifestUri("strangerpk", "deck1"), taggers = listOf("strangerpk")),
            ),
        )

        assertEquals(listOf("deck1"), repo.decksByTagGlobal(ReservedTags.DECK).map { it.id })
    }

    @Test
    fun globalBrowseKeepsEveryDeckWhenSignedOut() = runTest {
        // The own-deck rule is a filter on one pubky, not a blanket drop: with no session there is
        // nobody to exclude.
        session.set(null)
        putRemoteManifest(author = TEST_PUBKY, deckId = "mine", updatedAt = 200L)
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(tagged(manifestUri(TEST_PUBKY, "mine"), listOf(TEST_PUBKY))),
        )

        assertEquals(listOf("mine"), repo.decksByTagGlobal(ReservedTags.DECK).map { it.id })
    }

    @Test
    fun globalBrowseDedupesRepeatedSubjects() = runTest {
        putRemoteManifest(author = "strangerpk", deckId = "deck1", updatedAt = 100L)
        val subject = tagged(manifestUri("strangerpk", "deck1"), taggers = listOf("strangerpk"))
        tagRepo.subjectsByTag = mapOf(ReservedTags.DECK to listOf(subject, subject))

        assertEquals(expected = 1, actual = repo.decksByTagGlobal(ReservedTags.DECK).size)
    }

    // ── the Loopky user directory ────────────────────────────────────────

    @Test
    fun loopkyUsersReturnsSelfTaggedAccountsWithProfiles() = runTest {
        pubky.store["pubky://strangerpk/pub/pubky.app/profile.json"] =
            """{"name":"Ada","bio":null,"image":null}"""
        tagRepo.usersByTag = mapOf(ReservedTags.USER to listOf("strangerpk"))
        tagRepo.selfTaggers = setOf("strangerpk")

        val users = repo.loopkyUsers()

        assertEquals(listOf("strangerpk"), users.map { it.pubky })
        assertEquals("Ada", users.single().displayName)
    }

    @Test
    fun loopkyUsersDropsAccountsThatDidNotTagThemselves() = runTest {
        // Someone tagging *other* people loopky-user shows up as a tagger; they are not a claim
        // about themselves and must not enter the directory.
        pubky.store["pubky://impostorpk/pub/pubky.app/profile.json"] =
            """{"name":"Impostor","bio":null,"image":null}"""
        tagRepo.usersByTag = mapOf(ReservedTags.USER to listOf("impostorpk"))
        tagRepo.selfTaggers = emptySet()

        assertEquals(emptyList(), repo.loopkyUsers())
    }

    @Test
    fun loopkyUsersKeepsASelfTaggedAccountThatPublishedNoProfile() = runTest {
        // The account this directory exists for: signed up, self-tagged, published nothing — no
        // deck to be inferred from, and no `pubky.app/profile.json`, which signing up to Loopky
        // never had to write. Dropping on that 404 made every such person invisible on Discover
        // while deck authors, whose unresolved profiles are kept by suggestedPeople, stayed.
        tagRepo.usersByTag = mapOf(ReservedTags.USER to listOf("ghostpk"))
        tagRepo.selfTaggers = setOf("ghostpk")

        val users = repo.loopkyUsers()

        assertEquals(listOf("ghostpk"), users.map { it.pubky })
        // Under a bare pubky — the screen renders a truncated key rather than a name.
        assertNull(users.single().displayName)
    }

    @Test
    fun loopkyUsersDropsTheSignedInUser() = runTest {
        tagRepo.usersByTag = mapOf(ReservedTags.USER to listOf(TEST_PUBKY))
        tagRepo.selfTaggers = setOf(TEST_PUBKY)

        assertEquals(emptyList(), repo.loopkyUsers())
    }

    @Test
    fun loopkyUsersFindsDeckOwnersWhenTheProfileTagSearchIsUnavailable() = runTest {
        // Prod today: /v0/search/users/by_tags is not deployed, so the whole directory has to come
        // off the decks. Its 404 must not read as "no Loopky users" (#134).
        tagRepo.usersTaggedError = HttpError(404, "not found")
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                tagged(manifestUri("publisherpk", "deck1"), taggers = listOf("publisherpk")),
                tagged(manifestUri("publisherpk", "deck2"), taggers = listOf("publisherpk")),
            ),
        )
        tagRepo.selfTaggers = setOf("publisherpk")
        pubky.store["pubky://publisherpk/pub/pubky.app/profile.json"] =
            """{"name":"Ada","bio":null,"image":null}"""

        assertEquals(listOf("publisherpk"), repo.loopkyUsers().map { it.pubky })
    }

    @Test
    fun loopkyUsersFindsAnnouncementAuthorsWhoseManifestIsNotIndexed() = runTest {
        tagRepo.usersTaggedError = HttpError(404, "not found")
        tagRepo.postAuthorsByTag = mapOf(ReservedTags.DECK to listOf("announcerpk"))
        tagRepo.selfTaggers = setOf("announcerpk")
        pubky.store["pubky://announcerpk/pub/pubky.app/profile.json"] =
            """{"name":"Ada","bio":null,"image":null}"""

        assertEquals(listOf("announcerpk"), repo.loopkyUsers().map { it.pubky })
    }

    @Test
    fun loopkyUsersUnionsTheThreeSourcesSelfTaggedFirst() = runTest {
        tagRepo.usersByTag = mapOf(ReservedTags.USER to listOf("selfpk"))
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                // Also self-tagged: listed once, in the position its own claim earned it.
                tagged(manifestUri("selfpk", "deck1"), taggers = listOf("selfpk")),
                tagged(manifestUri("publisherpk", "deck2"), taggers = listOf("publisherpk")),
            ),
        )
        tagRepo.postAuthorsByTag = mapOf(ReservedTags.DECK to listOf("publisherpk", "announcerpk"))
        tagRepo.selfTaggers = setOf("selfpk", "publisherpk", "announcerpk")
        for (pk in listOf("selfpk", "publisherpk", "announcerpk")) {
            pubky.store["pubky://$pk/pub/pubky.app/profile.json"] =
                """{"name":"$pk","bio":null,"image":null}"""
        }

        assertEquals(
            listOf("selfpk", "publisherpk", "announcerpk"),
            repo.loopkyUsers().map { it.pubky },
        )
    }

    @Test
    fun loopkyUsersStillVerifiesTheSelfTagOfADeckOwner() = runTest {
        // Publishing a deck is not a claim to be a Loopky user, and a manifest URI can be tagged
        // by anyone — the directory's bar is unchanged whichever source found the candidate.
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(tagged(manifestUri("publisherpk", "deck1"), listOf("publisherpk"))),
        )
        tagRepo.selfTaggers = emptySet()
        pubky.store["pubky://publisherpk/pub/pubky.app/profile.json"] =
            """{"name":"Ada","bio":null,"image":null}"""

        assertEquals(emptyList(), repo.loopkyUsers())
    }

    @Test
    fun loopkyUsersIgnoresATaggedUriThatIsNotADeckManifest() = runTest {
        // The label is untrusted: anyone can point `loopky-deck` at any record, and the owner of
        // an arbitrary URI is not an author of anything.
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                tagged(PubkyUri("pubky://randompk/pub/pubky.app/profile.json"), listOf("randompk")),
            ),
        )
        tagRepo.selfTaggers = setOf("randompk")
        pubky.store["pubky://randompk/pub/pubky.app/profile.json"] =
            """{"name":"Ada","bio":null,"image":null}"""

        assertEquals(emptyList(), repo.loopkyUsers())
    }

    // ── suggested people (directory ∪ deck authors) ──────────────────────

    @Test
    fun suggestedPeopleFallsBackToDeckAuthorsWhenTheDirectoryIsEmpty() = runTest {
        // The directory can still come back empty — nobody self-tagged, or nothing indexed yet —
        // and the seed decks are what keeps the strip from being blank.
        pubky.store["pubky://authorpk/pub/pubky.app/profile.json"] =
            """{"name":"Ada","bio":null,"image":null}"""

        val people = repo.suggestedPeople(seedDecks = listOf(testDeck(authorPubky = "authorpk")))

        assertEquals(listOf("authorpk"), people.map { it.pubky })
        assertEquals("Ada", people.single().displayName)
    }

    @Test
    fun suggestedPeopleKeepsADeckAuthorWithNoPublishedProfile() = runTest {
        // Their deck already fetched and parsed, so the account is real whatever the profile says.
        // Dropping them would empty the strip for exactly the users it exists to serve.
        val people = repo.suggestedPeople(seedDecks = listOf(testDeck(authorPubky = "barepk")))

        assertEquals(listOf("barepk"), people.map { it.pubky })
        assertNull(people.single().displayName)
    }

    @Test
    fun suggestedPeoplePrefersTheDirectoryAndDedupesAgainstIt() = runTest {
        pubky.store["pubky://authorpk/pub/pubky.app/profile.json"] =
            """{"name":"Ada","bio":null,"image":null}"""
        tagRepo.usersByTag = mapOf(ReservedTags.USER to listOf("authorpk"))
        tagRepo.selfTaggers = setOf("authorpk")

        val people = repo.suggestedPeople(seedDecks = listOf(testDeck(authorPubky = "authorpk")))

        // Present in both sources — listed once, from the directory.
        assertEquals(listOf("authorpk"), people.map { it.pubky })
    }

    @Test
    fun suggestedPeopleExcludesYourselfAndAnyoneYouAlreadyFollow() = runTest {
        repo.followUser("friendpk").getOrThrow()

        val people = repo.suggestedPeople(
            seedDecks = listOf(
                testDeck(id = "a", authorPubky = "friendpk"),
                testDeck(id = "b", authorPubky = TEST_PUBKY),
                testDeck(id = "c", authorPubky = "strangerpk"),
            ),
        )

        assertEquals(listOf("strangerpk"), people.map { it.pubky })
    }

    @Test
    fun suggestedPeopleRespectsTheLimit() = runTest {
        val decks = (1..5).map { testDeck(id = "d$it", authorPubky = "author$it") }

        assertEquals(expected = 2, actual = repo.suggestedPeople(decks, limit = 2).size)
    }

    // ── search ───────────────────────────────────────────────────────────

    private fun respondUserSearch(kind: String, prefix: String, pubkys: List<String>) {
        val body = pubkys.joinToString(prefix = "[", postfix = "]") { "\"" + it + "\"" }
        http.respond("$NEXUS_BASE/v0/search/users/$kind/$prefix?limit=10", body)
    }

    /** Every deck in the global sample, tagged by its own author so verification keeps it. */
    private fun sampleOf(vararg decks: Pair<String, String>) {
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to decks.map { (author, deckId) ->
                tagged(manifestUri(author, deckId), taggers = listOf(author))
            },
        )
    }

    @Test
    fun searchPeopleFindsAnAccountByNamePrefix() = runTest {
        respondUserSearch("by_name", "ada", listOf("adapk"))
        tagRepo.selfTaggers = setOf("adapk")

        assertEquals(listOf("adapk"), repo.searchPeople("ada").map { it.pubky })
    }

    @Test
    fun searchPeopleAlsoAsksThePubkyIndexWhenTheQueryCouldBeAKey() = runTest {
        respondUserSearch("by_name", "ybnd", emptyList())
        respondUserSearch("by_id", "ybnd", listOf("ybndkeypk"))
        tagRepo.selfTaggers = setOf("ybndkeypk")

        assertEquals(listOf("ybndkeypk"), repo.searchPeople("ybnd").map { it.pubky })
    }

    @Test
    fun searchPeopleSkipsThePubkyIndexForTextThatCannotBeAKey() = runTest {
        // "l" and "v" are outside z-base-32, so a by_id round-trip could only ever come back empty.
        respondUserSearch("by_name", "lovelace", listOf("adapk"))
        tagRepo.selfTaggers = setOf("adapk")

        repo.searchPeople("lovelace")

        assertTrue(http.requestedUrls.none { it.contains("by_id") }, http.requestedUrls.toString())
    }

    @Test
    fun searchPeopleDropsAccountsThatNeverOpenedLoopky() = runTest {
        // Nexus indexes the whole pubky.app network; the self-tag is what makes a match worth
        // showing, exactly as it is for the directory.
        respondUserSearch("by_name", "ada", listOf("adapk", "pubkyonlypk"))
        tagRepo.selfTaggers = setOf("adapk")

        assertEquals(listOf("adapk"), repo.searchPeople("ada").map { it.pubky })
    }

    @Test
    fun searchPeopleExcludesYourself() = runTest {
        respondUserSearch("by_name", "me", listOf(TEST_PUBKY, "adapk"))
        tagRepo.selfTaggers = setOf(TEST_PUBKY, "adapk")

        assertEquals(listOf("adapk"), repo.searchPeople("me").map { it.pubky })
    }

    @Test
    fun searchPeopleIsEmptyWhenTheIndexerIsUnreachable() = runTest {
        assertEquals(emptyList(), repo.searchPeople("ada"))
    }

    @Test
    fun searchPeopleIgnoresAQueryTooShortToNarrowAnything() = runTest {
        assertEquals(emptyList(), repo.searchPeople("a"))
        assertTrue(http.requestedUrls.isEmpty(), "asked the indexer anyway")
    }

    @Test
    fun searchDecksMatchesATitleAnywhereInIt() = runTest {
        putRemoteManifest("strangerpk", "deck1", updatedAt = 100L, title = "Spanish verbs")
        putRemoteManifest("strangerpk", "deck2", updatedAt = 100L, title = "Chess openings")
        sampleOf("strangerpk" to "deck1", "strangerpk" to "deck2")

        assertEquals(listOf("deck1"), repo.searchDecks("verbs").map { it.id })
    }

    @Test
    fun searchDecksRanksATitlePrefixAboveATagMatch() = runTest {
        putRemoteManifest("strangerpk", "tagged", updatedAt = 100L, title = "Verb drills", tags = listOf(Tag("spanish")))
        putRemoteManifest("strangerpk", "titled", updatedAt = 100L, title = "Spanish verbs")
        sampleOf("strangerpk" to "tagged", "strangerpk" to "titled")

        assertEquals(listOf("titled", "tagged"), repo.searchDecks("spanish").map { it.id })
    }

    @Test
    fun searchDecksReachesPastTheSampleThroughAnExactTag() = runTest {
        putRemoteManifest("strangerpk", "sampled", updatedAt = 100L, title = "Chess openings")
        putRemoteManifest("strangerpk", "obscure", updatedAt = 100L, title = "Endgames", tags = listOf(Tag("chess")))
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(tagged(manifestUri("strangerpk", "sampled"), listOf("strangerpk"))),
            Tag("chess") to listOf(tagged(manifestUri("strangerpk", "obscure"), listOf("strangerpk"))),
        )

        assertEquals(listOf("sampled", "obscure"), repo.searchDecks("chess").map { it.id })
    }

    @Test
    fun searchDecksAsksNoTagIndexForAPhrase() = runTest {
        putRemoteManifest("strangerpk", "deck1", updatedAt = 100L, title = "Spanish verbs")
        sampleOf("strangerpk" to "deck1")

        assertEquals(listOf("deck1"), repo.searchDecks("spanish verbs").map { it.id })
        assertEquals(listOf(ReservedTags.DECK), tagRepo.taggedRequests.map { it.first })
    }

    @Test
    fun searchDecksFetchesTheSampleOnlyOncePerSession() = runTest {
        putRemoteManifest("strangerpk", "deck1", updatedAt = 100L, title = "Spanish verbs")
        sampleOf("strangerpk" to "deck1")

        repo.searchDecks("spanish verbs")
        repo.searchDecks("verbs")

        // One indexer read for the sample; the second query filtered what was already in hand.
        assertEquals(expected = 1, actual = tagRepo.taggedRequests.count { it.first == ReservedTags.DECK })
    }

    // --- announceDeck (#39) ---------------------------------------------------

    @Test
    fun `announceDeck writes a pubky app post linking the deck`() = runTest {
        val deck = testDeck(id = "d1", title = "Kanji N5")

        val uri = repo.announceDeck(
            DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created),
        ).getOrThrow()

        // pubky.app's own namespace, not Loopky's — a Loopky-namespaced record is a generic
        // resource and no cross-app feed reads those (Architecture.md §7.7).
        assertTrue(uri.value.startsWith("pubky://$TEST_PUBKY/pub/pubky.app/posts/"), uri.value)
        val postId = uri.value.substringAfterLast('/')
        assertEquals(POST_ID_LENGTH, postId.length, postId)

        val post = loopkyJson.decodeFromString<PostDto>(pubky.store.getValue(uri.value))
        assertTrue(post.content.contains("Kanji N5"), post.content)
        assertTrue(post.content.contains(deck.pubkyUri.value), post.content)
        assertEquals(deck.pubkyUri.value, post.embed?.uri)
    }

    @Test
    fun `announceDeck never embeds as a repost`() = runTest {
        // A `short` embed is what Nexus reads as a repost, and it then blocks on the embedded URI
        // being an already-indexed post. A deck manifest never is, so the post would sit in the
        // retry queue forever.
        repo.announceDeck(DeckAnnouncement.of(testDeck(), DeckAnnouncement.Kind.Created)).getOrThrow()

        val post = loopkyJson.decodeFromString<PostDto>(pubky.puts.last().second)
        assertEquals(PostKinds.LINK, post.embed?.kind)
    }

    @Test
    fun `the deck cover travels in the body rather than as an attachment`() = runTest {
        val deck = testDeck(
            coverImageRef = testCoverImage().copy(path = "", sha256 = "", url = "https://img.test/c.jpg"),
        )

        repo.announceDeck(DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created)).getOrThrow()

        val post = loopkyJson.decodeFromString<PostDto>(pubky.puts.last().second)
        // pubky.app resolves `attachments` strictly as pubky.app file records, so a URL there is
        // invisible — it renders the first http(s) link in the *content* instead.
        assertNull(post.attachments)
        assertTrue(post.content.contains("https://img.test/c.jpg"), post.content)
        assertEquals(PostKinds.LINK, post.kind)
    }

    @Test
    fun `announceDeck tags the post with the deck topics and loopky-deck`() = runTest {
        val deck = testDeck(id = "d1", tags = listOf(Tag("kanji"), Tag("japanese")))

        val postUri = repo.announceDeck(
            DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created),
        ).getOrThrow()

        // The subject is the *post*, not the manifest — a manifest tag is a generic resource and
        // resource tags never trend (Architecture.md §7.7).
        assertEquals(
            listOf(postUri to Tag("kanji"), postUri to Tag("japanese")),
            tagRepo.putTags,
        )
        assertEquals(listOf(postUri to ReservedTags.DECK), tagRepo.putReservedTags)
    }

    @Test
    fun `a tag that fails still leaves the announcement standing`() = runTest {
        tagRepo.failWith = IllegalStateException("indexer unreachable")

        val result = repo.announceDeck(
            DeckAnnouncement.of(testDeck(tags = listOf(Tag("kanji"))), DeckAnnouncement.Kind.Created),
        )

        assertTrue(result.isSuccess)
        assertTrue(pubky.store.containsKey(result.getOrThrow().value))
    }

    @Test
    fun `announceDeck writes nothing while sharing is off`() = runTest {
        preferences.setShareOnPubky(false)

        val result = repo.announceDeck(
            DeckAnnouncement.of(testDeck(), DeckAnnouncement.Kind.Created),
        )

        // The gate is the write's own invariant, not just the callers': "off" in #39 means nothing
        // reaches the homeserver, however the call got here.
        assertTrue(result.isFailure)
        assertTrue(pubky.puts.none { it.first.contains("/pub/pubky.app/posts/") })
    }

    @Test
    fun `announceDeck reports failure rather than throwing`() = runTest {
        pubky.failNextSessionCallWith = PubkyError("homeserver unreachable")
        // Not retryable-as-session-expiry, so the revalidator does not paper over it.
        val result = repo.announceDeck(
            DeckAnnouncement.of(testDeck(), DeckAnnouncement.Kind.Created),
        )

        assertTrue(result.isFailure)
    }

    private companion object {
        const val NEXUS_BASE = "https://nexus.test"

        /** pubky-app-specs timestamp ids are always 13 Crockford-base32 characters. */
        const val POST_ID_LENGTH = 13
    }

    @Test
    fun followingForgetsThePreviousAccountsFollows() = runTest {
        repo.followUser("friendpk").getOrThrow()
        assertEquals(listOf("friendpk"), repo.following())

        // Same shape as the subscription cache: served before the owner was read, so a fresh
        // pubky inherited whoever the last one followed.
        session.set(null)
        session.set(fakeSession("freshpk"))

        assertEquals(emptyList(), repo.following())
    }
}
