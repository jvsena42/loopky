package com.github.jvsena42.loopky.domain.model

/**
 * One account's year, built from what the account already holds: each card's current review state
 * and the dated deck manifests. Nothing records history, so there is no review total and no streak
 * — a card reviewed forty times this year and one reviewed once look the same.
 *
 * **The site computes this a second time** (`assets/js/recap.js` in `loopky.github.io`) from the
 * same public records, so a rule changed here is a rule changed there. `recap-fixture.json` is
 * asserted by both suites. See Architecture.md §8.8.
 */
data class YearRecap(
    val year: Int,
    /** Cards whose most recent review fell inside [year]. */
    val cardsStudied: Int,
    /** Of [cardsStudied], those sitting at the maturity threshold. */
    val cardsMastered: Int,
    val decksStudied: Int,
    val topDeck: RecapDeck?,
    /** Owned decks created inside [year], and the cards they hold now. */
    val decksCreated: Int,
    val cardsCreated: Int,
    /** Base language subtags (`"es"`, never `"es-ES"`) declared on studied decks, commonest first. */
    val languages: List<String>,
    val topics: List<String>,
    val persona: RecapPersona,
) {
    val isEmpty: Boolean get() = cardsStudied == 0 && decksCreated == 0

    companion object {
        /**
         * [statesByDeck] is keyed by deck id and is counted **as states, not as cards**: a state
         * whose card has since been deleted still counts, because the site cannot afford to fetch
         * every card chunk to find out. States of a deck in neither list are ignored.
         */
        fun from(
            year: Int,
            owned: List<Deck>,
            followed: List<Deck>,
            statesByDeck: Map<String, Collection<SrsState>>,
            thresholdDays: Int,
        ): YearRecap {
            val bounds = RecapWindow.yearStart(year) until RecapWindow.yearStart(year + 1)
            val studied = (owned + followed).distinctBy { it.id }.mapNotNull { deck ->
                val inYear = statesByDeck[deck.id].orEmpty().filter { it.lastReviewedAt in bounds }
                if (inYear.isEmpty()) null else StudiedDeck(deck, inYear)
            }
            val created = owned.distinctBy { it.id }.filter { it.createdAt in bounds }
            val cardsStudied = studied.sumOf { it.states.size }
            val cardsMastered = studied.sumOf { deck -> deck.states.count { it.intervalDays >= thresholdDays } }
            val cardsCreated = created.sumOf { it.cardCount }
            val languages = ranked(
                studied.flatMap { listOfNotNull(it.deck.frontLang, it.deck.backLang).map(::baseSubtag).distinct() },
            )
            return YearRecap(
                year = year,
                cardsStudied = cardsStudied,
                cardsMastered = cardsMastered,
                decksStudied = studied.size,
                topDeck = studied
                    .sortedWith(
                        compareByDescending<StudiedDeck> { it.states.size }
                            .thenBy { it.deck.title }
                            .thenBy { it.deck.id },
                    )
                    .firstOrNull()
                    ?.let { RecapDeck(it.deck.title, it.deck.coverEmoji, it.states.size) },
                decksCreated = created.size,
                cardsCreated = cardsCreated,
                languages = languages,
                topics = ranked(
                    studied.flatMap { it.deck.tags.filterNot(ReservedTags::isReserved).map(Tag::value).distinct() },
                ).take(MAX_TOPICS),
                persona = when {
                    languages.size >= POLYGLOT_LANGUAGES -> RecapPersona.Polyglot
                    created.isNotEmpty() && cardsCreated >= cardsStudied -> RecapPersona.Creator
                    cardsStudied > 0 && cardsMastered * 2 >= cardsStudied -> RecapPersona.Master
                    else -> RecapPersona.Explorer
                },
            )
        }

        private fun ranked(values: List<String>): List<String> =
            values.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key }

        private fun baseSubtag(tag: String): String = tag.substringBefore('-').lowercase()

        private const val MAX_TOPICS = 3
        private const val POLYGLOT_LANGUAGES = 3
    }
}

data class RecapDeck(val title: String, val coverEmoji: String?, val cardsStudied: Int)

/** PascalCase because it crosses to Swift, where entries arrive lowercased with separators dropped. */
enum class RecapPersona { Polyglot, Creator, Master, Explorer }

private class StudiedDeck(val deck: Deck, val states: List<SrsState>)

/**
 * When a state was last graded. Not stored — recovered from how `SrsState?.review` builds a due
 * date, so it is exact for a graded card and only as good as the record for one written by
 * `SrsRepository.upsert`.
 */
internal val SrsState.lastReviewedAt: Long
    get() = if (intervalDays == 0) dueAt - AGAIN_DELAY_MS else dueAt - intervalDays * RECAP_DAY_MS

private const val RECAP_DAY_MS = 86_400_000L

/**
 * When the recap is offered: 16–31 December.
 *
 * **UTC, deliberately**, unlike every other date in the app. The site draws the same year from the
 * same records in a browser, and a local boundary would have the two disagree about a card graded
 * on New Year's Eve depending on where each was opened. The cost is a banner that appears a few
 * hours early or late, which nobody can see.
 */
object RecapWindow {
    // MayBeConst: a `const val` in an object crosses to Swift under a mangled name.
    @Suppress("MayBeConst")
    val firstDay: Int = 16

    private const val DECEMBER = 12

    fun isOpenAt(millis: Long): Boolean {
        val date = civil(millis)
        return date.month == DECEMBER && date.day >= firstDay
    }

    fun yearAt(millis: Long): Int = civil(millis).year

    /** Midnight UTC on 1 January of [year]. */
    fun yearStart(year: Int): Long = daysFromCivil(year, 1, 1) * RECAP_DAY_MS

    private class CivilDate(val year: Int, val month: Int, val day: Int)

    // Howard Hinnant's civil-from-days, so commonMain needs no calendar and JS's getUTC* agrees.
    @Suppress("MagicNumber")
    private fun civil(millis: Long): CivilDate {
        val z = millis.floorDiv(RECAP_DAY_MS) + 719_468
        val era = z.floorDiv(146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        return CivilDate(year.toInt(), month.toInt(), (doy - (153 * mp + 2) / 5 + 1).toInt())
    }

    @Suppress("MagicNumber")
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400)
        val yoe = y - era * 400
        val doy = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}

/**
 * The clock the recap is gated on. A class rather than a bare `epochMillis()` so a debug build can
 * move it into December — setting the device clock instead breaks homeserver auth.
 */
class RecapClock(private val systemNow: () -> Long) {
    private var overrideMillis: Long? = null

    fun now(): Long = overrideMillis ?: systemNow()

    /** Debug builds only. A non-positive value clears it. */
    fun setOverride(millis: Long) {
        overrideMillis = millis.takeIf { it > 0 }
    }
}
