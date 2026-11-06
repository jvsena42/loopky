package com.github.jvsena42.loopky.data.report

import com.github.jvsena42.loopky.data.nexus.HttpError
import com.github.jvsena42.loopky.data.nexus.HttpFetcher
import com.github.jvsena42.loopky.data.nexus.HttpMethod
import com.github.jvsena42.loopky.data.nexus.HttpRequest
import com.github.jvsena42.loopky.data.repository.impl.loopkyJson
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Sends a report to pubky.app's `POST /api/report`, which files it in the Chatwoot Reports inbox
 * pubky.app's moderators already work from (#577). Unauthenticated: the endpoint takes the
 * reporter's pubky and name as given. [baseUrl] is the environment's pubky.app origin, so a
 * staging build never files into the production inbox.
 */
class ReportClient(
    private val http: HttpFetcher,
    private val baseUrl: String,
) {
    suspend fun submit(report: ReportRequest): Result<Unit> = runSuspendCatching {
        val response = http.send(
            HttpRequest(
                url = "$baseUrl$REPORT_PATH",
                method = HttpMethod.POST,
                headers = mapOf("Content-Type" to "application/json"),
                body = loopkyJson.encodeToString(report),
            ),
        ).getOrThrow()
        if (!response.isSuccess) {
            throw HttpError(response.statusCode, "POST $REPORT_PATH failed with HTTP ${response.statusCode}")
        }
    }

    private companion object {
        const val REPORT_PATH = "/api/report"
    }
}

/**
 * pubky.app's report body. Field names are its own: `postUrl` is any https URL of the reported
 * thing, and every field must be non-blank or the endpoint answers 400.
 */
@Serializable
data class ReportRequest(
    val pubky: String,
    val name: String,
    val postUrl: String,
    val issueType: String,
    val reason: String,
)
