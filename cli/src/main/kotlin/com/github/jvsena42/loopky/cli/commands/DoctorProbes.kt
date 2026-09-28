package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.isProxyRefusal
import com.github.jvsena42.loopky.cli.isUntrustedCertificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

/**
 * What one HTTPS request to a host came back with. [timedOut] singles out the one unreachable case
 * worth asking again: a refusal or a certificate failure answers the same every time.
 */
internal data class ProbeOutcome(
    val status: String,
    val detail: String?,
    val redirect: String?,
    val millis: Long,
    val timedOut: Boolean = false,
)

/** An HTTP answer to [unauthenticatedPut]: its status and the start of its body. */
internal data class WriteAnswer(val code: Int, val body: String)

/** The two requests `doctor` sends; replaced in tests, which must never reach the network. */
internal class DoctorProbes(
    val read: suspend (String) -> ProbeOutcome = ::httpsProbe,
    val write: suspend (String) -> WriteAnswer? = ::unauthenticatedPut,
)

internal suspend fun httpsProbe(url: String): ProbeOutcome = withContext(Dispatchers.IO) {
    val started = System.nanoTime()
    fun elapsed() = (System.nanoTime() - started) / NANOS_PER_MILLI
    runCatching {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            val redirect = connection.getHeaderField("Location")?.takeIf { code in REDIRECTS }
            if (code == HTTP_PROXY_AUTH) {
                ProbeOutcome(REFUSED, "the proxy refused its credentials (407)", null, elapsed())
            } else {
                ProbeOutcome(REACHABLE, null, redirect, elapsed())
            }
        } finally {
            connection.disconnect()
        }
    }.getOrElse { error ->
        val status = when {
            error.isProxyRefusal() -> REFUSED
            error.isUntrustedCertificate() -> INTERCEPTED
            else -> UNREACHABLE
        }
        ProbeOutcome(
            status,
            error.message ?: error::class.simpleName,
            null,
            elapsed(),
            timedOut = status == UNREACHABLE && error is SocketTimeoutException,
        )
    }
}

/** No session is sent, so nothing is written: the homeserver answers 401 before it looks at the path. */
private suspend fun unauthenticatedPut(url: String): WriteAnswer? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(0)
            connection.outputStream.close()
            val code = connection.responseCode
            val body = (connection.errorStream ?: connection.inputStream)?.use {
                it.readNBytes(WRITE_BODY_BYTES).decodeToString().trim()
            }.orEmpty()
            WriteAnswer(code, body)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

private const val WRITE_BODY_BYTES = 200
private const val NANOS_PER_MILLI = 1_000_000
private const val HTTP_PROXY_AUTH = 407
private val REDIRECTS = 300..399
