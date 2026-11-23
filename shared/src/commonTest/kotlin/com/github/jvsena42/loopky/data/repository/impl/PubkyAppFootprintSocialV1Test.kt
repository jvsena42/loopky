package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.SocialLayout
import com.github.jvsena42.loopky.domain.model.PubkyAppFootprint
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same judgement as [PubkyAppFootprintReaderTest], for records in `/pub/social/v1/` (#616): a
 * migrated account, or one Loopky started there, is judged by what it holds in both layouts.
 */
class PubkyAppFootprintSocialV1Test {

    private val pubky = FakePubkyClient()
    private val reader = PubkyAppFootprintReader(pubky)
    private val owner = TEST_PUBKY
    private val v1 = SocialLayout.SocialV1

    private fun profile(json: String = """{"name":"Ana","bio":"hi"}""") {
        pubky.store[v1.profile(owner)] = json
    }

    /** A v1 post's first version, returned as the reference tags name it by. */
    private fun post(id: String, json: String): String {
        pubky.store[v1.postVersion(owner, id)] = json
        return v1.post(owner, id)
    }

    private fun linkPost(id: String, uri: String): String =
        post(id, """{"content":"x","kind":"link","embed":"$uri","attachments":[]}""")

    private fun label(subject: String, label: String) {
        pubky.store[v1.tag(owner, "tag${(subject + label).hashCode().toUInt()}")] =
            """{"uri":"$subject","label":"$label","created_at":1}"""
    }

    @Test
    fun everythingLoopkyWritesInSocialV1IsLoopkyOnly() = runTest {
        profile()
        pubky.store[v1.follow(owner, "friendpk")] = """{"created_at":1}"""
        label(v1.profile(owner), "loopky-user")
        val own = linkPost("0034A0X7NJ52G", "pubky://$owner/pub/loopky/decks/deck1/manifest.json")
        label(own, "loopky-deck")
        val request = post("0034A0X7NJ52H", """{"content":"a deck about tides","kind":"note","attachments":[]}""")
        label(request, "loopky")
        label(request, "deck-request")

        assertEquals(PubkyAppFootprint.LoopkyOnly, reader.classify(owner))
        val judged = reader.judgedPubkyAppRecords(owner)
        assertTrue(judged.foreign.isEmpty() && judged.unreadable.isEmpty(), judged.toString())
        assertEquals(2, reader.loopkyPosts(owner).size)
    }

    @Test
    fun anOrdinaryV1PostIsDirect() = runTest {
        profile()
        post("0034A0X7NJ52G", """{"content":"gm","kind":"note","attachments":[]}""")

        assertEquals(PubkyAppFootprint.Direct, reader.classify(owner))
    }

    @Test
    fun aV1ProfilePictureIsDirect() = runTest {
        profile("""{"name":"Ana","image":"pubky://$owner/pub/social/v1/files/abc.png"}""")

        assertEquals(PubkyAppFootprint.Direct, reader.classify(owner))
    }

    @Test
    fun anotherEpochUnderSocialIsDirect() = runTest {
        pubky.store["pubky://$owner/pub/social/v2/profile.json"] = """{"name":"Ana"}"""

        assertEquals(PubkyAppFootprint.Direct, reader.classify(owner))
    }

    @Test
    fun anotherAppInEitherLayoutMakesTheAccountDirect() = runTest {
        profile()
        pubky.store[SocialLayout.Legacy.post(owner, "p1")] = """{"content":"gm","kind":"short"}"""

        assertEquals(PubkyAppFootprint.Direct, reader.classify(owner))
    }
}
