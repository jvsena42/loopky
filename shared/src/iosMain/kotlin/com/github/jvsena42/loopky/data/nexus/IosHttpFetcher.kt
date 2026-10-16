package com.github.jvsena42.loopky.data.nexus

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.coroutines.resume

/**
 * [HttpFetcher] backed by [NSURLSession] — keeps the shared module free of any HTTP client
 * dependency for Loopky's handful of plain REST calls.
 */
class IosHttpFetcher : HttpFetcher {

    /*
     * Ephemeral, never `sharedSession`: the shared session holds its cache and cookie store open
     * as locked SQLite files, and on a Mac a suspended app holding a lock outside the directories
     * RunningBoard allows is killed with 0xdead10cc. `AppURLSession` in the iOS app is the Swift
     * half of the same rule.
     */
    private val session = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration,
    )

    override suspend fun send(request: HttpRequest): Result<HttpResponse> {
        val nsUrl = NSURL.URLWithString(request.url)
            ?: return Result.failure(IllegalArgumentException("Invalid URL: ${request.url}"))

        val nsRequest = NSMutableURLRequest.requestWithURL(nsUrl)
        nsRequest.setHTTPMethod(request.method.name)
        // Without this the call inherits NSURLSessionConfiguration's 60s default, which would fire
        // partway through a 60s long-poll and report a payment as unconfirmed.
        nsRequest.setTimeoutInterval(request.timeoutMs / MILLIS_PER_SECOND)
        nsRequest.setValue("application/json", forHTTPHeaderField = "Accept")
        request.headers.forEach { (key, value) -> nsRequest.setValue(value, forHTTPHeaderField = key) }
        request.body?.let { body ->
            nsRequest.setHTTPBody(
                NSString.create(string = body).dataUsingEncoding(NSUTF8StringEncoding),
            )
        }

        return suspendCancellableCoroutine { continuation ->
            val task = session.dataTaskWithRequest(nsRequest) { data, response, error ->
                val result: Result<HttpResponse> = if (error != null) {
                    Result.failure(RuntimeException(error.localizedDescription))
                } else {
                    val http = response as? NSHTTPURLResponse
                    Result.success(
                        HttpResponse(
                            statusCode = http?.statusCode?.toInt() ?: 0,
                            // Read on every status, not just 2xx: a failure body is often the
                            // only place the reason is stated.
                            body = data.toUtf8String(),
                            headers = http.singleValueHeaders(),
                        ),
                    )
                }
                continuation.resume(result)
            }
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }

    private fun NSHTTPURLResponse?.singleValueHeaders(): Map<String, String> {
        val fields = this?.allHeaderFields ?: return emptyMap()
        return fields.entries.mapNotNull { entry ->
            val key = entry.key?.toString() ?: return@mapNotNull null
            key.lowercase() to entry.value.toString()
        }.toMap()
    }

    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun NSData?.toUtf8String(): String {
        if (this == null) return ""
        return NSString.create(data = this, encoding = NSUTF8StringEncoding) as? String ?: ""
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}
