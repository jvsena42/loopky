package com.github.jvsena42.loopky.domain

import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.SrsState
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.YearRecap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The site draws the recap a second time in JavaScript. This file and `tools/recap.test.mjs` in
 * `loopky.github.io` read the same `recap-fixture.json`, so the two cannot drift apart silently.
 */
class YearRecapFixtureJvmTest {

    @Test
    fun `the recap matches the fixture the site is held to`() {
        val text = requireNotNull(javaClass.getResource("/recap-fixture.json")) { "fixture missing" }.readText()
        val fixture = Json.parseToJsonElement(text).jsonObject
        val decks = fixture.getValue("decks").jsonArray.map { it.jsonObject }
        val expected = fixture.getValue("expected").jsonObject

        val recap = YearRecap.from(
            year = fixture.getValue("year").jsonPrimitive.int,
            owned = decks.filter { it.getValue("owned").jsonPrimitive.booleanOrNull == true }.map(::deck),
            followed = decks.filter { it.getValue("owned").jsonPrimitive.booleanOrNull == false }.map(::deck),
            statesByDeck = decks.associate { it.string("id").orEmpty() to states(it) },
            thresholdDays = fixture.getValue("thresholdDays").jsonPrimitive.int,
        )

        val top = expected.getValue("topDeck").jsonObject
        assertEquals(expected.getValue("cardsStudied").jsonPrimitive.int, recap.cardsStudied)
        assertEquals(expected.getValue("cardsMastered").jsonPrimitive.int, recap.cardsMastered)
        assertEquals(expected.getValue("decksStudied").jsonPrimitive.int, recap.decksStudied)
        assertEquals(top.string("title"), recap.topDeck?.title)
        assertEquals(top.string("coverEmoji"), recap.topDeck?.coverEmoji)
        assertEquals(top.getValue("cardsStudied").jsonPrimitive.int, recap.topDeck?.cardsStudied)
        assertEquals(expected.getValue("decksCreated").jsonPrimitive.int, recap.decksCreated)
        assertEquals(expected.getValue("cardsCreated").jsonPrimitive.int, recap.cardsCreated)
        assertEquals(expected.strings("languages"), recap.languages)
        assertEquals(expected.strings("topics"), recap.topics)
        assertEquals(expected.string("persona"), recap.persona.name)
        assertEquals(expected.getValue("isEmpty").jsonPrimitive.booleanOrNull, recap.isEmpty)
    }

    private fun JsonObject.string(key: String): String? = getValue(key).jsonPrimitive.contentOrNull

    private fun JsonObject.strings(key: String): List<String> =
        getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun deck(json: JsonObject) = Deck(
        id = json.string("id").orEmpty(),
        authorPubky = if (json.getValue("owned").jsonPrimitive.booleanOrNull == true) "me" else "them",
        title = json.string("title").orEmpty(),
        description = null,
        coverEmoji = json.string("coverEmoji"),
        coverImageRef = null,
        tags = json.strings("tags").map(::Tag),
        createdAt = json.getValue("createdAt").jsonPrimitive.long,
        updatedAt = json.getValue("createdAt").jsonPrimitive.long,
        cardCount = json.getValue("cardCount").jsonPrimitive.int,
        frontLang = json.string("frontLang"),
        backLang = json.string("backLang"),
    )

    private fun states(json: JsonObject): List<SrsState> =
        json.getValue("states").jsonArray.mapIndexed { index, element ->
            val state = element.jsonObject
            SrsState(
                cardId = "c$index",
                dueAt = state.getValue("dueAt").jsonPrimitive.long,
                intervalDays = state.getValue("intervalDays").jsonPrimitive.int,
                easeFactor = 2.5,
                repetitions = 1,
                lastGrade = null,
            )
        }
}
