package com.github.jvsena42.loopky.mcp.security

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * Reads at most [maxBytes] of the body, answering 413 and returning null past that. Reads one
 * byte more than the cap rather than trusting `Content-Length`, which a client can omit or lie about.
 */
suspend fun ApplicationCall.receiveTextLimited(maxBytes: Int): String? {
    val declared = request.contentLength()
    if (declared != null && declared > maxBytes) {
        respondText("Request body too large", status = HttpStatusCode.PayloadTooLarge)
        return null
    }
    val bytes = receiveChannel().readRemaining(maxBytes.toLong() + 1).readByteArray()
    if (bytes.size > maxBytes) {
        respondText("Request body too large", status = HttpStatusCode.PayloadTooLarge)
        return null
    }
    return bytes.decodeToString()
}
