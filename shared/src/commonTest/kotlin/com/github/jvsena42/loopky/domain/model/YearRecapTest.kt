package com.github.jvsena42.loopky.domain.model

import com.github.jvsena42.loopky.testing.testDeck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YearRecapTest {

    private val day = 86_400_000L
    private val y2026 = 1_767_225_600_000L
    private val y2027 = 1_798_761_600_000L
    private val midYear = y2026 + 150 * day

    /** A state as `SrsState?.review` would have left it after a grade at [reviewedAt]. */
    private fun state(id: String, reviewedAt: Long, intervalDays: Int = 3) = SrsState(
        cardId = id,
        dueAt = reviewedAt + if (intervalDays == 0) AGAIN_DELAY_MS else intervalDays * day,
        intervalDays = intervalDays,
        easeFactor = 2.5,
        repetitions = 1,
        lastGrade = SrsGrade.Good,
    )

    private fun recap(
        owned: List<Deck> = emptyList(),
        followed: List<Deck> = emptyList(),
        states: Map<String, List<SrsState>> = emptyMap(),
        threshold: Int = 7,
    ) = YearRecap.from(2026, owned, followed, states, threshold)

    @Test
    fun `the last review is recovered from what the scheduler wrote`() {
        val now = midYear
        val good = null.review("c1", SrsGrade.Good, now)
        val again = null.review("c2", SrsGrade.Again, now)

        assertEquals(now, good.lastReviewedAt)
        assertEquals(now, again.lastReviewedAt)
    }

    @Test
    fun `a card counts in the year its last review fell in - bounds in UTC`() {
        val deck = testDeck(id = "d1")
        val result = recap(
            owned = listOf(deck),
            states = mapOf(
                "d1" to listOf(
                    state("first-instant", y2026),
                    state("last-instant", y2027 - 1),
                    state("year-before", y2026 - 1),
                    state("year-after", y2027),
                ),
            ),
        )

        assertEquals(2, result.cardsStudied)
        assertEquals(1, result.decksStudied)
    }

    @Test
    fun `mastered is the studied cards sitting at the threshold`() {
        val result = recap(
            owned = listOf(testDeck(id = "d1")),
            states = mapOf(
                "d1" to listOf(
                    state("a", midYear, intervalDays = 7),
                    state("b", midYear, intervalDays = 30),
                    state("c", midYear, intervalDays = 6),
                    state("lapsed", midYear, intervalDays = 0),
                    state("mastered-last-year", y2026 - 10 * day, intervalDays = 7),
                ),
            ),
        )

        assertEquals(4, result.cardsStudied)
        assertEquals(2, result.cardsMastered)
    }

    @Test
    fun `the top deck is the most studied - ties broken by title`() {
        val result = recap(
            owned = listOf(testDeck(id = "d1", title = "Zoology"), testDeck(id = "d2", title = "Algebra")),
            followed = listOf(testDeck(id = "d3", title = "Minor", authorPubky = "other")),
            states = mapOf(
                "d1" to listOf(state("a", midYear), state("b", midYear)),
                "d2" to listOf(state("c", midYear), state("d", midYear)),
                "d3" to listOf(state("e", midYear)),
            ),
        )

        assertEquals("Algebra", result.topDeck?.title)
        assertEquals(2, result.topDeck?.cardsStudied)
        assertEquals(3, result.decksStudied)
    }

    @Test
    fun `states of a deck no longer owned or followed are ignored`() {
        val result = recap(
            owned = listOf(testDeck(id = "d1")),
            states = mapOf("unfollowed" to listOf(state("a", midYear))),
        )

        assertEquals(0, result.cardsStudied)
        assertNull(result.topDeck)
    }

    @Test
    fun `created counts owned decks made this year - never followed ones`() {
        val result = recap(
            owned = listOf(
                testDeck(id = "new", createdAt = midYear, cardCount = 12),
                testDeck(id = "old", createdAt = y2026 - 1, cardCount = 99),
            ),
            followed = listOf(testDeck(id = "theirs", authorPubky = "other", createdAt = midYear, cardCount = 50)),
        )

        assertEquals(1, result.decksCreated)
        assertEquals(12, result.cardsCreated)
        assertFalse(result.isEmpty)
    }

    @Test
    fun `languages are base subtags of studied decks - commonest first`() {
        val result = recap(
            owned = listOf(
                testDeck(id = "d1", frontLang = "es-ES", backLang = "en-US"),
                testDeck(id = "d2", frontLang = "pt-BR", backLang = "en-GB"),
                testDeck(id = "unstudied", frontLang = "de-DE", backLang = "en-US"),
            ),
            states = mapOf("d1" to listOf(state("a", midYear)), "d2" to listOf(state("b", midYear))),
        )

        assertEquals(listOf("en", "es", "pt"), result.languages)
    }

    @Test
    fun `topics skip reserved labels and stop at three`() {
        val tags = listOf("verbs", "spanish", "grammar", "loopky-deck", "a1").map(::Tag)
        val result = recap(
            owned = listOf(testDeck(id = "d1", tags = tags), testDeck(id = "d2", tags = listOf(Tag("spanish")))),
            states = mapOf("d1" to listOf(state("a", midYear)), "d2" to listOf(state("b", midYear))),
        )

        assertEquals(listOf("spanish", "a1", "grammar"), result.topics)
    }

    @Test
    fun `topics leave out the labels a language pair adds - which languages already report`() {
        val tags = listOf("language", "spanish", "english", "verbs").map(::Tag)
        val result = recap(
            owned = listOf(testDeck(id = "d1", tags = tags, frontLang = "es-ES", backLang = "en-US")),
            states = mapOf("d1" to listOf(state("a", midYear))),
        )

        assertEquals(listOf("verbs"), result.topics)
        assertEquals(listOf("en", "es"), result.languages)
    }

    @Test
    fun `persona follows what the year was mostly about`() {
        fun studied(count: Int, interval: Int) = List(count) { state("c$it", midYear, interval) }
        val plain = testDeck(id = "d1", createdAt = y2026 - 1)

        val master = recap(owned = listOf(plain), states = mapOf("d1" to studied(4, 7)))
        val explorer = recap(owned = listOf(plain), states = mapOf("d1" to studied(4, 1)))
        val creator = recap(
            owned = listOf(testDeck(id = "d1", createdAt = midYear, cardCount = 10)),
            states = mapOf("d1" to studied(4, 7)),
        )
        val polyglot = recap(
            owned = listOf(
                testDeck(id = "d1", createdAt = midYear, cardCount = 10, frontLang = "es", backLang = "en"),
                testDeck(id = "d2", frontLang = "ja", backLang = "en"),
            ),
            states = mapOf("d1" to studied(1, 7), "d2" to listOf(state("x", midYear))),
        )

        assertEquals(RecapPersona.Master, master.persona)
        assertEquals(RecapPersona.Explorer, explorer.persona)
        assertEquals(RecapPersona.Creator, creator.persona)
        assertEquals(RecapPersona.Polyglot, polyglot.persona)
    }

    @Test
    fun `a year with nothing studied and nothing created is empty`() {
        val result = recap(owned = listOf(testDeck(id = "d1", createdAt = y2026 - 1)))

        assertTrue(result.isEmpty)
        assertEquals(RecapPersona.Explorer, result.persona)
    }
}

class RecapWindowTest {
    private val dec16 = 1_797_379_200_000L
    private val jan1Next = 1_798_761_600_000L

    @Test
    fun `the window opens on 16 December UTC and closes with the year`() {
        assertFalse(RecapWindow.isOpenAt(dec16 - 1))
        assertTrue(RecapWindow.isOpenAt(dec16))
        assertTrue(RecapWindow.isOpenAt(jan1Next - 1))
        assertFalse(RecapWindow.isOpenAt(jan1Next))
    }

    @Test
    fun `it is closed in every other month`() {
        // 20 October 2026, 20 November 2026.
        assertFalse(RecapWindow.isOpenAt(1_792_454_400_000L))
        assertFalse(RecapWindow.isOpenAt(1_795_132_800_000L))
    }

    @Test
    fun `the year is read in UTC on both sides of midnight`() {
        assertEquals(2026, RecapWindow.yearAt(jan1Next - 1))
        assertEquals(2027, RecapWindow.yearAt(jan1Next))
        assertEquals(1970, RecapWindow.yearAt(0))
        assertEquals(1969, RecapWindow.yearAt(-1))
    }

    @Test
    fun `year starts land on midnight - leap years included`() {
        assertEquals(1_767_225_600_000L, RecapWindow.yearStart(2026))
        assertEquals(jan1Next, RecapWindow.yearStart(2027))
        // 2024 is a leap year: 29 February exists and 31 December is its 366th day.
        assertEquals(2024, RecapWindow.yearAt(1_709_164_800_000L))
        assertTrue(RecapWindow.isOpenAt(1_735_603_200_000L))
        assertEquals(366L * 86_400_000L, RecapWindow.yearStart(2025) - RecapWindow.yearStart(2024))
    }

    @Test
    fun `a debug override moves the clock and a non-positive one gives it back`() {
        val clock = RecapClock(systemNow = { 42L })

        clock.setOverride(dec16)
        assertEquals(dec16, clock.now())
        clock.setOverride(0)
        assertEquals(42L, clock.now())
    }
}

class RecapShareTargetTest {
    private val url = "https://loopky.app/recap/?pubky=${"y".repeat(52)}&year=2026"

    @Test
    fun `every target's post fits its limit and still ends with the link`() {
        val long = "I studied a lot this year. ".repeat(200)
        RecapShareTarget.entries.forEach { target ->
            val post = target.compose(long, url)
            target.maxChars?.let { assertTrue(post.length <= it, "$target: ${post.length}") }
            assertTrue(post.endsWith(url), target.name)
        }
    }

    @Test
    fun `a caption that fits is left exactly as written`() {
        assertEquals("My 2026 on Loopky\n\n$url", RecapShareTarget.X.compose("  My 2026 on Loopky ", url))
    }

    @Test
    fun `instagram is the one target that takes no text - and gets the story format`() {
        assertEquals(listOf(RecapShareTarget.Instagram), RecapShareTarget.entries.filterNot { it.takesText })
        assertEquals(RecapImageFormat.Story, RecapShareTarget.Instagram.imageFormat)
    }
}
