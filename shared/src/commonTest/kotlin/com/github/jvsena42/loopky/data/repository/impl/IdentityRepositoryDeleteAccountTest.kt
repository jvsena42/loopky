package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.PubkyLinks
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.repository.PubkyAppFootprintChangedException
import com.github.jvsena42.loopky.domain.model.DailyStudyProgress
import com.github.jvsena42.loopky.domain.model.PubkyAppFootprint
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeDeckRepository
import com.github.jvsena42.loopky.testing.FakePendingReviewStore
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.FakeStudyProgressStore
import com.github.jvsena42.loopky.testing.FakeUnsplashKeyStore
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.identityRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What "delete my account" is allowed to reach, and — more importantly — what it is not.
 *
 * The blast radius is the whole point of these tests. The sweep runs over the same homeserver that
 * holds the user's pubky.app identity and social graph, which belong to every Pubky app rather
 * than to Loopky, and a delete is not undoable.
 */
class IdentityRepositoryDeleteAccountTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val tags = RecordingTagRepository()
    private val decks = FakeDeckRepository()
    private val pendingReviews = FakePendingReviewStore()
    private val studyProgress = FakeStudyProgressStore()
    private val preferences = FakeAppPreferences()
    private val unsplashKey = FakeUnsplashKeyStore()

    private val repo = identityRepository(
        pubky = pubky,
        sessionProvider = session,
        tagRepository = tags,
        deckRepository = decks,
        pendingReviews = pendingReviews,
        studyProgress = studyProgress,
        preferences = preferences,
        unsplashKeyStore = unsplashKey,
    )

    private val loopkyRoot = "pubky://$TEST_PUBKY/${PubkyPaths.APP_NAMESPACE}"

    private fun seedHomeserver() {
        // Loopky's own namespace: review state, settings, a subscription, a tag record.
        pubky.store["$loopkyRoot/srs/$TEST_PUBKY/deck1/0.json"] = "{}"
        pubky.store["$loopkyRoot/settings.json"] = "{}"
        pubky.store["$loopkyRoot/subscriptions/otherpk/deck9.json"] = "{}"
        pubky.store["$loopkyRoot/tags/abc123"] = "{}"
        // Not Loopky's: the wider Pubky identity and social graph.
        pubky.store[PubkyPaths.profile(TEST_PUBKY)] = """{"name":"Tester","bio":null,"image":null}"""
        pubky.store[PubkyPaths.follow(TEST_PUBKY, "friendpk")] = "{}"
    }

    @Test
    fun everythingUnderTheLoopkyNamespaceIsRemoved() = runTest {
        seedHomeserver()

        repo.deleteAccount().getOrThrow()

        assertTrue(
            pubky.store.keys.none { it.startsWith("$loopkyRoot/") },
            "left behind: ${pubky.store.keys.filter { it.startsWith("$loopkyRoot/") }}",
        )
    }

    @Test
    fun aSubscriptionNestedTwoLevelsDeepIsRemoved() = runTest {
        // Found on device: a followed deck survived two sweeps that both reported success. One
        // listing of `pub/loopky/` did not return `subscriptions/{author}/{deckId}.json`, so the
        // record was never even attempted — and because nothing re-checked, "deleted" was reported
        // over data that was still there. Each sub-root is listed in its own right now.
        val subscription = PubkyPaths.subscription(TEST_PUBKY, "otherpk", "deck9")
        pubky.store[subscription] = "{}"

        repo.deleteAccount().getOrThrow()

        assertFalse(subscription in pubky.store)
    }

    @Test
    fun aSweepThatLeavesSomethingBehindReportsFailure() = runTest {
        // The counters cannot prove completeness: deleteRecord scores a 404 as success, so a record
        // the listing never returned looks identical to one already gone. Only re-listing can tell.
        pubky.store["$loopkyRoot/settings.json"] = "{}"
        pubky.undeletablePaths += "$loopkyRoot/settings.json"

        val result = repo.deleteAccount()

        assertTrue(result.isFailure, "a sweep must not claim success over surviving records")
        assertEquals(TEST_PUBKY, session.current()?.identity?.pubky, "and must leave the user signed in")
    }

    @Test
    fun theProfileAndSocialFollowsAreLeftAlone() = runTest {
        seedHomeserver()

        repo.deleteAccount().getOrThrow()

        // These are the user's presence in every other Pubky app. Deleting them would be Loopky
        // reaching well past what it was asked to remove.
        assertTrue(PubkyPaths.profile(TEST_PUBKY) in pubky.store, "profile.json is not Loopky's")
        assertTrue(PubkyPaths.follow(TEST_PUBKY, "friendpk") in pubky.store, "follows are not Loopky's")
    }

    @Test
    fun everyOwnedDeckGoesThroughTheDeckRepositoryRatherThanARawSweep() = runTest {
        // DeckRepository.delete takes the per-deck lock, deletes the manifest last, and clears the
        // deck's tag records. A raw path sweep does none of that.
        decks.decks["deck1"] = testDeck(id = "deck1")
        decks.decks["deck2"] = testDeck(id = "deck2")

        repo.deleteAccount().getOrThrow()

        assertEquals(listOf("deck1", "deck2"), decks.deleted.sorted())
    }

    @Test
    fun theLoopkyUserSelfTagIsRemovedSoTheAccountLeavesTheDirectory() = runTest {
        val profileUri = PubkyUri(PubkyPaths.profile(TEST_PUBKY))
        tags.putReservedTag(profileUri, ReservedTags.USER)

        repo.deleteAccount().getOrThrow()

        // Without this the account keeps turning up in Discover and search after deletion — the
        // tag index is the only user directory Loopky has.
        assertFalse(tags.isSelfTagged(TEST_PUBKY, ReservedTags.USER))
    }

    @Test
    fun anAnnouncementPostForOwnDecksIsRemoved() = runTest {
        val post = PubkyPaths.post(TEST_PUBKY, "post1")
        pubky.store[post] = announcement("$loopkyRoot/decks/deck1/manifest.json")

        repo.deleteAccount().getOrThrow()

        assertFalse(post in pubky.store, "an announcement would link to a deck that no longer resolves")
    }

    @Test
    fun anOrdinaryPostIsNeverTouched() = runTest {
        // posts/ is the user's whole pubky.app feed. Matching on anything looser than "embeds one
        // of my own deck manifests" deletes writing that has nothing to do with Loopky.
        val plain = PubkyPaths.post(TEST_PUBKY, "post2")
        val aboutSomeoneElsesDeck = PubkyPaths.post(TEST_PUBKY, "post3")
        pubky.store[plain] = """{"content":"good morning","kind":"short"}"""
        pubky.store[aboutSomeoneElsesDeck] =
            announcement("pubky://otherpk/${PubkyPaths.APP_NAMESPACE}/decks/deck1/manifest.json")

        repo.deleteAccount().getOrThrow()

        assertTrue(plain in pubky.store)
        assertTrue(aboutSomeoneElsesDeck in pubky.store, "someone else's deck is not our announcement")
    }

    @Test
    fun aFollowAnnouncementCarryingTheDeckLabelIsRemovedWithItsLabels() = runTest {
        val post = PubkyPaths.post(TEST_PUBKY, "post4")
        pubky.store[post] = announcement("pubky://otherpk/${PubkyPaths.APP_NAMESPACE}/decks/deck1/manifest.json")
        val deckLabel = labelOn(post, "loopky-deck")
        val topic = labelOn(post, "spanish")

        repo.deleteAccount().getOrThrow()

        assertFalse(post in pubky.store)
        assertFalse(deckLabel in pubky.store, "a label on a deleted post indexes nothing")
        assertFalse(topic in pubky.store)
    }

    @Test
    fun aRecapPostIsRemovedWithItsLabel() = runTest {
        // Recaps link to the site, not to a deck, so the old own-deck match never saw them.
        val post = PubkyPaths.post(TEST_PUBKY, "recap1")
        pubky.store[post] = linkPost(PubkyLinks.recapWebUrl(TEST_PUBKY, 2026))
        val label = labelOn(post, "loopky-recap")

        repo.deleteAccount().getOrThrow()

        assertFalse(post in pubky.store)
        assertFalse(label in pubky.store)
    }

    @Test
    fun aDeckRequestIsRemovedWithItsLabels() = runTest {
        val post = PubkyPaths.post(TEST_PUBKY, "request1")
        pubky.store[post] = """{"content":"Someone make a deck about tides","kind":"short"}"""
        val labels = listOf(labelOn(post, "loopky"), labelOn(post, "deck-request"))

        repo.deleteAccount().getOrThrow()

        assertFalse(post in pubky.store)
        labels.forEach { assertFalse(it in pubky.store) }
    }

    @Test
    fun aShortPostWithOnlyOneDeckRequestLabelIsKept() = runTest {
        val post = PubkyPaths.post(TEST_PUBKY, "post5")
        pubky.store[post] = """{"content":"I love loopky","kind":"short"}"""
        val label = labelOn(post, "loopky")

        repo.deleteAccount().getOrThrow()

        assertTrue(post in pubky.store, "a post the user labelled loopky on pubky.app is theirs")
        assertTrue(label in pubky.store, "and so is its label")
    }

    @Test
    fun someoneElsesRecapSharedByTheUserIsKept() = runTest {
        val post = PubkyPaths.post(TEST_PUBKY, "post6")
        pubky.store[post] = linkPost(PubkyLinks.recapWebUrl("otherpk", 2026))

        repo.deleteAccount().getOrThrow()

        assertTrue(post in pubky.store)
    }

    @Test
    fun aPostThatCannotBeReadIsKept() = runTest {
        val unreadable = PubkyPaths.post(TEST_PUBKY, "post7")
        pubky.store[unreadable] = "{not json"

        repo.deleteAccount().getOrThrow()

        assertTrue(unreadable in pubky.store)
    }

    @Test
    fun anUnreachablePostIsNotDeleted() = runTest {
        // A failed read must never turn into a delete.
        val post = PubkyPaths.post(TEST_PUBKY, "post8")
        pubky.store[post] = announcement("$loopkyRoot/decks/deck1/manifest.json")
        pubky.failGetWhenUrlContains = "/posts/post8"

        repo.deleteAccount().getOrThrow()

        assertTrue(post in pubky.store)
    }

    @Test
    fun labelsOnAnythingButALoopkyPostAreLeftAlone() = runTest {
        val ownPost = PubkyPaths.post(TEST_PUBKY, "post9")
        pubky.store[ownPost] = """{"content":"hello","kind":"short"}"""
        val onOwnPost = labelOn(ownPost, "greeting")
        val onFriendsPost = labelOn(PubkyPaths.post("friendpk", "p1"), "funny")
        val onFriend = labelOn(PubkyPaths.profile("friendpk"), "climber")

        repo.deleteAccount().getOrThrow()

        assertTrue(onOwnPost in pubky.store)
        assertTrue(onFriendsPost in pubky.store)
        assertTrue(onFriend in pubky.store)
    }

    @Test
    fun localStateIsClearedButAnUnspentSignupTokenIsNot() = runTest {
        pendingReviews.save(listOf(pendingReview()))
        studyProgress.save(TEST_PUBKY, DailyStudyProgress(dayIndex = 7, newCards = 3, reviews = 12))
        preferences.setCachedStudySettings("""{"newCardsPerDayGoal":40}""")
        unsplashKey.save("a-key")

        repo.deleteAccount().getOrThrow()

        assertTrue(pendingReviews.load().isEmpty())
        assertEquals(0, studyProgress.load(TEST_PUBKY)?.reviews)
        assertEquals("", preferences.cachedStudySettings.first())
        assertEquals("", unsplashKey.key.first())
        // The token is deliberately absent from this flow: it cost sats or one of two SMS
        // verifications a week, never expires, and still redeems against a new account.
    }

    @Test
    fun theSessionIsClearedOnlyAfterTheSweepSucceeds() = runTest {
        seedHomeserver()

        repo.deleteAccount().getOrThrow()

        assertEquals(null, session.current())
    }

    @Test
    fun aFailedSweepLeavesTheUserSignedIn() = runTest {
        // Signed out and half-deleted is unrecoverable: signing back in is what a retry needs, and
        // the records left behind are unreachable until then.
        decks.decks["deck1"] = testDeck(id = "deck1")
        decks.deleteError = IllegalStateException("homeserver unreachable")
        pubky.failAllSessionCallsWith = IllegalStateException("homeserver unreachable")

        repo.deleteAccount()

        assertEquals(TEST_PUBKY, session.current()?.identity?.pubky)
    }

    @Test
    fun deletingWithoutASessionFails() = runTest {
        session.set(null)

        val result = repo.deleteAccount()

        assertTrue(result.isFailure)
    }

    @Test
    fun progressIsReportedAsRecordsGo() = runTest {
        decks.decks["deck1"] = testDeck(id = "deck1")
        seedHomeserver()

        val reports = mutableListOf<Pair<Int, Int>>()
        repo.deleteAccount { done, total -> reports += done to total }.getOrThrow()

        assertTrue(reports.isNotEmpty(), "a sweep with no feedback is a frozen dialog")
        // Monotonic, so a progress bar never jumps backwards.
        assertEquals(reports.map { it.first }.sorted(), reports.map { it.first })
    }

    // --- Deleting pubky.app too -------------------------------------------------------------------

    /** A Loopky-only pubky.app footprint, beside Loopky's own namespace. */
    private fun seedLoopkyOnlyPubkyApp() {
        seedHomeserver()
        pubky.store[PubkyPaths.mute(TEST_PUBKY, "trollpk")] = "{}"
        labelOn(PubkyPaths.profile("trollpk"), "loopky-reported")
        labelOn(PubkyPaths.profile("friendpk"), "climber")
        pubky.store[PubkyPaths.post(TEST_PUBKY, "post1")] = announcement("$loopkyRoot/decks/deck1/manifest.json")
    }

    private val pubkyAppRoot = "pubky://$TEST_PUBKY/pub/pubky.app/"

    private fun pubkyAppLeft() = pubky.store.keys.filter { it.startsWith(pubkyAppRoot) }

    @Test
    fun aLoopkyOnlyAccountIsReadAsOne() = runTest {
        seedLoopkyOnlyPubkyApp()

        assertEquals(PubkyAppFootprint.LoopkyOnly, repo.pubkyAppFootprint())
    }

    @Test
    fun deletingPubkyAppTooLeavesNothingUnderIt() = runTest {
        seedLoopkyOnlyPubkyApp()

        repo.deleteAccount(deletePubkyApp = true).getOrThrow()

        assertEquals(emptyList(), pubkyAppLeft())
        assertTrue(pubky.store.keys.none { it.startsWith("$loopkyRoot/") })
        assertEquals(null, session.current())
    }

    @Test
    fun theProfileIsTheLastPubkyAppRecordDeleted() = runTest {
        // A sweep that dies part way then still has a profile, reads as Loopky's, and a retry is
        // offered the same choice.
        seedLoopkyOnlyPubkyApp()

        repo.deleteAccount(deletePubkyApp = true).getOrThrow()

        assertEquals(PubkyPaths.profile(TEST_PUBKY), pubky.deletes.last { it.startsWith(pubkyAppRoot) })
    }

    @Test
    fun deletingPubkyAppNeverReachesAnotherAccount() = runTest {
        seedLoopkyOnlyPubkyApp()
        val friendsProfile = PubkyPaths.profile("friendpk")
        pubky.store[friendsProfile] = """{"name":"Friend"}"""
        val friendsFollow = PubkyPaths.follow("friendpk", TEST_PUBKY)
        pubky.store[friendsFollow] = "{}"

        repo.deleteAccount(deletePubkyApp = true).getOrThrow()

        assertTrue(friendsProfile in pubky.store)
        assertTrue(friendsFollow in pubky.store)
        assertTrue(pubky.deletes.none { it.startsWith("pubky://friendpk/") }, "${pubky.deletes}")
    }

    @Test
    fun anAccountAnotherAppWroteToSinceTheDialogOpenedIsNotTouched() = runTest {
        // The dialog read LoopkyOnly; by the time the user confirmed, they had posted on pubky.app.
        seedLoopkyOnlyPubkyApp()
        decks.decks["deck1"] = testDeck(id = "deck1")
        pubky.store[PubkyPaths.post(TEST_PUBKY, "fresh")] = """{"content":"good morning","kind":"short"}"""

        val result = repo.deleteAccount(deletePubkyApp = true)

        val error = result.exceptionOrNull()
        assertTrue(error is PubkyAppFootprintChangedException, "$error")
        assertEquals(PubkyAppFootprint.Direct, error.now)
        assertEquals(emptyList(), pubky.deletes, "nothing may be deleted on a refusal")
        assertEquals(emptyList(), decks.deleted)
        assertEquals(TEST_PUBKY, session.current()?.identity?.pubky)
    }

    @Test
    fun anAccountThatCannotBeReadIsNotTouchedEither() = runTest {
        seedLoopkyOnlyPubkyApp()
        pubky.failGetWhenUrlContains = "profile.json"

        val error = repo.deleteAccount(deletePubkyApp = true).exceptionOrNull()

        assertTrue(error is PubkyAppFootprintChangedException, "$error")
        assertEquals(PubkyAppFootprint.Unknown, error.now)
        assertEquals(emptyList(), pubky.deletes)
    }

    @Test
    fun anotherAppsNamespaceRefusesThePubkyAppDelete() = runTest {
        seedLoopkyOnlyPubkyApp()
        pubky.store["pubky://$TEST_PUBKY/pub/bitkit.to/profile.json"] = "{}"

        val error = repo.deleteAccount(deletePubkyApp = true).exceptionOrNull()

        assertTrue(error is PubkyAppFootprintChangedException, "$error")
        assertTrue("pubky://$TEST_PUBKY/pub/bitkit.to/profile.json" in pubky.store)
    }

    @Test
    fun anEmptyPubkyAppIsDeletedWithoutComplaint() = runTest {
        pubky.store["$loopkyRoot/settings.json"] = "{}"

        repo.deleteAccount(deletePubkyApp = true).getOrThrow()

        assertEquals(null, session.current())
    }

    @Test
    fun aPubkyAppRecordThatWillNotGoFailsTheDeleteAndKeepsTheUserSignedIn() = runTest {
        seedLoopkyOnlyPubkyApp()
        val stuck = PubkyPaths.follow(TEST_PUBKY, "friendpk")
        pubky.undeletablePaths += stuck

        val result = repo.deleteAccount(deletePubkyApp = true)

        assertTrue(result.isFailure)
        assertEquals(TEST_PUBKY, session.current()?.identity?.pubky)
        assertTrue(stuck in pubky.store)
    }

    @Test
    fun withoutTheChoiceALoopkyOnlyProfileFollowsAndBlocksStay() = runTest {
        seedLoopkyOnlyPubkyApp()

        repo.deleteAccount().getOrThrow()

        assertTrue(PubkyPaths.profile(TEST_PUBKY) in pubky.store)
        assertTrue(PubkyPaths.follow(TEST_PUBKY, "friendpk") in pubky.store)
        assertTrue(PubkyPaths.mute(TEST_PUBKY, "trollpk") in pubky.store)
        // The Loopky post still goes either way.
        assertFalse(PubkyPaths.post(TEST_PUBKY, "post1") in pubky.store)
    }

    @Test
    fun progressCountsThePubkyAppRecords() = runTest {
        seedLoopkyOnlyPubkyApp()

        val reports = mutableListOf<Pair<Int, Int>>()
        repo.deleteAccount(deletePubkyApp = true) { done, total -> reports += done to total }.getOrThrow()

        val total = reports.first().second
        assertTrue(reports.size >= pubkyAppLeft().size + 6, "$reports")
        assertTrue(reports.all { it.second == total })
    }

    @Test
    fun theFootprintIsUnknownWithoutASession() = runTest {
        seedLoopkyOnlyPubkyApp()
        session.set(null)

        assertEquals(PubkyAppFootprint.Unknown, repo.pubkyAppFootprint())
    }

    @Test
    fun theFootprintIsUnknownWhenTheHomeserverIsUnreachable() = runTest {
        seedLoopkyOnlyPubkyApp()
        pubky.failListWith = PubkyError("HTTP transport error: connection refused")

        assertEquals(PubkyAppFootprint.Unknown, repo.pubkyAppFootprint())
    }

    private fun announcement(deckUri: String): String = linkPost(deckUri)

    private fun linkPost(uri: String): String =
        """{"content":"new deck","kind":"link","embed":{"kind":"link","uri":"$uri"}}"""

    /** Write a pubky.app tag record by [TEST_PUBKY] on [subject], and return its path. */
    private fun labelOn(subject: String, label: String): String {
        val path = PubkyPaths.tag(TEST_PUBKY, "tag-${(subject + label).hashCode().toUInt()}")
        pubky.store[path] = """{"uri":"$subject","label":"$label","created_at":1}"""
        return path
    }

    private fun pendingReview() = com.github.jvsena42.loopky.data.storage.PendingReview(
        authorPubky = TEST_PUBKY,
        deckId = "deck1",
        chunk = 0,
        cardId = "c1",
        dueAt = 0,
        intervalDays = 1,
        easeFactor = 2.5,
        repetitions = 1,
    )
}
