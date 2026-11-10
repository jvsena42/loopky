package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.data.repository.impl.loopkyJson
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.ChunkMeta
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.Tag
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckDtosTest {

    private fun deck(
        listenEnabled: Boolean = true,
        speakEnabled: Boolean = true,
        typeEnabled: Boolean = false,
        reverseEnabled: Boolean = false,
        speechBothSides: Boolean = false,
        frontLang: String? = null,
        backLang: String? = null,
        cover: MediaRef.Image? = null,
    ) = Deck(
        id = "deck1",
        authorPubky = "pk:author",
        title = "Spanish Basics",
        description = "Greetings",
        coverEmoji = "🇪🇸",
        coverImageRef = cover,
        tags = listOf(Tag("spanish"), Tag("a1")),
        createdAt = 1_739_000_000_000L,
        updatedAt = 1_739_000_500_000L,
        cardCount = 1,
        chunks = listOf(ChunkMeta(n = 0, count = 1, updatedAt = 1L)),
        listenEnabled = listenEnabled,
        speakEnabled = speakEnabled,
        typeEnabled = typeEnabled,
        reverseEnabled = reverseEnabled,
        speechBothSides = speechBothSides,
        frontLang = frontLang,
        backLang = backLang,
    )

    @Test
    fun manifestRoundTripPreservesTheChunkTable() {
        val deck = deck()
        val back = loopkyJson.decodeFromString<ManifestDto>(
            loopkyJson.encodeToString(deck.toDto()),
        ).toDomain()
        assertEquals(1, back.cardCount)
        assertEquals(listOf(ChunkMeta(n = 0, count = 1, updatedAt = 1L)), back.chunks)
    }

    @Test
    fun manifestCarriesNoPerCardEntries() {
        // The card index is what made the manifest grow ~73 bytes per card; it must not come back.
        val json = loopkyJson.encodeToString(deck().toDto())
        assertFalse(json.contains("\"cards\""), "manifest still carries a card index: $json")
    }

    @Test
    fun cardRoundTripPreservesStudyOrder() {
        val card = Card(
            id = "c1",
            deckId = "deck1",
            updatedAt = 5L,
            front = CardSide(text = "hola"),
            back = CardSide(text = "hello"),
            ord = 3000L,
        )
        val back = loopkyJson.decodeFromString<CardDto>(
            loopkyJson.encodeToString(card.toDto()),
        ).toDomain()
        assertEquals(3000L, back.ord)
        assertEquals(card, back)
    }

    @Test
    fun provenanceRoundTrips() {
        val deck = deck().copy(
            source = DeckSource(
                kind = DeckSource.Kind.Clone,
                uri = "pubky://author/pub/loopky/decks/d/manifest.json",
                importedAt = 42L,
            ),
        )
        val back = loopkyJson.decodeFromString<ManifestDto>(
            loopkyJson.encodeToString(deck.toDto()),
        ).toDomain()
        assertEquals(DeckSource.Kind.Clone, back.source?.kind)
        assertEquals("pubky://author/pub/loopky/decks/d/manifest.json", back.source?.uri)
        assertEquals(42L, back.source?.importedAt)
    }

    @Test
    fun manifestRoundTripPreservesOptions() {
        val deck = deck(listenEnabled = false, speakEnabled = true)
        val json = loopkyJson.encodeToString(deck.toDto())
        val back = loopkyJson.decodeFromString<ManifestDto>(json).toDomain()
        assertFalse(back.listenEnabled)
        assertTrue(back.speakEnabled)
    }

    @Test
    fun legacyManifestWithoutOptionsDefaultsToEnabled() {
        // A manifest written before listen/speak existed.
        val legacy = """
            {"schema_version":1,"deck_id":"d","author_pubky":"pk","title":"T",
             "created_at":1,"updated_at":2}
        """.trimIndent()
        val back = loopkyJson.decodeFromString<ManifestDto>(legacy).toDomain()
        assertTrue(back.listenEnabled)
        assertTrue(back.speakEnabled)
    }

    @Test
    fun manifestRoundTripPreservesTypeEnabled() {
        val deck = deck(typeEnabled = true)
        val json = loopkyJson.encodeToString(deck.toDto())
        assertTrue(json.contains("\"type_enabled\":true"), "type_enabled missing from \$json")
        val back = loopkyJson.decodeFromString<ManifestDto>(json).toDomain()
        assertTrue(back.typeEnabled)
        // Additive, so the schema does not move.
        assertEquals(1, loopkyJson.decodeFromString<ManifestDto>(json).schema_version)
    }

    @Test
    fun legacyManifestWithoutTypeEnabledDecodesAsOff() {
        // Unlike listen/speak: a manifest written before typing existed says nothing about
        // whether its author wanted the mode, and off is the reading that surprises nobody.
        val legacy = """
            {"schema_version":1,"deck_id":"d","author_pubky":"pk","title":"T",
             "created_at":1,"updated_at":2}
        """.trimIndent()
        val back = loopkyJson.decodeFromString<ManifestDto>(legacy).toDomain()
        assertFalse(back.typeEnabled)
    }

    @Test
    fun manifestRoundTripPreservesReverseEnabled() {
        val deck = deck(reverseEnabled = true)
        val json = loopkyJson.encodeToString(deck.toDto())
        assertTrue(json.contains("\"reverse_enabled\":true"), "reverse_enabled missing from $json")
        val back = loopkyJson.decodeFromString<ManifestDto>(json).toDomain()
        assertTrue(back.reverseEnabled)
        // Additive, so the schema does not move.
        assertEquals(1, loopkyJson.decodeFromString<ManifestDto>(json).schema_version)
    }

    @Test
    fun legacyManifestWithoutReverseEnabledDecodesAsOff() {
        // Same reading as type_enabled: a manifest written before both directions existed says
        // nothing about whether its author meant the deck to be drilled that way.
        val legacy = """
            {"schema_version":1,"deck_id":"d","author_pubky":"pk","title":"T",
             "created_at":1,"updated_at":2}
        """.trimIndent()
        val back = loopkyJson.decodeFromString<ManifestDto>(legacy).toDomain()
        assertFalse(back.reverseEnabled)
    }

    @Test
    fun manifestRoundTripPreservesSpeechBothSides() {
        val json = loopkyJson.encodeToString(deck(speechBothSides = true).toDto())
        assertTrue(json.contains("\"speech_both_sides\":true"), "speech_both_sides missing from $json")
        assertTrue(loopkyJson.decodeFromString<ManifestDto>(json).toDomain().speechBothSides)
    }

    @Test
    fun legacyManifestWithoutSpeechBothSidesKeepsListenAndSpeakToTheAnswer() {
        val legacy = """
            {"schema_version":1,"deck_id":"d","author_pubky":"pk","title":"T",
             "created_at":1,"updated_at":2,"listen_enabled":true,"speak_enabled":true}
        """.trimIndent()
        val back = loopkyJson.decodeFromString<ManifestDto>(legacy).toDomain()
        assertTrue(back.listenEnabled)
        assertFalse(back.speechBothSides)
    }

    @Test
    fun manifestRoundTripPreservesTheLanguagePair() {
        val deck = deck(frontLang = "en-US", backLang = "es-ES")
        val json = loopkyJson.encodeToString(deck.toDto())
        assertTrue(json.contains("\"front_lang\":\"en-US\""), "front_lang missing from \$json")
        val back = loopkyJson.decodeFromString<ManifestDto>(json).toDomain()
        assertEquals("en-US", back.frontLang)
        assertEquals("es-ES", back.backLang)
        assertTrue(back.speechReady)
    }

    @Test
    fun legacyManifestWithoutLanguagesIsNotSpeechReady() {
        // Opt-ins default true, but with no declared pair listen/speak stay inert rather than
        // falling back to the reader's device locale.
        val legacy = """
            {"schema_version":1,"deck_id":"d","author_pubky":"pk","title":"T",
             "created_at":1,"updated_at":2}
        """.trimIndent()
        val back = loopkyJson.decodeFromString<ManifestDto>(legacy).toDomain()
        assertNull(back.frontLang)
        assertNull(back.backLang)
        assertFalse(back.speechReady)
    }

    @Test
    fun webCoverImageRoundTripsWithUrlAndNoBlob() {
        val cover = MediaRef.Image(
            path = "",
            mime = "image/jpeg",
            sha256 = "",
            width = null,
            height = null,
            url = "https://images.unsplash.com/photo-1.jpg",
        )
        val back = loopkyJson.decodeFromString<ManifestDto>(
            loopkyJson.encodeToString(deck(cover = cover).toDto()),
        ).toDomain()
        val image = back.coverImageRef!!
        assertTrue(image.isRemote)
        assertEquals("https://images.unsplash.com/photo-1.jpg", image.url)
        assertEquals("", image.sha256)
    }

    @Test
    fun blobCoverImageHasNoUrl() {
        val cover = MediaRef.Image(
            path = "media/abc.jpg",
            mime = "image/jpeg",
            sha256 = "abc",
            width = 512,
            height = 512,
        )
        val back = loopkyJson.decodeFromString<ManifestDto>(
            loopkyJson.encodeToString(deck(cover = cover).toDto()),
        ).toDomain()
        val image = back.coverImageRef!!
        assertFalse(image.isRemote)
        assertNull(image.url)
        assertEquals("abc", image.sha256)
    }
}
