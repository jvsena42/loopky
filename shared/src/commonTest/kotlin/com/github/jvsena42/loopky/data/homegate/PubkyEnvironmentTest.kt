package com.github.jvsena42.loopky.data.homegate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PubkyEnvironmentTest {

    @Test
    fun theTwoEnvironmentsNeverShareAHomeserverOrAGate() {
        // The pairing is the whole point of the type: a token minted by one Homegate is only
        // valid on its own homeserver, and a signup token is single-use, so crossing them is
        // not a recoverable mistake.
        assertNotEquals(PubkyEnvironment.Staging.homegateBaseUrl, PubkyEnvironment.Production.homegateBaseUrl)
        assertNotEquals(PubkyEnvironment.Staging.defaultHomeserver, PubkyEnvironment.Production.defaultHomeserver)
        assertNotEquals(PubkyEnvironment.Staging.nexusBaseUrl, PubkyEnvironment.Production.nexusBaseUrl)
    }

    @Test
    fun theHomeserversMatchPubkyRingsConstants() {
        // Verbatim from pubky-ring `src/utils/constants.ts:2-3`. If Ring's defaults ever move,
        // a key created there and a deck published here would land on different servers.
        assertEquals(
            "ufibwbmed6jeq9k4p583go95wofakh9fwpp4k734trq79pd9u1uy",
            PubkyEnvironment.Staging.defaultHomeserver,
        )
        assertEquals(
            "8um71us3fyw6h8wbcxb5ar3rwusy1a6u49956ikzojg3gcwd1dty",
            PubkyEnvironment.Production.defaultHomeserver,
        )
    }

    @Test
    fun anUnknownOrMissingNameResolvesToProduction() {
        // Production is the safe fallback: a confused build pointed at staging would mint tokens
        // the production homeserver rejects, which is worse than the reverse.
        assertEquals(PubkyEnvironment.Production, PubkyEnvironment.fromNameOrProduction(null))
        assertEquals(PubkyEnvironment.Production, PubkyEnvironment.fromNameOrProduction(""))
        assertEquals(PubkyEnvironment.Production, PubkyEnvironment.fromNameOrProduction("nonsense"))
    }

    @Test
    fun namesRoundTripSoAPersistedChoiceSurvives() {
        // The Settings override stores the enum name; a mismatch here silently reverts a debug
        // build to production on the next launch.
        PubkyEnvironment.entries.forEach { environment ->
            assertEquals(environment, PubkyEnvironment.fromNameOrProduction(environment.name))
        }
        assertEquals(PubkyEnvironment.Staging, PubkyEnvironment.fromNameOrProduction("staging"))
    }

    @Test
    fun everyGateIsHttps() {
        PubkyEnvironment.entries.forEach {
            assertTrue(it.homegateBaseUrl.startsWith("https://"), "${it.name} must not be plaintext")
            assertTrue(it.webBaseUrl.startsWith("https://"), "${it.name} web must not be plaintext")
            assertTrue(it.nexusBaseUrl.startsWith("https://"), "${it.name} indexer must not be plaintext")
        }
    }

    @Test
    fun theIndexerIsOnTheSameNetworkAsTheGateAndTheWebClient() {
        // The whole reason the indexer moved into this enum (#205): it used to travel on its own
        // BuildConfig wire, and a mismatched one does not error — Nexus answers normally, for the
        // other network, so discovery, tags, followers, search and every avatar just come back
        // empty. Hosts, not URLs, because only the host decides which deployment answers.
        PubkyEnvironment.entries.forEach { environment ->
            val family = environment.webBaseUrl.removePrefix(HTTPS)
            assertTrue(
                environment.nexusBaseUrl.removePrefix(HTTPS).endsWith(family),
                "${environment.name}: indexer ${environment.nexusBaseUrl} is not under $family",
            )
            assertTrue(
                environment.homegateBaseUrl.removePrefix(HTTPS).endsWith(family),
                "${environment.name}: gate ${environment.homegateBaseUrl} is not under $family",
            )
        }
        // `pubky.app` is a suffix of `staging.pubky.app`, so the check above passes for a
        // production entry pointed at a staging host. This is what catches that direction.
        listOf(
            PubkyEnvironment.Production.nexusBaseUrl,
            PubkyEnvironment.Production.homegateBaseUrl,
            PubkyEnvironment.Production.webBaseUrl,
        ).forEach { assertFalse(it.contains(STAGING_MARKER), "production must not read staging: $it") }
        listOf(
            PubkyEnvironment.Staging.nexusBaseUrl,
            PubkyEnvironment.Staging.homegateBaseUrl,
            PubkyEnvironment.Staging.webBaseUrl,
        ).forEach { assertTrue(it.contains(STAGING_MARKER), "staging must not read production: $it") }
    }

    @Test
    fun theIndexerIsTheOneMatchingTheBuildsNetwork() {
        // The literals `androidApp/build.gradle.kts` and `iOSApp.swift` used to carry on a second
        // wire, now reached through the environment name alone.
        assertEquals(
            "https://nexus.staging.pubky.app",
            PubkyEnvironment.fromNameOrProduction("Staging").nexusBaseUrl,
        )
        assertEquals(
            "https://nexus.pubky.app",
            PubkyEnvironment.fromNameOrProduction("Production").nexusBaseUrl,
        )
    }

    @Test
    fun theWebClientIsTheOneMatchingTheBuildsNetwork() {
        // The deployments index separate networks: an account created against staging has no
        // profile page on pubky.app at all, so a shared host would 404 for every debug user.
        assertEquals("https://staging.pubky.app", PubkyEnvironment.Staging.webBaseUrl)
        assertEquals("https://pubky.app", PubkyEnvironment.Production.webBaseUrl)
        assertNotEquals(PubkyEnvironment.Staging.webBaseUrl, PubkyEnvironment.Production.webBaseUrl)
    }

    @Test
    fun aProfileUrlIsPubkyAppsOwnShareRoute() {
        // pubky-app builds `{origin}/profile/{pubky}` in `useProfileMenuActions`; a link handed
        // out from Loopky has to be the same one, or it lands on a route that does not exist.
        val pubky = "n1zpc53jzy8hbwbcxb5ar3rwusy1a6u49956ikzojg3gcwd1dty"
        assertEquals(
            "https://pubky.app/profile/$pubky",
            PubkyEnvironment.Production.profileUrl(pubky),
        )
        assertEquals(
            "https://staging.pubky.app/profile/$pubky",
            PubkyEnvironment.Staging.profileUrl(pubky),
        )
    }

    @Test
    fun theBuildConfigNameDecidesWhichPubkyAppTheUserOpens() {
        // Closes the loop from the build type to the browser: `androidApp/build.gradle.kts` writes
        // these two literals into `BuildConfig.PUBKY_ENV` (debug -> Staging, release ->
        // Production), `LoopkyApp` resolves them through `fromNameOrProduction`, and this is the
        // URL the profile screens then hand to the browser. If the gradle literals move, this is
        // what catches it.
        val pubky = "somepubky"
        assertEquals(
            "https://staging.pubky.app/profile/$pubky",
            PubkyEnvironment.fromNameOrProduction("Staging").profileUrl(pubky),
        )
        assertEquals(
            "https://pubky.app/profile/$pubky",
            PubkyEnvironment.fromNameOrProduction("Production").profileUrl(pubky),
        )
    }

    @Test
    fun aProfileUrlNeverDoublesTheSeparator() {
        // The host and the route are joined by exactly one slash. A trailing slash on either
        // side produces `//profile/…`, which is a different path.
        PubkyEnvironment.entries.forEach { environment ->
            val url = environment.profileUrl("somepubky")
            assertFalse(url.removePrefix(HTTPS).contains("//"), "${environment.name}: $url")
        }
    }

    private companion object {
        const val HTTPS = "https://"

        /** What every staging host carries and no production host may. */
        const val STAGING_MARKER = "staging."
    }
}
