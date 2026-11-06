package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.PubkyLinks
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.domain.model.PubkyAppFootprint
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether an account holds anything another Pubky app wrote. [PubkyAppFootprint.LoopkyOnly] is
 * the one answer that lets Delete account offer to remove pubky.app records, so every way of
 * reaching it wrongly is a test here.
 */
class PubkyAppFootprintReaderTest {

    private val pubky = FakePubkyClient()
    private val reader = PubkyAppFootprintReader(pubky)
    private val owner = TEST_PUBKY
    private val pubkyApp = "pubky://$owner/pub/pubky.app"

    private suspend fun classify() = reader.classify(owner)

    private fun profile(json: String = """{"name":"Ana","bio":"hi"}""") {
        pubky.store[PubkyPaths.profile(owner)] = json
    }

    private fun post(id: String, json: String): String =
        PubkyPaths.post(owner, id).also { pubky.store[it] = json }

    private fun linkPost(id: String, uri: String): String =
        post(id, """{"content":"x","kind":"link","embed":{"kind":"link","uri":"$uri"}}""")

    private fun label(subject: String, label: String): String {
        val path = PubkyPaths.tag(owner, "tag-${(subject + label).hashCode().toUInt()}")
        pubky.store[path] = """{"uri":"$subject","label":"$label","created_at":1}"""
        return path
    }

    private fun follows(count: Int) =
        repeat(count) { pubky.store[PubkyPaths.follow(owner, "friend" + it.toString().padStart(4, '0'))] = "{}" }

    /** One of every record kind Loopky writes under pubky.app. */
    private fun seedEverythingLoopkyWrites() {
        profile()
        pubky.store[PubkyPaths.follow(owner, "friendpk")] = """{"created_at":1}"""
        pubky.store[PubkyPaths.mute(owner, "trollpk")] = """{"created_at":1}"""
        label(PubkyPaths.profile(owner), "loopky-user")
        label(PubkyPaths.profile("trollpk"), "loopky-reported")
        label(PubkyPaths.profile("friendpk"), "climber")
        label("pubky://friendpk/pub/loopky/decks/d1/manifest.json", "spanish")
        val own = linkPost("p1", "pubky://$owner/pub/loopky/decks/deck1/manifest.json")
        label(own, "loopky-deck")
        label(own, "spanish")
        val followed = linkPost("p2", "pubky://friendpk/pub/loopky/decks/d1/manifest.json")
        label(followed, "loopky-deck")
        val recap = linkPost("p3", PubkyLinks.recapWebUrl(owner, 2026))
        label(recap, "loopky-recap")
        val request = post("p4", """{"content":"a deck about tides","kind":"short"}""")
        label(request, "loopky")
        label(request, "deck-request")
        // Loopky's own namespace is never a sign of another app.
        pubky.store["pubky://$owner/pub/loopky/settings.json"] = "{}"
    }

    // --- Empty and Loopky-only --------------------------------------------------------------------

    @Test
    fun anAccountWithNothingIsEmpty() = runTest {
        assertEquals(PubkyAppFootprint.Empty, classify())
    }

    @Test
    fun anAccountWithOnlyLoopkysNamespaceIsEmpty() = runTest {
        pubky.store["pubky://$owner/pub/loopky/decks/deck1/manifest.json"] = "{}"

        assertEquals(PubkyAppFootprint.Empty, classify())
    }

    @Test
    fun aLoopkyProfileAloneIsLoopkyOnly() = runTest {
        profile()

        assertEquals(PubkyAppFootprint.LoopkyOnly, classify())
    }

    @Test
    fun everyRecordKindLoopkyWritesTogetherIsLoopkyOnly() = runTest {
        seedEverythingLoopkyWrites()

        assertEquals(PubkyAppFootprint.LoopkyOnly, classify())
    }

    @Test
    fun aLabelOnAnOwnPostThatNoLongerExistsIsLoopkyOnly() = runTest {
        // A deck request whose labels were written before the post rolled back leaves exactly this.
        profile()
        label(PubkyPaths.post(owner, "gone"), "deck-request")

        assertEquals(PubkyAppFootprint.LoopkyOnly, classify())
    }

    @Test
    fun anotherAccountsRecordsAreNeverConsulted() = runTest {
        profile()
        pubky.store["pubky://friendpk/pub/pubky.app/bookmarks/b1"] = "{}"
        pubky.store["pubky://friendpk/pub/bitkit.to/x"] = "{}"

        assertEquals(PubkyAppFootprint.LoopkyOnly, classify())
    }

    // --- Another app ------------------------------------------------------------------------------

    @Test
    fun anotherAppsNamespaceIsDirect() = runTest {
        profile()
        pubky.store["pubky://$owner/pub/bitkit.to/profile.json"] = "{}"

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun aFileDirectlyUnderPubIsDirect() = runTest {
        pubky.store["pubky://$owner/pub/notes.txt"] = "hello"

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun anotherAppsNamespaceIsDirectEvenWithNothingInPubkyApp() = runTest {
        pubky.store["pubky://$owner/pub/someapp/data.json"] = "{}"

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun everyPubkyAppEntryLoopkyNeverWritesIsDirect() = runTest {
        for (entry in listOf("bookmarks/b1", "files/f1", "blobs/b1", "feeds/f1", "last_read", "settings.json")) {
            pubky.store.clear()
            profile()
            pubky.store["$pubkyApp/$entry"] = "{}"

            assertEquals(PubkyAppFootprint.Direct, classify(), entry)
        }
    }

    @Test
    fun aProfilePictureIsDirect() = runTest {
        profile("""{"name":"Ana","image":"pubky://$owner/pub/pubky.app/files/1"}""")

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun profileLinksOrAStatusAreDirect() = runTest {
        profile("""{"name":"Ana","links":[{"title":"x","url":"https://x"}],"status":"hi"}""")

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun aProfileThatDoesNotParseIsDirect() = runTest {
        profile("{broken")

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun anOrdinaryPostIsDirect() = runTest {
        seedEverythingLoopkyWrites()
        post("mine", """{"content":"good morning","kind":"short"}""")

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun aDeckLinkSharedByHandIsDirect() = runTest {
        linkPost("shared", "pubky://friendpk/pub/loopky/decks/d1/manifest.json")

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun aShortPostLabelledOnlyLoopkyIsDirect() = runTest {
        val p = post("fan", """{"content":"I love Loopky","kind":"short"}""")
        label(p, "loopky")

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun aPostThatDoesNotParseIsDirect() = runTest {
        post("odd", "{broken")

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun aLabelOnSomeoneElsesPostIsDirect() = runTest {
        // Loopky never labels another person's post.
        profile()
        label(PubkyPaths.post("friendpk", "p1"), "funny")

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun aLabelOnAnArbitraryAddressIsDirect() = runTest {
        profile()
        label("https://example.com/article", "read-later")

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun aTagRecordThatDoesNotParseIsDirect() = runTest {
        profile()
        pubky.store[PubkyPaths.tag(owner, "weird")] = """{"not":"a tag"}"""

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    // --- Failures ---------------------------------------------------------------------------------

    @Test
    fun anUnlistableAccountIsUnknown() = runTest {
        profile()
        pubky.failListWith = PubkyError("HTTP transport error: connection refused")

        assertEquals(PubkyAppFootprint.Unknown, classify())
    }

    @Test
    fun anUnlistablePubkyAppIsUnknown() = runTest {
        profile()
        pubky.failListWhenUrlContains = "/pub/pubky.app/"

        assertEquals(PubkyAppFootprint.Unknown, classify())
    }

    @Test
    fun anUnreadableProfileIsUnknown() = runTest {
        profile()
        pubky.failGetWhenUrlContains = "profile.json"

        assertEquals(PubkyAppFootprint.Unknown, classify())
    }

    @Test
    fun anUnreadablePostIsUnknown() = runTest {
        profile()
        linkPost("p1", "pubky://$owner/pub/loopky/decks/deck1/manifest.json")
        pubky.failGetWhenUrlContains = "/posts/p1"

        assertEquals(PubkyAppFootprint.Unknown, classify())
    }

    @Test
    fun anUnreadableTagIsUnknown() = runTest {
        profile()
        val t = label(PubkyPaths.profile(owner), "loopky-user")
        pubky.failGetWhenUrlContains = t.substringAfterLast('/')

        assertEquals(PubkyAppFootprint.Unknown, classify())
    }

    @Test
    fun anUnreadableLabelOnADeckRequestCannotMakeItLookOrdinary() = runTest {
        // Without one of its two labels a deck request reads as an ordinary post, which would be
        // Direct for the wrong reason. A failed read is Unknown, never a guess.
        val request = post("p4", """{"content":"a deck about tides","kind":"short"}""")
        label(request, "loopky")
        val second = label(request, "deck-request")
        pubky.failGetWhenUrlContains = second.substringAfterLast('/')

        assertEquals(PubkyAppFootprint.Unknown, classify())
    }

    @Test
    fun anUnreadableLabelDoesNotHideAnOrdinaryPost() = runTest {
        // No label could make this one Loopky's, so the failed read changes nothing.
        post("mine", """{"content":"good morning","kind":"short","embed":{"kind":"link","uri":"https://example.com"}}""")
        val t = label(PubkyPaths.profile(owner), "loopky-user")
        pubky.failGetWhenUrlContains = t.substringAfterLast('/')

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun aProofOfAnotherAppWinsOverAFailedRead() = runTest {
        profile()
        linkPost("p1", "pubky://$owner/pub/loopky/decks/deck1/manifest.json")
        post("p2", """{"content":"good morning","kind":"short"}""")
        pubky.failGetWhenUrlContains = "/posts/p1"

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun aListingThatNeverEndsIsUnknownRatherThanLoopkyOnly() = runTest {
        // A homeserver that ignores the cursor hands back page one forever. What was read is all
        // Loopky's, but the rest was never seen.
        profile()
        follows(MORE_THAN_A_PAGE)
        pubky.ignoresListCursor = true

        assertEquals(PubkyAppFootprint.Unknown, classify())
    }

    @Test
    fun aListingThatFailsPartWayIsUnknown() = runTest {
        profile()
        follows(MORE_THAN_A_PAGE)
        // The first-level listing of /pub/, then page one of pubky.app; page two fails.
        pubky.failListAfterPages = 2

        assertEquals(PubkyAppFootprint.Unknown, classify())
    }

    // --- Listings ---------------------------------------------------------------------------------

    @Test
    fun aRecordPastTheFirstPageIsStillSeen() = runTest {
        profile()
        follows(MORE_THAN_A_PAGE)
        // Sorts after every follow, so only a listing that pages reaches it.
        pubky.store["$pubkyApp/zz_last_read"] = "{}"

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun aHomeserverThatIgnoresShallowStillShowsAnotherNamespace() = runTest {
        pubky.honoursShallow = false
        repeat(4) { pubky.store["pubky://$owner/pub/loopky/decks/deck$it/manifest.json"] = "{}" }
        pubky.store["pubky://$owner/pub/someapp/x"] = "{}"

        assertEquals(PubkyAppFootprint.Direct, classify())
    }

    @Test
    fun aHomeserverThatIgnoresShallowStillReadsALoopkyOnlyAccount() = runTest {
        pubky.honoursShallow = false
        seedEverythingLoopkyWrites()

        assertEquals(PubkyAppFootprint.LoopkyOnly, classify())
    }

    @Test
    fun theFirstProofStopsTheReadingEarly() = runTest {
        profile()
        repeat(60) { post("p" + it.toString().padStart(3, '0'), """{"content":"post $it","kind":"short"}""") }

        assertEquals(PubkyAppFootprint.Direct, classify())
        val postReads = pubky.gets.count { "/posts/" in it }
        assertTrue(postReads < 60, "read $postReads of 60 posts after the first one proved it")
    }

    // --- Records for deletion ---------------------------------------------------------------------

    @Test
    fun theRecordsForDeletionAreEverythingUnderPubkyAppAndNothingElse() = runTest {
        seedEverythingLoopkyWrites()
        pubky.store["pubky://friendpk/pub/pubky.app/profile.json"] = "{}"

        val records = reader.pubkyAppRecords(owner)

        assertTrue(records.all { it.startsWith("$pubkyApp/") }, "$records")
        assertEquals(pubky.store.keys.filter { it.startsWith("$pubkyApp/") }.sorted(), records.sorted())
    }

    @Test
    fun theRecordsForDeletionRefuseAnIncompleteListing() = runTest {
        profile()
        follows(MORE_THAN_A_PAGE)
        pubky.failListAfterPages = 1

        assertTrue(runCatching { reader.pubkyAppRecords(owner) }.isFailure)
    }

    private companion object {
        /** More than one page of the real listing ([LIST_PAGE_SIZE]), so the loop has to page. */
        val MORE_THAN_A_PAGE = LIST_PAGE_SIZE.toInt() * 2 + 50
    }
}
