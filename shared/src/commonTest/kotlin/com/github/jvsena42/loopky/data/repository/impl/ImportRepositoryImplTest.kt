package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.domain.model.Separator
import com.github.jvsena42.loopky.domain.model.TriageDecision
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImportRepositoryImplTest {

    private fun repo() = ImportRepositoryImpl()

    // ── Separator detection ──────────────────────────────────────────────

    @Test
    fun emDashSeparator() = runBlocking {
        val draft = repo().parse("hola — hello\ngracias — thank you").getOrThrow()
        assertIs<Separator.EmDash>(draft.separator)
        assertEquals(2, draft.rows.size)
    }

    @Test
    fun colonSeparator() = runBlocking {
        val draft = repo().parse("mitosis: cell division\nosmosis: water moves across a membrane").getOrThrow()
        assertIs<Separator.Colon>(draft.separator)
        assertEquals(2, draft.rows.size)
    }

    @Test
    fun tabSeparator() = runBlocking {
        val draft = repo().parse("hola\thello\ngracias\tthank you").getOrThrow()
        assertIs<Separator.Tab>(draft.separator)
        assertEquals(2, draft.rows.size)
    }

    @Test
    fun commaSeparator() = runBlocking {
        val draft = repo().parse("hola,hello\ngracias,thank you").getOrThrow()
        assertIs<Separator.Comma>(draft.separator)
        assertEquals(2, draft.rows.size)
    }

    @Test
    fun pipeSeparator() = runBlocking {
        val draft = repo().parse("hola|hello\ngracias|thank you").getOrThrow()
        assertIs<Separator.Pipe>(draft.separator)
        assertEquals(2, draft.rows.size)
    }

    @Test
    fun semicolonSeparator() = runBlocking {
        val draft = repo().parse("hola;hello\ngracias;thank you").getOrThrow()
        assertIs<Separator.Semicolon>(draft.separator)
        assertEquals(2, draft.rows.size)
    }

    @Test
    fun blankLinePairs() = runBlocking {
        val text = "hola\nhello\n\ngracias\nthank you"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.BlankLine>(draft.separator)
        assertEquals(2, draft.rows.size)
        assertEquals(listOf("hola", "hello"), draft.rows[0].fields)
        assertEquals(listOf("gracias", "thank you"), draft.rows[1].fields)
    }

    @Test
    fun markdownTable() = runBlocking {
        val text = "| front | back |\n| --- | --- |\n| hola | hello |\n| gracias | thank you |"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.MarkdownTable>(draft.separator)
        assertEquals(2, draft.rows.size)
        assertEquals("hola", draft.rows[0].fields[0])
        assertEquals("hello", draft.rows[0].fields[1])
    }

    @Test
    fun singleColumn() = runBlocking {
        val draft = repo().parse("hola\nhello").getOrThrow()
        assertIs<Separator.SingleColumn>(draft.separator)
        assertEquals(2, draft.rows.size)
        assertEquals(1, draft.rows[0].fields.size)
    }

    // ── Front/back mapping ───────────────────────────────────────────────

    @Test
    fun emDashFrontBackMapping() = runBlocking {
        val draft = repo().parse("hola — hello\ngracias — thank you\npor favor — please").getOrThrow()
        assertEquals("hola", draft.rows[0].fields[0])
        assertEquals("hello", draft.rows[0].fields[1])
        assertEquals("gracias", draft.rows[1].fields[0])
        assertEquals("thank you", draft.rows[1].fields[1])
        assertEquals("por favor", draft.rows[2].fields[0])
        assertEquals("please", draft.rows[2].fields[1])
    }

    @Test
    fun colonFrontBackMapping() = runBlocking {
        val draft = repo().parse("mitosis: cell division\nosmosis: water moves").getOrThrow()
        assertEquals("mitosis", draft.rows[0].fields[0])
        assertEquals("cell division", draft.rows[0].fields[1])
    }

    // ── Extra columns are dropped (spec §8) ──────────────────────────────

    @Test
    fun tabThreeColumnsDropExtra() = runBlocking {
        val text = "hola\thello\tes,vocab\ngracias\tthank you\tes"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Tab>(draft.separator)
        assertEquals(2, draft.rows.size)
        assertEquals(listOf("hola", "hello"), draft.rows[0].fields)
        assertEquals(listOf("gracias", "thank you"), draft.rows[1].fields)
    }

    @Test
    fun markdownTableThreeColumnsDropExtra() = runBlocking {
        val text = "| front | back | tags |\n| --- | --- | --- |\n" +
            "| hola | hello | es,vocab |\n| gracias | thank you | es |"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.MarkdownTable>(draft.separator)
        assertEquals(2, draft.rows.size)
        assertEquals(listOf("hola", "hello"), draft.rows[0].fields)
        assertEquals(listOf("gracias", "thank you"), draft.rows[1].fields)
    }

    // ── Delimiter in content (split limit) ───────────────────────────────

    @Test
    fun colonInBackContent() = runBlocking {
        val text = "time: it's 3:00 PM\ndate: December 25: Christmas"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Colon>(draft.separator)
        assertEquals(2, draft.rows.size)
        assertEquals("time", draft.rows[0].fields[0])
        assertEquals("it's 3:00 PM", draft.rows[0].fields[1])
        assertEquals("date", draft.rows[1].fields[0])
        assertEquals("December 25: Christmas", draft.rows[1].fields[1])
    }

    @Test
    fun emDashInBackContent() = runBlocking {
        val text = "test — answer — with extra dash\nother — simple"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.EmDash>(draft.separator)
        assertEquals(2, draft.rows.size)
        assertEquals("test", draft.rows[0].fields[0])
        assertEquals("answer — with extra dash", draft.rows[0].fields[1])
    }

    @Test
    fun pipeInBackContent() = runBlocking {
        val text = "true|yes | correct\nfalse|no"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Pipe>(draft.separator)
        assertEquals("true", draft.rows[0].fields[0])
        assertEquals("yes | correct", draft.rows[0].fields[1])
    }

    @Test
    fun commaInBackContent() = runBlocking {
        val text = "greeting,hello, world\nfarewell,goodbye, everyone"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Comma>(draft.separator)
        assertEquals("greeting", draft.rows[0].fields[0])
        assertEquals("hello, world", draft.rows[0].fields[1])
    }

    // ── En-dash vs em-dash ───────────────────────────────────────────────

    @Test
    fun enDashSeparator() = runBlocking {
        val text = "hola \u2013 hello\ngracias \u2013 thank you" // \u2013 = en-dash
        val draft = repo().parse(text).getOrThrow()
        assertEquals(2, draft.rows.size)
        assertEquals("hola", draft.rows[0].fields[0])
        assertEquals("hello", draft.rows[0].fields[1])
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Test
    fun deduplication() = runBlocking {
        val text = "hola — hello\nhola — hello\ngracias — thank you"
        val draft = repo().parse(text).getOrThrow()
        assertEquals(2, draft.rows.size)
        assertEquals(1, draft.duplicatesCollapsed)
    }

    @Test
    fun emptyTextFails() = runBlocking {
        val result = repo().parse("")
        assertTrue(result.isFailure)
    }

    @Test
    fun whitespaceOnlyFails() = runBlocking {
        val result = repo().parse("   \n  \n  ")
        assertTrue(result.isFailure)
    }

    @Test
    fun windowsLineEndings() = runBlocking {
        val text = "hola — hello\r\ngracias — thank you\r\n"
        val draft = repo().parse(text).getOrThrow()
        assertEquals(2, draft.rows.size)
        assertEquals("hola", draft.rows[0].fields[0])
        assertEquals("hello", draft.rows[0].fields[1])
    }

    // ── False-positive separator detection ─────────────────────────────
    //
    // Characters that appear as natural punctuation in prose should NOT
    // be treated as field separators.  A comma in "Yes, I agree" is
    // grammar, not a front/back delimiter.

    @Test
    fun commaInProseIsNotSeparator(): Unit = runBlocking {
        // Commas here are natural punctuation, not field separators
        val text = "Yes, I agree with that\nNo, I disagree completely\nWell, maybe so"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.SingleColumn>(draft.separator)
        Unit
    }

    @Test
    fun semicolonInProseIsNotSeparator(): Unit = runBlocking {
        // Semicolons joining independent clauses
        val text = "She ran quickly; he walked slowly\nThey ate dinner; we slept early"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.SingleColumn>(draft.separator)
        Unit
    }

    @Test
    fun emDashParentheticalIsNotSeparator(): Unit = runBlocking {
        // Em-dashes used as parenthetical inserts (two per line)
        val text = "The dog \u2014 a big one \u2014 barked loudly\nThe cat \u2014 so tiny \u2014 meowed softly"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.SingleColumn>(draft.separator)
        Unit
    }

    @Test
    fun commaInNumbersNotSeparator(): Unit = runBlocking {
        // Commas inside numbers, no real front/back separation
        val text = "Total 1,000 units shipped\nCount 2,500 items remaining\nValue 10,250 dollars"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.SingleColumn>(draft.separator)
        Unit
    }

    @Test
    fun commaWithShortFrontIsRealSeparator() = runBlocking {
        // Short front + long back = genuine flashcard-style pairs
        val text = "hola,hello\ngracias,thank you\nadios,goodbye"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Comma>(draft.separator)
        assertEquals("hola", draft.rows[0].fields[0])
        assertEquals("hello", draft.rows[0].fields[1])
    }

    @Test
    fun colonWithShortFrontIsRealSeparator(): Unit = runBlocking {
        // Legitimate key: value pairs
        val text = "mitosis: cell division\nosmosis: diffusion of water\nphotosynthesis: converting light to energy"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Colon>(draft.separator)
        Unit
    }

    @Test
    fun semicolonWithShortFrontIsRealSeparator(): Unit = runBlocking {
        // Short terms separated by semicolons
        val text = "hola;hello\ngracias;thank you\nadios;goodbye"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Semicolon>(draft.separator)
        Unit
    }

    @Test
    fun pipeInShellCommandsNotSeparator(): Unit = runBlocking {
        // Shell pipes — both sides are commands, not front/back pairs
        val text = "ls -la | grep .txt | head\ncat file.log | sort | uniq -c"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.SingleColumn>(draft.separator)
        Unit
    }

    @Test
    fun higherPriorityDelimiterWinsOverIncidentalComma() = runBlocking {
        // Colon is the real separator; commas are incidental in content
        val text = "population: 1,000,000 people\narea: 2,500 sq km"
        val draft = repo().parse(text).getOrThrow()
        assertIs<Separator.Colon>(draft.separator)
        assertEquals("population", draft.rows[0].fields[0])
        assertEquals("1,000,000 people", draft.rows[0].fields[1])
    }

    @Test
    fun draftIsPersisted() = runBlocking {
        val r = repo()
        assertEquals(null, r.currentDraft())
        r.parse("hola — hello\ngracias — thank you")
        assertTrue(r.currentDraft() != null)
        r.clear()
        assertEquals(null, r.currentDraft())
    }

    @Test
    fun keptRowsDefaultsToAll() = runBlocking {
        val r = repo()
        r.parse("hola — hello\ngracias — thanks\nadios — bye")
        assertEquals(3, r.keptRows().size)
    }

    @Test
    fun discardedRowsAreExcluded() = runBlocking {
        val r = repo()
        r.parse("hola — hello\ngracias — thanks\nadios — bye")
        r.setDecision(1, TriageDecision.Discard)
        val kept = r.keptRows()
        assertEquals(2, kept.size)
        assertEquals("hola", kept[0].fields[0])
        assertEquals("adios", kept[1].fields[0])
    }

    @Test
    fun updateRowAppliesEditToKeptRows() = runBlocking {
        val r = repo()
        r.parse("hola — hello\ngracias — thanks")
        r.updateRow(0, "buenos dias", "good morning")
        val kept = r.keptRows()
        assertEquals("buenos dias", kept[0].fields[0])
        assertEquals("good morning", kept[0].fields[1])
    }

    /**
     * The edit has to be visible *before* publish, or triage keeps showing the text the card had
     * before it was fixed and the card editor reopens on it — which is what shipping the edits
     * only through [ImportRepository.keptRows] did.
     */
    @Test
    fun updateRowShowsInCurrentDraft() = runBlocking {
        val r = repo()
        r.parse("hola — hello\ngracias — thanks")
        r.updateRow(0, "buenos dias", "good morning")
        val rows = r.currentDraft()?.rows.orEmpty()
        assertEquals("buenos dias", rows[0].fields[0])
        assertEquals("good morning", rows[0].fields[1])
        // The untouched row is left exactly as parsed.
        assertEquals("gracias", rows[1].fields[0])
    }

    /** `isValid` is recomputed from the edit, not carried over from the parse. */
    @Test
    fun updateRowEmptyingBothSidesInvalidatesTheRow() = runBlocking {
        val r = repo()
        r.parse("hola — hello\ngracias — thanks")
        r.updateRow(0, "", "")
        assertEquals(false, r.currentDraft()?.rows?.first { it.index == 0 }?.isValid)
    }

    @Test
    fun parseResetsTriageState() = runBlocking {
        val r = repo()
        r.parse("a — 1\nb — 2")
        r.setDecision(0, TriageDecision.Discard)
        r.parse("c — 3\nd — 4")
        assertEquals(2, r.keptRows().size)
        assertTrue(r.decisions().isEmpty())
    }

    @Test
    fun anExplicitSeparatorOverridesAutoDetection() = runBlocking {
        // ": " makes auto-detection choose Colon; forcing Comma must win (spec §5.2).
        val text = "fruit: apple, red\nsky: blue, wide"

        val auto = repo().parse(text).getOrThrow()
        val forced = repo().parse(text, Separator.Comma).getOrThrow()

        assertIs<Separator.Colon>(auto.separator)
        assertIs<Separator.Comma>(forced.separator)
        assertEquals(listOf("fruit: apple", "red"), forced.rows.first().fields)
    }

    @Test
    fun theAutoSeparatorStillMeansDetect() = runBlocking {
        // Needs two lines: a single line always falls back to SingleColumn.
        val explicitAuto = repo().parse("hola,hello\nadios,bye", Separator.Auto).getOrThrow()

        assertIs<Separator.Comma>(explicitAuto.separator)
        Unit
    }

    // ── bulk / Anki import ───────────────────────────────────────────────

    @Test
    fun bulkImportAcceptsADeckFarLargerThanAPaste() = runBlocking {
        // An Anki "Notes in Plain Text" export: tab-separated, which the existing rules already
        // handle (spec §6 rule 3) — zero new dependencies.
        val text = (1..5_000).joinToString("\n") { "front$it\tback$it" }

        val draft = repo().parseBulk(text).getOrThrow()

        assertEquals(Separator.Tab, draft.separator)
        assertEquals(expected = 5_000, actual = draft.rows.size)
        assertEquals(expected = 0, actual = draft.truncated)
    }

    @Test
    fun aPasteThatSizeIsRejectedRatherThanSilentlyHalved() = runBlocking {
        val text = (1..5_000).joinToString("\n") { "front$it\tback$it" }

        // The paste box keeps a modest character cap: the constraint is the human reading it.
        assertTrue(repo().parse(text).isFailure)
    }

    @Test
    fun truncationIsReportedRatherThanSilent() = runBlocking {
        // Short rows so the 2000-card cap is what bites, not the character cap. Duplicates are
        // collapsed after the cap is applied, so `truncated` still reflects the rows dropped.
        val text = (1..2_001).joinToString("\n") { "a\tb" }

        val draft = repo().parse(text).getOrThrow()

        assertEquals(expected = 1, actual = draft.truncated, "the dropped row was not reported")
    }

    @Test
    fun anAnkiExportWithATagsColumnStillParsesFrontAndBack() = runBlocking {
        // Anki's export carries an optional third tags column; cards have no tags (spec §8), so
        // it is dropped — the first two columns still parse cleanly.
        val text = "hola\thello\tspanish::greetings\ngracias\tthank you\tspanish"

        val draft = repo().parseBulk(text).getOrThrow()

        assertEquals(listOf("hola", "hello"), draft.rows[0].fields)
        assertEquals(listOf("gracias", "thank you"), draft.rows[1].fields)
    }

    @Test
    fun bulkImportDropsRowsMissingASideInsteadOfFailingThePublish() = runBlocking {
        // Bulk has no triage step, so a half-row has nowhere to be fixed. It used to survive
        // keptRows() and reach publish(), which rejects an empty side — one blank line in a
        // 20k-card export failed the entire import.
        val repo = repo()
        val text = "hola\thello\nhuerfano\t\n\tsolo\ngracias\tthank you"

        val draft = repo.parseBulk(text).getOrThrow()

        assertEquals(expected = 4, actual = draft.rows.size, "the parse should still see them")
        assertEquals(
            expected = listOf("hola", "gracias"),
            actual = repo.keptRows().map { it.fields[0] },
            "rows missing a front or back must not reach publish",
        )
    }

    @Test
    fun aParseSupersededByTheNextOneDoesNotOverwriteTheDraft() = runBlocking {
        // Parses used to run to completion in one main-thread turn and so could never interleave.
        // Off the main thread they can, and the loser must not clear the winner's triage state or
        // assign a stale draft over it.
        val repo = repo()

        val stale = launch { repo.parse((1..2_000).joinToString("\n") { "old$it\told$it" }) }
        stale.cancel()
        repo.parse("hola\thello\ngracias\tthank you").getOrThrow()
        stale.join()

        assertEquals(expected = 2, actual = repo.currentDraft()?.rows?.size)
        assertEquals(expected = "hola", actual = repo.currentDraft()?.rows?.get(0)?.fields?.get(0))
    }

    @Test
    fun aPasteKeepsRowsMissingASideSoTriageCanFillThemIn() = runBlocking {
        // The mirror of the above: spec §9 says a single-column paste becomes fronts with empty
        // backs that the user completes in triage. Dropping them there would delete their work.
        val repo = repo()

        repo.parse("dog\ncat\nbird").getOrThrow()

        assertEquals(expected = 3, actual = repo.keptRows().size)
    }
}
