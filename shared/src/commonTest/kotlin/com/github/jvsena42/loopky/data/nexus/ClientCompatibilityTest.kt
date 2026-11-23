package com.github.jvsena42.loopky.data.nexus

import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientCompatibilityTest {

    private val http = FakeHttpFetcher()
    private val compatibility = ClientCompatibility()
    private val client = NexusClient(http, baseUrl = BASE, compatibility = compatibility)

    @Test
    fun goneOrUpgradeRequiredMarksTheBuildOutdated() = runTest {
        for (status in listOf(410, 426)) {
            val compat = ClientCompatibility()
            val nexus = NexusClient(http, baseUrl = BASE, compatibility = compat)
            http.fail("$BASE/v0/search/tags/by_prefix/ab?limit=10", HttpError(statusCode = status, message = "gone"))

            nexus.searchTagsByPrefix("ab")

            assertTrue(compat.outdated.value, "status $status")
        }
    }

    @Test
    fun badInputAndMissingThingsDoNot() = runTest {
        // A 400 is also a malformed search and a 404 an unindexed user: neither may lock readers out.
        for (status in listOf(400, 404, 500)) {
            http.fail("$BASE/v0/search/tags/by_prefix/ab?limit=10", HttpError(statusCode = status, message = "no"))

            client.searchTagsByPrefix("ab")
        }

        assertFalse(compatibility.outdated.value)
    }

    private companion object {
        const val BASE = "https://nexus.test"
    }
}
