package com.github.jvsena42.loopky.data.nexus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * [HttpFetcher] backed by [HttpURLConnection] — no client library needed for Loopky's handful of
 * plain REST calls.
 *
 * Shared by every JVM target: `HttpURLConnection` is in the JDK and in Android's platform, so
 * Android and the desktop `jvm()` target the CLI runs on make the same call the same way. That
 * matters more than it looks for the CLI, whose `--json` output is a *verification* channel
 * (#54) — a second HTTP implementation is a second set of quirks for a read to disagree on.
 */
class JvmHttpFetcher : HttpFetcher {

    override suspend fun send(request: HttpRequest): Result<HttpResponse> = withContext(Dispatchers.IO) {
        // Plain `runCatching`: the block is synchronous inside `withContext`, so there is no
        // suspension point for it to swallow a cancellation at.
        runCatching {
            val connection = URL(request.url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = request.timeoutMs.toInt()
                connection.readTimeout = request.timeoutMs.toInt()
                connection.requestMethod = request.method.name
                connection.setRequestProperty("Accept", "application/json")
                request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                request.body?.let { body ->
                    val bytes = body.encodeToByteArray()
                    connection.doOutput = true
                    // Without an explicit length HttpURLConnection falls back to chunked transfer
                    // encoding, which some gateways reject outright on a small JSON POST.
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }

                val code = connection.responseCode
                // `inputStream` throws on >= 400 — reading `errorStream` instead is the whole
                // reason a failure body ever reaches the caller. It is null when there is no body.
                val stream = if (code in SUCCESS_RANGE) connection.inputStream else connection.errorStream
                HttpResponse(
                    statusCode = code,
                    body = stream?.bufferedReader()?.use { it.readText() }.orEmpty(),
                    headers = connection.singleValueHeaders(),
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * [HttpURLConnection.getHeaderFields] keys the status line under a **null** key, which would
     * blow up the lower-casing below, so it is filtered out rather than mapped.
     */
    private fun HttpURLConnection.singleValueHeaders(): Map<String, String> =
        headerFields
            .filterKeys { it != null }
            .entries
            .associate { (name, values) -> name.lowercase() to values.joinToString(", ") }
}
