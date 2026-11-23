package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.testing.testDeck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PubkyUrisTest {

    // ── isProfile ─────────────────────────────────────────────────────

    @Test
    fun recognisesAProfileUri() {
        assertTrue(PubkyUris.isProfile("pubky://$PUBKY/pub/pubky.app/profile.json"))
    }

    @Test
    fun recognisesASocialV1ProfileAndPostReference() {
        assertTrue(PubkyUris.isProfile("pubky://$PUBKY/pub/social/v1/profile.json"))
        assertTrue(PubkyUris.isPost("pubky://$PUBKY/pub/social/v1/posts/0034A0X7NJ52G"))
        // A version's file is not the post's address; tags name the versionless reference.
        assertTrue(!PubkyUris.isPost("pubky://$PUBKY/pub/social/v1/posts/0034A0X7NJ52G/0034A0X7NJ52G.json"))
    }

    @Test
    fun rejectsNonProfileUris() {
        assertTrue(!PubkyUris.isProfile("pubky://$PUBKY/pub/loopky/decks/d1/manifest.json"))
        assertTrue(!PubkyUris.isProfile("pubky://$PUBKY/pub/pubky.app/profile.json/extra"))
        assertTrue(!PubkyUris.isProfile("https://example.com/profile.json"))
        assertTrue(!PubkyUris.isProfile("pubky://"))
    }

    // ── parseDeckManifest ─────────────────────────────────────────────

    @Test
    fun parsesAManifestUriBackIntoAuthorAndDeckId() {
        val ref = PubkyUris.parseDeckManifest("pubky://$PUBKY/pub/loopky/decks/deck1/manifest.json")

        assertEquals(DeckRef(authorPubky = PUBKY, deckId = "deck1"), ref)
    }

    @Test
    fun roundTripsWhatDeckPubkyUriBuilds() {
        // Deck.pubkyUri builds the same string literally (domain can't depend on this layer), so
        // the parser has to stay in sync with it.
        val deck = testDeck(id = "deck1", authorPubky = PUBKY)

        assertEquals(
            DeckRef(authorPubky = PUBKY, deckId = "deck1"),
            PubkyUris.parseDeckManifest(deck.pubkyUri.value),
        )
    }

    @Test
    fun rejectsAnythingThatIsNotExactlyAManifest() {
        // These are the forged-tag shapes the global browse verification has to drop (#40).
        listOf(
            "pubky://$PUBKY/pub/loopky/decks/deck1/cards/0.json",
            "pubky://$PUBKY/pub/loopky/decks/deck1",
            "pubky://$PUBKY/pub/loopky/decks//manifest.json",
            "pubky://$PUBKY/pub/loopky/decks/deck1/nested/manifest.json",
            "pubky://$PUBKY/pub/pubky.app/posts/abc",
            "https://example.com/pub/loopky/decks/deck1/manifest.json",
            "pubky:///pub/loopky/decks/deck1/manifest.json",
            "",
        ).forEach { uri -> assertNull(PubkyUris.parseDeckManifest(uri), "should reject $uri") }
    }

    private companion object {
        const val PUBKY = "authorpubky"
    }
}
