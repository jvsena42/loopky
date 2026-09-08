package com.github.jvsena42.loopky.domain.model

import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.testCoverImage
import com.github.jvsena42.loopky.testing.testDeck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckAnnouncementTest {

    @Test
    fun `created announcement names the deck and links its manifest`() {
        val deck = testDeck(id = "d1", title = "Kanji N5")
        val content = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created).content

        assertTrue(content.startsWith("📚 I published a new deck on Loopky: \"Kanji N5\""), content)
        assertTrue(content.contains("pubky://$TEST_PUBKY/pub/loopky/decks/d1/manifest.json"), content)
    }

    @Test
    fun `follow and clone mention the original author`() {
        val deck = testDeck(title = "Kanji N5")
        val followed = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Followed, AUTHOR).content
        val cloned = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Cloned, AUTHOR).content

        // "pubky" + the whole key is what Nexus indexes as a mention and pubky.app renders as a
        // link to the profile — so the author hears about it, not just the announcer's followers.
        assertTrue(
            followed.contains("Now following the Loopky deck: \"Kanji N5\" by pubky$AUTHOR"),
            followed,
        )
        assertTrue(
            cloned.contains("Cloned the Loopky deck: \"Kanji N5\" by pubky$AUTHOR into my library"),
            cloned,
        )
    }

    @Test
    fun `the deck URI is not read as a second mention of its author`() {
        val deck = testDeck(id = "d1", authorPubky = AUTHOR, title = "Kanji N5")

        val content = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Followed, AUTHOR).content

        // `pubky://` carries the mention prefix too, so the post is scanned the way Nexus scans
        // it: every occurrence of the prefix, keyed on whether a whole key follows. One credit in,
        // one mention out — a second would notify the author twice for one follow.
        assertEquals(listOf(AUTHOR), mentionsIn(content), content)
    }

    @Test
    fun `an unknown author is omitted rather than leaving a dangling by`() {
        val deck = testDeck(title = "Kanji N5")
        val content = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Followed, authorPubky = "  ").content

        assertTrue(content.contains("deck: \"Kanji N5\"\n"), content)
        assertTrue(!content.contains(" by "), content)
    }

    @Test
    fun `anything that is not a whole key is dropped rather than half-mentioned`() {
        val deck = testDeck(title = "Kanji N5")
        // A prefix, an over-long string, and 52 characters outside z-base-32: "pubky" in front of
        // any of them mentions nobody while looking like it should.
        val notKeys = listOf(AUTHOR.take(20), "z".repeat(200), "L".repeat(Pubky.LENGTH))

        notKeys.forEach { candidate ->
            val content = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Followed, candidate).content
            assertTrue(!content.contains(" by "), content)
        }
    }

    @Test
    fun `the cover emoji opens the post`() {
        val deck = testDeck(title = "Kanji N5").copy(coverEmoji = "🇯🇵")
        assertTrue(
            DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created).content.startsWith("🇯🇵 "),
        )
    }

    @Test
    fun `a web cover goes in the body where a reader's client will look for it`() {
        val deck = testDeck(
            coverImageRef = testCoverImage().copy(path = "", sha256 = "", url = "https://img.test/c.jpg"),
        )

        val announcement = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created)

        assertEquals("https://img.test/c.jpg", announcement.coverImageUrl)
        // pubky.app probes the first http(s) link in the content and renders an image content-type
        // inline; nothing linkifies the pubky:// URI, so the cover is the only candidate.
        assertTrue(announcement.content.endsWith("https://img.test/c.jpg"), announcement.content)
    }

    @Test
    fun `a homeserver cover is dropped rather than linked to a URL nobody can fetch`() {
        // Only a Pubky client could resolve pubky://…/media/cafe.png, and the OpenGraph probe on
        // the other end is an ordinary HTTP fetch. Announcing without an image beats a dead link.
        val deck = testDeck(id = "d1", coverImageRef = testCoverImage(sha = "cafe"))

        val announcement = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created)

        assertNull(announcement.coverImageUrl)
        assertTrue(announcement.content.endsWith("manifest.json"), announcement.content)
    }

    @Test
    fun `a cloned cover pinned to its origin is dropped for the same reason`() {
        val origin = "pubky://otherpk/pub/loopky/decks/src/media/cafe.png"
        val deck = testDeck(coverImageRef = testCoverImage(sha = "cafe").copy(uri = origin))

        assertNull(DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created).coverImageUrl)
    }

    @Test
    fun `no cover means no image link`() {
        assertNull(DeckAnnouncement.of(testDeck(), DeckAnnouncement.Kind.Created).coverImageUrl)
    }

    @Test
    fun `an over-long cover url is dropped rather than swamping the post`() {
        val long = "https://img.test/" + "q".repeat(200)
        val deck = testDeck(coverImageRef = testCoverImage().copy(path = "", sha256 = "", url = long))

        assertNull(DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created).coverImageUrl)
    }

    @Test
    fun `topics never appear in the body`() {
        val deck = testDeck(title = "Kanji N5", tags = listOf(Tag("kanji"), Tag("japanese")))

        val content = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created).content

        // The tag records put chips under the post already; hashtags said it twice.
        assertFalse(content.contains("#"), content)
    }

    @Test
    fun `a deck with no topics still announces itself as a Loopky deck`() {
        val announcement = DeckAnnouncement.of(testDeck(), DeckAnnouncement.Kind.Created)

        assertEquals(listOf(ReservedTags.DECK), announcement.tags)
    }

    @Test
    fun `topics are capped so a post does not trail twenty hashtags`() {
        val many = (1..12).map { Tag("topic$it") }
        val announcement = DeckAnnouncement.of(testDeck(tags = many), DeckAnnouncement.Kind.Created)

        // Five topics plus the reserved label; each costs a homeserver write of its own.
        assertEquals(expected = 6, actual = announcement.tags.size)
        assertEquals(ReservedTags.DECK, announcement.tags.last())
    }

    @Test
    fun `a reserved label smuggled onto a deck is not re-announced`() {
        val deck = testDeck(tags = listOf(Tag("loopky-user"), Tag("kanji")))

        assertEquals(
            listOf(Tag("kanji"), ReservedTags.DECK),
            DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created).tags,
        )
    }

    @Test
    fun `a foreign title is truncated to stay inside the content limit`() {
        val deck = testDeck(title = "x".repeat(500))
        val content = DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Followed, TEST_PUBKY).content

        assertTrue(content.contains("…"), content)
        assertTrue(content.length < SHORT_CONTENT_LIMIT, "was ${content.length}")
    }

    /**
     * Nexus's `find_mentioned_ids`: every occurrence of the prefix whose next 52 characters are a
     * whole key. Mirrored rather than approximated, since that scan is what decides whether the
     * post mentions anyone at all.
     */
    private fun mentionsIn(content: String): List<String> =
        content.windowedSequence(MENTION_PREFIX.length + Pubky.LENGTH)
            .filter { it.startsWith(MENTION_PREFIX) }
            .map { it.drop(MENTION_PREFIX.length) }
            .filter(Pubky::isKey)
            .toList()

    private companion object {
        const val MENTION_PREFIX = "pubky"

        /** A real 52-character z-base-32 key: a mention only renders for an exact one. */
        const val AUTHOR = "3jubjyq4fkh4dq38exrpuo8we6xta8a6rhxnjjzyoo7j4r3f4rjo"

        /** `post_short_content_max_length` in pubky-app-specs. */
        const val SHORT_CONTENT_LIMIT = 2_000
    }
}
