package com.github.jvsena42.loopky.data.pubky

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules that decide which pubky.app records Delete account may treat as Loopky's. A false
 * "yes" deletes another app's record, so most cases here are near misses that must answer no.
 */
class LoopkyPubkyAppRecordsTest {

    private val owner = "ownerpk"
    private val ownDeck = "pubky://$owner/pub/loopky/decks/deck1/manifest.json"
    private val othersDeck = "pubky://otherpk/pub/loopky/decks/deck9/manifest.json"
    private val recap = PubkyLinks.recapWebUrl(owner, 2026)

    private fun link(uri: String) = PostDto(
        content = "x",
        kind = PostKinds.LINK,
        embed = PostEmbedDto(kind = PostKinds.LINK, uri = uri),
    )

    private fun loopkys(post: PostDto, vararg labels: String) =
        LoopkyPubkyAppRecords.isLoopkyPost(owner, post, labels.toSet())

    // --- profile.json ---------------------------------------------------------------------------

    @Test
    fun aProfileWithOnlyNameAndBioIsLoopkys() {
        assertTrue(LoopkyPubkyAppRecords.isLoopkyProfile("""{"name":"Ana","bio":"hi"}"""))
        assertTrue(LoopkyPubkyAppRecords.isLoopkyProfile("""{"name":"Ana"}"""))
        assertTrue(LoopkyPubkyAppRecords.isLoopkyProfile("""{}"""))
    }

    @Test
    fun aNullImageIsStillLoopkys() {
        assertTrue(LoopkyPubkyAppRecords.isLoopkyProfile("""{"name":"Ana","bio":null,"image":null}"""))
    }

    @Test
    fun aProfilePictureMeansAnotherClientWroteTheProfile() {
        // The editor only carries a published picture forward; it never sets one.
        assertFalse(LoopkyPubkyAppRecords.isLoopkyProfile("""{"name":"Ana","image":"pubky://ownerpk/pub/pubky.app/files/1"}"""))
    }

    @Test
    fun linksOrAStatusMeanAnotherClientWroteTheProfile() {
        assertFalse(LoopkyPubkyAppRecords.isLoopkyProfile("""{"name":"Ana","links":[]}"""))
        assertFalse(LoopkyPubkyAppRecords.isLoopkyProfile("""{"name":"Ana","status":"busy"}"""))
        assertFalse(LoopkyPubkyAppRecords.isLoopkyProfile("""{"name":"Ana","links":null}"""))
    }

    @Test
    fun aProfileThatIsNotAJsonObjectIsNotLoopkys() {
        assertFalse(LoopkyPubkyAppRecords.isLoopkyProfile("not json"))
        assertFalse(LoopkyPubkyAppRecords.isLoopkyProfile("""["name"]"""))
        assertFalse(LoopkyPubkyAppRecords.isLoopkyProfile(""))
    }

    // --- Announcements ---------------------------------------------------------------------------

    @Test
    fun anAnnouncementOfAnOwnDeckIsLoopkysWithOrWithoutItsLabel() {
        // The label is best-effort, so the own-deck embed has to stand on its own.
        assertTrue(loopkys(link(ownDeck)))
        assertTrue(loopkys(link(ownDeck), "loopky-deck", "spanish"))
    }

    @Test
    fun anAnnouncementOfAFollowedDeckNeedsTheDeckLabel() {
        assertTrue(loopkys(link(othersDeck), "loopky-deck"))
        // Without it, this is someone sharing a deck link by hand on pubky.app.
        assertFalse(loopkys(link(othersDeck)))
        assertFalse(loopkys(link(othersDeck), "spanish", "loopky"))
    }

    @Test
    fun aLinkToSomethingInLoopkysNamespaceThatIsNotADeckIsNotAnAnnouncement() {
        assertFalse(loopkys(link("pubky://otherpk/pub/loopky/settings.json"), "loopky-deck"))
    }

    @Test
    fun aShortEmbedOfAnOwnDeckIsNotLoopkys() {
        // Loopky only writes link embeds; a short embed is a repost made by another client.
        val repost = PostDto(content = "x", kind = PostKinds.SHORT, embed = PostEmbedDto(PostKinds.SHORT, ownDeck))
        assertFalse(loopkys(repost))
        val mixed = PostDto(content = "x", kind = PostKinds.LINK, embed = PostEmbedDto(PostKinds.SHORT, ownDeck))
        assertFalse(loopkys(mixed))
    }

    @Test
    fun anOwnDeckAddressHiddenInAnotherOwnersPathIsNotOwn() {
        assertFalse(loopkys(link("pubky://ownerpk2/pub/loopky/decks/deck1/manifest.json")))
    }

    // --- Recaps ---------------------------------------------------------------------------------

    @Test
    fun aRecapOfTheOwnerIsLoopkys() {
        assertTrue(loopkys(link(recap)))
        assertTrue(loopkys(link(PubkyLinks.recapWebUrl(owner, 2025)), "loopky-recap"))
    }

    @Test
    fun someoneElsesRecapIsNotLoopkys() {
        assertFalse(loopkys(link(PubkyLinks.recapWebUrl("otherpk", 2026))))
    }

    @Test
    fun aRecapAddressWithoutAYearIsNotOne() {
        assertFalse(PubkyLinks.isRecapWebUrlOf("https://loopky.app/recap/?pubky=$owner&year=", owner))
        assertFalse(PubkyLinks.isRecapWebUrlOf("https://loopky.app/recap/?pubky=$owner&year=2026&x=1", owner))
        assertFalse(PubkyLinks.isRecapWebUrlOf("https://loopky.app/recap/?pubky=${owner}x&year=2026", owner))
        assertTrue(PubkyLinks.isRecapWebUrlOf(recap, owner))
    }

    @Test
    fun anotherLoopkyPageIsNotARecap() {
        assertFalse(loopkys(link(PubkyLinks.profileWebUrl(owner))))
        assertFalse(loopkys(link("https://loopky.app/")))
    }

    // --- Deck requests --------------------------------------------------------------------------

    @Test
    fun aDeckRequestNeedsBothItsLabels() {
        val request = PostDto(content = "Make me a deck about tides", kind = PostKinds.SHORT)
        assertTrue(loopkys(request, "loopky", "deck-request"))
        assertFalse(loopkys(request, "loopky"))
        assertFalse(loopkys(request, "deck-request"))
        assertFalse(loopkys(request))
    }

    @Test
    fun aLongPostWithTheDeckRequestLabelsIsNotOne() {
        val long = PostDto(content = "an essay", kind = "long")
        assertFalse(loopkys(long, "loopky", "deck-request"))
    }

    @Test
    fun anOrdinaryPostIsNotLoopkys() {
        assertFalse(loopkys(PostDto(content = "good morning")))
        assertFalse(loopkys(link("https://example.com/")))
        assertFalse(loopkys(link("https://example.com/"), "loopky-deck"))
    }

    // --- Addresses ------------------------------------------------------------------------------

    @Test
    fun profileAddressesAreRecognisedForAnyOwner() {
        assertTrue(LoopkyPubkyAppRecords.isProfileUri(PubkyPaths.profile("otherpk")))
        assertFalse(LoopkyPubkyAppRecords.isProfileUri("pubky://otherpk/pub/pubky.app/profile.json.bak"))
        assertFalse(LoopkyPubkyAppRecords.isProfileUri("pubky://otherpk/pub/pubky.app/posts/1"))
        assertFalse(LoopkyPubkyAppRecords.isProfileUri("https://otherpk/pub/pubky.app/profile.json"))
        assertFalse(LoopkyPubkyAppRecords.isProfileUri("pubky:///pub/pubky.app/profile.json"))
    }

    @Test
    fun loopkyNamespaceAddressesAreRecognisedForAnyOwner() {
        assertTrue(LoopkyPubkyAppRecords.isLoopkyNamespaceUri(othersDeck))
        assertFalse(LoopkyPubkyAppRecords.isLoopkyNamespaceUri("pubky://otherpk/pub/loopkyish/x"))
        assertFalse(LoopkyPubkyAppRecords.isLoopkyNamespaceUri("pubky://otherpk/pub/pubky.app/posts/1"))
    }
}
