package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.nexus.HttpMethod
import com.github.jvsena42.loopky.data.nexus.HttpRequest
import com.github.jvsena42.loopky.data.nexus.HttpResponse
import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.nexus.NexusResourceSorting
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.report.ReportClient
import com.github.jvsena42.loopky.data.report.ReportRequest
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.repository.TaggedSubject
import com.github.jvsena42.loopky.domain.model.HiddenReason
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReportIssue
import com.github.jvsena42.loopky.domain.model.ReportSubject
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
import com.github.jvsena42.loopky.testing.identityRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveryRepositoryReportTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val blockedAccounts = BlockedAccounts(pubky, session)
    private val reportHttp = FakeHttpFetcher()
    private val nexusHttp = FakeHttpFetcher()
    private val deckRepo = DeckRepositoryImpl(
        pubky = pubky,
        session = session,
        cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined),
        revalidator = revalidator,
        tagRepo = RecordingTagRepository(),
        mediaRepo = FakeMediaRepository(),
        backgroundTasks = FakeBackgroundTasks(),
        deckCache = FakeDeckCacheStore(),
        preferences = FakeAppPreferences(),
        scope = CoroutineScope(SupervisorJob()),
        blockedAccounts = blockedAccounts,
    )
    private val tagRepo = RecordingTagRepository()
    private val repo = DiscoveryRepositoryImpl(
        pubky = pubky,
        session = session,
        revalidator = revalidator,
        deckRepository = deckRepo,
        tagRepository = tagRepo,
        identityRepository = identityRepository(pubky = pubky, sessionProvider = session, tagRepository = tagRepo),
        nexus = NexusClient(http = nexusHttp, baseUrl = NEXUS),
        preferences = FakeAppPreferences(),
        reportClient = ReportClient(reportHttp, "https://staging.pubky.app"),
        environment = PubkyEnvironment.Staging,
        blockedAccounts = blockedAccounts,
    )

    private fun acceptReports() =
        reportHttp.enqueue(HttpMethod.POST, REPORT_URL, HttpResponse(statusCode = 200, body = """{"message":"Success"}"""))

    private fun sentReport(): ReportRequest =
        loopkyJson.decodeFromString(ReportRequest.serializer(), requireNotNull(reportHttp.requests.single().body))

    private fun putRemoteManifest(author: String, deckId: String) {
        val dto = testDeck(id = deckId, authorPubky = author, title = "Deck $deckId").toDto()
        pubky.store["pubky://$author/pub/loopky/decks/$deckId/manifest.json"] = loopkyJson.encodeToString(dto)
    }

    private fun follow(followee: String) {
        pubky.store["pubky://$TEST_PUBKY/pub/pubky.app/follows/$followee"] = """{"created_at":1}"""
    }

    private fun reportedOnNexus(reported: String, taggers: List<String>) {
        nexusHttp.respond(
            "$NEXUS/v0/search/users/by_tags?tags=loopky-reported&limit=100",
            """[{"user_id":"$reported","score":1}]""",
        )
        nexusHttp.respond(
            "$NEXUS/v0/user/$reported/taggers/loopky-reported?limit=100",
            """{"users":${taggers.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}}""",
        )
    }

    @Test
    fun `a profile report goes to pubky app with the pubky app profile url`() = runTest {
        acceptReports()

        val outcome = repo.report(ReportSubject.Profile("trollpk"), ReportIssue.Harassment, "  rude  ").getOrThrow()

        val sent = sentReport()
        assertEquals(TEST_PUBKY, sent.pubky)
        assertEquals("https://staging.pubky.app/profile/trollpk", sent.postUrl)
        assertEquals("harassment", sent.issueType)
        assertTrue(sent.reason.startsWith("Loopky profile: pubky://trollpk/pub/pubky.app/profile.json"))
        assertTrue(sent.reason.endsWith("\n\nrude"))
        assertTrue(sent.name.isNotBlank())
        assertTrue(outcome.blocked)
    }

    @Test
    fun `a report tags the profile and blocks the account`() = runTest {
        acceptReports()

        repo.report(ReportSubject.Profile("trollpk"), ReportIssue.HateSpeech, "").getOrThrow()

        assertEquals(
            listOf(PubkyUri("pubky://trollpk/pub/pubky.app/profile.json") to ReservedTags.REPORTED),
            tagRepo.putReservedTags,
        )
        assertTrue(repo.isBlocked("trollpk"))
    }

    @Test
    fun `a deck report sends the deck link and blocks its author`() = runTest {
        acceptReports()
        val deck = testDeck(id = "baddeck", authorPubky = "trollpk", title = "Bad")

        repo.report(ReportSubject.OfDeck(deck), ReportIssue.Copyright, "copied").getOrThrow()

        val sent = sentReport()
        assertEquals(deck.webUrl, sent.postUrl)
        assertTrue(sent.reason.startsWith("Loopky deck \"Bad\": pubky://trollpk/pub/loopky/decks/baddeck/"))
        assertTrue(repo.isBlocked("trollpk"))
    }

    @Test
    fun `a report the server refuses blocks nothing and tags nothing`() = runTest {
        reportHttp.enqueue(HttpMethod.POST, REPORT_URL, HttpResponse(statusCode = 400, body = "{}"))

        assertTrue(repo.report(ReportSubject.Profile("trollpk"), ReportIssue.Violence, "").isFailure)

        assertFalse(repo.isBlocked("trollpk"))
        assertTrue(tagRepo.putReservedTags.isEmpty())
    }

    @Test
    fun `you cannot report yourself`() = runTest {
        assertTrue(repo.report(ReportSubject.Profile(TEST_PUBKY), ReportIssue.Violence, "").isFailure)
        assertTrue(reportHttp.requests.isEmpty())
    }

    @Test
    fun `a guest cannot report`() = runTest {
        session.set(null)

        assertTrue(repo.report(ReportSubject.Profile("trollpk"), ReportIssue.Violence, "").isFailure)
        assertTrue(reportHttp.requests.isEmpty())
    }

    @Test
    fun `an account a followee reported is left out of global browse`() = runTest {
        follow("friendpk")
        reportedOnNexus("trollpk", taggers = listOf("friendpk"))
        putRemoteManifest(author = "trollpk", deckId = "bad")
        putRemoteManifest(author = "otherpk", deckId = "good")
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                TaggedSubject(PubkyUri("pubky://trollpk/pub/loopky/decks/bad/manifest.json"), listOf("trollpk"), 1),
                TaggedSubject(PubkyUri("pubky://otherpk/pub/loopky/decks/good/manifest.json"), listOf("otherpk"), 1),
            ),
        )

        assertEquals(listOf("good"), repo.decksByTagGlobal(ReservedTags.DECK).map { it.id })
        assertEquals(HiddenReason.ReportedByFollowee, repo.hiddenReason("trollpk"))
    }

    @Test
    fun `a report by a stranger hides nobody`() = runTest {
        follow("friendpk")
        reportedOnNexus("trollpk", taggers = listOf("strangerpk"))

        assertEquals(null, repo.hiddenReason("trollpk"))
    }

    @Test
    fun `an account you follow is not hidden by a friend's report`() = runTest {
        follow("friendpk")
        follow("trollpk")
        reportedOnNexus("trollpk", taggers = listOf("friendpk"))

        assertEquals(null, repo.hiddenReason("trollpk"))
    }

    private fun flaggedOnNexus(flagged: String, label: String, taggers: List<String>) {
        nexusHttp.respond(
            "$NEXUS/v0/search/users/by_tags?tags=$label&limit=100",
            """[{"user_id":"$flagged","score":1}]""",
        )
        nexusHttp.respond(
            "$NEXUS/v0/user/$flagged/taggers/$label?limit=1&viewer_id=$BOT",
            """{"users":[],"relationship":${BOT in taggers}}""",
        )
    }

    @Test
    fun `an indexer that was unreachable once does not switch moderation off for the process`() = runTest {
        session.set(null)
        putRemoteManifest(author = "trollpk", deckId = "bad")
        putRemoteManifest(author = "otherpk", deckId = "good")
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                TaggedSubject(PubkyUri("pubky://trollpk/pub/loopky/decks/bad/manifest.json"), listOf("trollpk"), 1),
                TaggedSubject(PubkyUri("pubky://otherpk/pub/loopky/decks/good/manifest.json"), listOf("otherpk"), 1),
            ),
        )
        // Offline: no by_tags URL answers, so every label search fails.
        repo.decksByTagGlobal(ReservedTags.DECK)

        flaggedOnNexus("trollpk", "violence", taggers = listOf(BOT))

        assertEquals(listOf("good"), repo.decksByTagGlobal(ReservedTags.DECK).map { it.id })
    }

    @Test
    fun `a bot flag past the first page of taggers still hides the account`() = runTest {
        val strangers = (1..100).map { "stranger$it" }
        val page = "$NEXUS/v0/user/trollpk/taggers/violence?limit=100"
        nexusHttp.respond(page, """{"users":${strangers.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}}""")
        nexusHttp.respond("$NEXUS/v0/user/trollpk/taggers/violence?limit=1&viewer_id=$BOT", """{"users":[],"relationship":true}""")

        assertEquals(HiddenReason.FlaggedByModerators, repo.hiddenReason("trollpk"))
    }

    @Test
    fun `an account the moderation bot flagged is hidden for a guest too`() = runTest {
        session.set(null)
        flaggedOnNexus("trollpk", "violence", taggers = listOf(BOT))
        putRemoteManifest(author = "trollpk", deckId = "bad")
        putRemoteManifest(author = "otherpk", deckId = "good")
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                TaggedSubject(PubkyUri("pubky://trollpk/pub/loopky/decks/bad/manifest.json"), listOf("trollpk"), 1),
                TaggedSubject(PubkyUri("pubky://otherpk/pub/loopky/decks/good/manifest.json"), listOf("otherpk"), 1),
            ),
        )

        assertEquals(listOf("good"), repo.decksByTagGlobal(ReservedTags.DECK).map { it.id })
        assertEquals(HiddenReason.FlaggedByModerators, repo.hiddenReason("trollpk"))
    }

    @Test
    fun `a violation label written by anyone but the bot hides nobody`() = runTest {
        flaggedOnNexus("trollpk", "violence", taggers = listOf("strangerpk"))

        assertEquals(null, repo.hiddenReason("trollpk"))
    }

    @Test
    fun `a moderation flag wins over a friend's report`() = runTest {
        follow("friendpk")
        reportedOnNexus("trollpk", taggers = listOf("friendpk"))
        flaggedOnNexus("trollpk", "nudity", taggers = listOf(BOT))

        assertEquals(HiddenReason.FlaggedByModerators, repo.hiddenReason("trollpk"))
    }

    @Test
    fun `a failed candidates read is not remembered for the session`() = runTest {
        follow("friendpk")
        putRemoteManifest(author = "trollpk", deckId = "bad")
        putRemoteManifest(author = "otherpk", deckId = "good")
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                TaggedSubject(PubkyUri("pubky://trollpk/pub/loopky/decks/bad/manifest.json"), listOf("trollpk"), 1),
                TaggedSubject(PubkyUri("pubky://otherpk/pub/loopky/decks/good/manifest.json"), listOf("otherpk"), 1),
            ),
        )
        val candidates = "$NEXUS/v0/search/users/by_tags?tags=loopky-reported&limit=100"
        nexusHttp.enqueueFailure(HttpMethod.GET, candidates, IllegalStateException("offline"))
        nexusHttp.enqueue(
            HttpMethod.GET,
            candidates,
            HttpResponse(statusCode = 200, body = """[{"user_id":"trollpk","score":1}]"""),
        )
        nexusHttp.respond("$NEXUS/v0/user/trollpk/taggers/loopky-reported?limit=100", """{"users":["friendpk"]}""")

        assertEquals(setOf("bad", "good"), repo.decksByTagGlobal(ReservedTags.DECK).map { it.id }.toSet())
        assertEquals(listOf("good"), repo.decksByTagGlobal(ReservedTags.DECK).map { it.id })
    }

    @Test
    fun `a browse page reads friends' reports alongside its first page`() = runTest {
        follow("friendpk")
        reportedOnNexus("trollpk", taggers = listOf("friendpk"))
        putRemoteManifest(author = "trollpk", deckId = "bad")
        putRemoteManifest(author = "otherpk", deckId = "good")
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                TaggedSubject(PubkyUri("pubky://trollpk/pub/loopky/decks/bad/manifest.json"), listOf("trollpk"), 1),
                TaggedSubject(PubkyUri("pubky://otherpk/pub/loopky/decks/good/manifest.json"), listOf("otherpk"), 1),
            ),
        )
        // Not `by nexusHttp`: delegation would route the default `get` straight to the fake.
        val slowNexus = object : HttpFetcher {
            override suspend fun send(request: HttpRequest) =
                nexusHttp.send(request).also { if ("by_tags" in request.url) delay(SLOW_MS) }
        }
        val slowTags = object : TagRepository by tagRepo {
            override suspend fun taggedSubjects(tag: Tag, limit: Int, skip: Int, sorting: NexusResourceSorting) =
                tagRepo.taggedSubjects(tag, limit, skip, sorting).also { if (skip == 0) delay(SLOW_MS) }
        }
        val slowRepo = DiscoveryRepositoryImpl(
            pubky = pubky,
            session = session,
            revalidator = revalidator,
            deckRepository = deckRepo,
            tagRepository = slowTags,
            identityRepository = identityRepository(pubky = pubky, sessionProvider = session, tagRepository = slowTags),
            nexus = NexusClient(http = slowNexus, baseUrl = NEXUS),
            preferences = FakeAppPreferences(),
            reportClient = ReportClient(reportHttp, "https://staging.pubky.app"),
            environment = PubkyEnvironment.Staging,
            blockedAccounts = blockedAccounts,
        )

        val page = slowRepo.decksByTagGlobalPage(ReservedTags.DECK, limit = 2, cursor = 0, alsoTagged = emptySet())

        assertEquals(listOf("good"), page.decks.map { it.id })
        assertTrue(currentTime < 2 * SLOW_MS, "the page waited ${currentTime}ms: the two reads ran one after the other")
    }

    private companion object {
        const val NEXUS = "https://nexus.test"
        const val REPORT_URL = "https://staging.pubky.app/api/report"
        const val SLOW_MS = 1_000L
        val BOT = PubkyEnvironment.Staging.moderationBotPubky
    }
}
