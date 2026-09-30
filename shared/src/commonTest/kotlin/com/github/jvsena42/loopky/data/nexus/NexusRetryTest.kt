package com.github.jvsena42.loopky.data.nexus

import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the indexer reads retry, and — more importantly — what they refuse to (#321).
 *
 * The refusals are the load-bearing half: a backoff chain against a condition that cannot change
 * only delays the error block the reader was always going to get, on the one screen that could have
 * told them to check their connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NexusRetryTest {

    private val http = FakeHttpFetcher()
    private val client = NexusClient(http, baseUrl = BASE)

    private val tagsUrl = "$BASE/v0/search/tags/by_prefix/spa?limit=10"

    @Test
    fun aServerErrorIsRetriedAndTheSecondAttemptWins() = runTest {
        http.enqueueFailure(HttpMethod.GET, tagsUrl, HttpError(503, "unavailable"))
        http.enqueue(HttpMethod.GET, tagsUrl, HttpResponse(statusCode = 200, body = """["spanish"]"""))

        val result = client.searchTagsByPrefix("spa")
        advanceUntilIdle()

        assertEquals(listOf("spanish"), result.getOrThrow())
        assertEquals(2, http.requestedUrls.count { it == tagsUrl })
    }

    @Test
    fun aRateLimitIsRetried() = runTest {
        http.enqueueFailure(HttpMethod.GET, tagsUrl, HttpError(429, "slow down"))
        http.enqueue(HttpMethod.GET, tagsUrl, HttpResponse(statusCode = 200, body = """[]"""))

        assertEquals(emptyList(), client.searchTagsByPrefix("spa").getOrThrow())
        assertEquals(2, http.requestedUrls.count { it == tagsUrl })
    }

    @Test
    fun beingOfflineIsNotRetried() = runTest {
        // A device with no route answers the same way in 250ms and in 2s. Backing off against it
        // only makes the reader wait longer for the error that tells them to check the connection.
        http.fail(tagsUrl, RuntimeException("Unable to resolve host \"nexus.staging.pubky.app\""))

        val result = client.searchTagsByPrefix("spa")

        assertTrue(result.isFailure)
        assertEquals(1, http.requestedUrls.count { it == tagsUrl })
    }

    @Test
    fun aNotFoundIsNotRetried() = runTest {
        // An indexer that predates the endpoint (#134). Asking three times does not deploy it.
        http.fail(tagsUrl, HttpError(404, "no such endpoint"))

        assertTrue(client.searchTagsByPrefix("spa").isFailure)
        assertEquals(1, http.requestedUrls.count { it == tagsUrl })
    }

    @Test
    fun retriesAreBounded() = runTest {
        repeat(10) { http.enqueueFailure(HttpMethod.GET, tagsUrl, HttpError(500, "boom")) }

        val result = client.searchTagsByPrefix("spa")
        advanceUntilIdle()

        assertTrue(result.isFailure)
        // The original attempt plus MAX_RETRIES — a failing indexer must not be hammered.
        assertEquals(3, http.requestedUrls.count { it == tagsUrl })
    }

    private companion object {
        const val BASE = "https://nexus.test"
    }
}
