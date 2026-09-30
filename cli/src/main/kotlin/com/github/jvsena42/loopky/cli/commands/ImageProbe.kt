package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CLI_VERSION
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.URL

/**
 * `--check-images`: ask each distinct picture URL whether it is a picture, once.
 *
 * The static checks in `ImageUrls.kt` answer what a *string* can be known to be wrong about, and
 * they cover most of it. Three things they cannot see produce exactly the same silent blank card
 * (#229, item 4): a Wikipedia lead image that resolves to `.stl` or `.webm` behind a URL that
 * looks fine, a file that has been renamed or deleted, and a host that refuses an unfamiliar
 * client. One `HEAD` catches all three, and the caller ended up writing that check by hand anyway.
 *
 * Four properties keep it consistent with "nothing is uploaded, nothing is fetched":
 *
 * - **Opt-in.** Off by default, so an ordinary `card add` stays one write and no round trips.
 * - **Never fatal.** A host that is having a bad minute, or that refuses `HEAD` in a way the
 *   fallback does not cover, must not be able to fail somebody's import — the picture may be
 *   perfectly good. Every finding is a warning on stderr and a row in the result.
 * - **One request per distinct URL**, not per card. A picture on forty cards is asked about once.
 * - **"Wrong" and "not checked" are separate answers** (#257, item 1). A run of 475 Wikimedia
 *   URLs came back with 432 of them "wrong" — every one a 429 this check had provoked itself,
 *   and between them they scrolled the run's single real finding off the screen. A `429`, a
 *   timeout or an unreachable host says nothing about the picture, so it is counted apart, and
 *   neither bucket may print more than [MAX_REPORTED] lines.
 */
@Serializable
data class ImageCheck(
    val url: String,
    /** The HTTP status, or null when the host could not be reached at all. */
    val status: Int? = null,
    @SerialName("content_type") val contentType: String? = null,
    /** True when the host answered 2xx with an `image/…` content type. Everything else is worth reading. */
    val ok: Boolean = false,
    /**
     * True when the host gave no usable answer — a 429 that outlasted the retries, a timeout, a
     * 5xx, a DNS failure. **Not a finding about the picture**, which may be perfectly good.
     *
     * Distinct from [ok] rather than folded into it so a caller can tell "this URL is broken"
     * from "nobody asked successfully": the first is worth fixing, the second worth re-running.
     */
    val unverified: Boolean = false,
    /** Why this is being reported, for a person. Null when [ok]. */
    val reason: String? = null,
)

/** Whether this invocation asked for the probe. */
internal fun Args.checksImages(): Boolean = has(CHECK_IMAGES_FLAG)

internal const val CHECK_IMAGES_FLAG = "check-images"

internal const val CHECK_IMAGES_CONCURRENCY_FLAG = "check-images-concurrency"

/**
 * How many requests this run may have in flight.
 *
 * Refused above [MAX_PROBE_CONCURRENCY] rather than coerced: silently doing something other than
 * what was asked for is how the default came to be too high in the first place.
 */
internal fun Args.imageCheckConcurrency(): Int {
    val requested = positiveIntOrNull(CHECK_IMAGES_CONCURRENCY_FLAG) ?: return PROBE_CONCURRENCY
    if (requested > MAX_PROBE_CONCURRENCY) {
        throw CliError(
            ExitCode.Usage,
            "--$CHECK_IMAGES_CONCURRENCY_FLAG is $requested; $MAX_PROBE_CONCURRENCY is the most " +
                "this client will open against one host. More than that is how the check " +
                "rate-limits itself into reporting working pictures as broken.",
        )
    }
    return requested
}

/**
 * Refuse an image-check option that cannot mean anything as given.
 *
 * `--check-images-concurrency` is read only from behind `if (args.checksImages())`, so without
 * `--check-images` it was accepted and silently ignored — and its own value went unchecked with
 * it. Silently doing something other than what was asked for is the failure this whole flag exists
 * to stop, and the surface already hard-errors on the two analogous cases (`--separator` on an
 * `.apkg`, `--if-not-exists` without `--id`).
 */
internal fun Args.requireImageCheckOptions() {
    if (!has(CHECK_IMAGES_CONCURRENCY_FLAG)) return
    if (!checksImages()) {
        throw CliError(
            ExitCode.Usage,
            "--$CHECK_IMAGES_CONCURRENCY_FLAG means nothing without --$CHECK_IMAGES_FLAG: there " +
                "are no requests to spread. Pass both, or neither.",
        )
    }
    imageCheckConcurrency()
}

/**
 * Probe [urls], returning **only what is worth reporting** — an address that answered 2xx with an
 * image content type produces no row and no note.
 *
 * Concurrency is capped rather than unbounded, and low: 475 Wikimedia URLs at eight in flight
 * produced 432 rate-limited answers and no information (#257). The same list read three at a
 * time, retrying a `429`, comes back clean.
 */
internal suspend fun checkImageUrls(
    urls: Collection<String>,
    onNote: (String) -> Unit,
    concurrency: Int = PROBE_CONCURRENCY,
    probe: suspend (String) -> ImageCheck = ::probeImage,
): List<ImageCheck> {
    val distinct = urls.filter { it.isNotBlank() }.distinct()
    if (distinct.isEmpty()) return emptyList()
    onNote("loopky: checking ${distinct.size} distinct picture URL(s)…")

    // A permit rather than a barrier per group. `chunked(n)` waits for the slowest URL in each
    // group before starting the next, so with the retry path one throttled address can hold two
    // healthy ones for a couple of `Retry-After`s — which matters now the cap is three, not eight.
    val gate = Semaphore(concurrency.coerceAtLeast(1))
    val answers = coroutineScope {
        distinct.map { url -> async { gate.withPermit { probe(url) } } }.awaitAll()
    }
    val problems = answers.filterNot { it.ok }
    val (unverified, wrong) = problems.partition { it.unverified }

    // Unverified first, wrong second: a terminal keeps its last lines, and "this URL is broken" is
    // the half worth reading. The summary goes last for the same reason.
    unverified.report("could not be checked", onNote)
    wrong.report("wrong", onNote)

    if (problems.isEmpty()) {
        onNote("loopky: every picture URL answered with an image.")
    } else {
        // Named as a warning rather than left to be inferred from the rows: the write goes ahead.
        onNote(
            "loopky: picture URLs — ${answers.size - problems.size} ok, ${wrong.size} wrong, " +
                "${unverified.size} could not be checked; writing anyway.",
        )
    }
    return problems
}

/**
 * One bucket's rows on stderr, at most [MAX_REPORTED] of them.
 *
 * The cap is the finding from #257: 432 lines of the same rate-limit answer buried the one line
 * that mattered, and an agent reading that output learns to skip the block. `--json` carries every
 * row, so nothing is lost by not printing them all.
 */
private fun List<ImageCheck>.report(label: String, onNote: (String) -> Unit) {
    if (isEmpty()) return
    onNote("loopky: $size picture URL(s) $label:")
    take(MAX_REPORTED).forEach { onNote("loopky:   ${it.describe()}") }
    if (size > MAX_REPORTED) {
        onNote("loopky:   … and ${size - MAX_REPORTED} more — every one is in --json image_checks.")
    }
}

private fun ImageCheck.describe(): String = buildString {
    append(url)
    append(" — ")
    append(reason)
    status?.let { append(" (HTTP $it") }
    contentType?.let { append(", $it") }
    if (status != null) append(")")
}

/**
 * One URL's answer, retried through a host that is rate-limiting.
 *
 * `HEAD` first, because the point is to spend nothing: a 4 MB picture answers this in a few
 * hundred bytes. A host that refuses the method — 403 or 405 to a `HEAD` it serves happily to a
 * `GET` is a real and common configuration — is asked again with a one-byte ranged `GET`, so a
 * working picture is not reported as broken by a quirk of the method.
 *
 * A `429` is retried rather than believed, honouring `Retry-After` when the host sends one. It is
 * the answer this check provokes in itself, and reporting it as a finding turns a working deck
 * into hundreds of false ones.
 *
 * The user agent is not decoration. Wikimedia answers `403 Please set a user-agent` to a generic
 * one, which is the very failure mode this exists to catch, and a probe that produced it on every
 * Wikimedia URL would be worse than no probe.
 */
private suspend fun probeImage(url: String): ImageCheck = withContext(Dispatchers.IO) {
    var answer = attempt(url)
    var backoff = RATE_LIMIT_BACKOFF_MS
    repeat(RATE_LIMIT_ATTEMPTS - 1) {
        if (answer.status != TOO_MANY_REQUESTS) return@withContext answer.classified(url)
        delay(answer.retryAfterMs ?: backoff)
        backoff *= 2
        answer = attempt(url)
    }
    answer.classified(url)
}

private fun attempt(url: String): ProbeAnswer {
    val head = request(url, "HEAD")
    return if (head.status in METHOD_REFUSED) request(url, "GET", ranged = true) else head
}

/**
 * What one request came back with, before it is judged. `internal` so the judging — which is the
 * whole of the check's opinion — is testable without a host to answer.
 */
internal class ProbeAnswer(
    val status: Int?,
    val contentType: String?,
    val failure: String? = null,
    /** `Retry-After` in milliseconds, when the host sent a parseable one. */
    val retryAfterMs: Long? = null,
)

internal fun ProbeAnswer.classified(url: String): ImageCheck {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase()
    return when {
        failure != null -> ImageCheck(url, unverified = true, reason = "could not be reached: $failure")

        // Nothing about the picture: this client asked too fast, or the host is having a bad
        // minute. Folding either into "look wrong" is what made a 475-URL run unreadable (#257).
        status == TOO_MANY_REQUESTS -> ImageCheck(
            url,
            status,
            type,
            unverified = true,
            reason = "the host is rate-limiting this check — try again, or --$CHECK_IMAGES_CONCURRENCY_FLAG 1",
        )

        status in SERVER_ERROR -> ImageCheck(
            url,
            status,
            type,
            unverified = true,
            reason = "the host answered with an error of its own, which says nothing about the picture",
        )

        status !in SUCCESS -> ImageCheck(url, status, type, reason = "the host refused it")
        type == null -> ImageCheck(url, status, null, reason = "the host named no content type")
        !type.startsWith(IMAGE_PREFIX) ->
            ImageCheck(url, status, type, reason = "this is not an image — both apps will render a blank card")

        // An `image/` type is not the same as a decodable one, and this is where the difference
        // bites: Wikimedia serves an SVG original as `image/svg+xml` with a perfectly ordinary
        // 200, so a prefix check calls the whole flags deck fine. See `imageUrlAdvice`.
        type in UNDECODABLE_IMAGE_TYPES -> ImageCheck(
            url,
            status,
            type,
            reason = "neither app decodes this — use a JPEG, PNG or WebP (a Wikimedia /thumb/ URL renders one)",
        )

        else -> ImageCheck(url, status, type, ok = true)
    }
}

private fun request(url: String, method: String, ranged: Boolean = false): ProbeAnswer {
    val connection = runCatching { URL(url).openConnection() as HttpURLConnection }
        .getOrElse { return ProbeAnswer(null, null, it.message ?: "bad URL") }
    return try {
        connection.requestMethod = method
        connection.connectTimeout = PROBE_CONNECT_TIMEOUT_MS
        connection.readTimeout = PROBE_READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "image/*,*/*;q=0.8")
        // One byte, so the fallback for a host that refuses HEAD still transfers nothing.
        if (ranged) connection.setRequestProperty("Range", "bytes=0-0")
        val status = connection.responseCode
        ProbeAnswer(status, connection.contentType, retryAfterMs = connection.retryAfterMs())
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        // Every failure here is "the host did not answer", which is the finding. Nothing is
        // rethrown: the probe is advisory, and an unreachable host must not fail the write.
        ProbeAnswer(null, null, error.message ?: error::class.simpleName)
    } finally {
        runCatching { connection.disconnect() }
    }
}

/**
 * `Retry-After` as a wait, capped.
 *
 * Seconds only — the HTTP-date form is legal and Wikimedia does not send it, and a date parsed
 * against a skewed clock would produce a wait nobody asked for. Capped because a host is entitled
 * to say "an hour" and this is a pre-flight check, not a crawler.
 */
private fun HttpURLConnection.retryAfterMs(): Long? =
    getHeaderField("Retry-After")?.trim()?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?.let { (it * MILLIS_PER_SECOND).coerceAtMost(MAX_RETRY_AFTER_MS) }

/**
 * What Wikimedia asks for, and what every other host is entitled to see. `403 Please set a
 * user-agent` to a generic client is a real answer from a real host in this path.
 */
private val USER_AGENT = "loopky/$CLI_VERSION (+https://github.com/jvsena42/loopky)"

/** Statuses that mean "not this method", worth one ranged GET before believing them. */
private val METHOD_REFUSED = setOf(403, 405, 501)

private val SUCCESS = 200..299

private val SERVER_ERROR = 500..599

private const val TOO_MANY_REQUESTS = 429

private const val IMAGE_PREFIX = "image/"

/** `image/` types that are still a blank card: Coil ships no SVG decoder here and UIImage has none. */
private val UNDECODABLE_IMAGE_TYPES = setOf("image/svg+xml", "image/svg", "image/tiff", "image/x-tiff")

/**
 * Few enough not to be rate-limited into a false negative.
 *
 * It was eight, and eight is what a 475-URL Wikimedia deck answers 429 to 432 times (#257).
 * Measured against `upload.wikimedia.org` on 2026-09-06, more in flight is also *slower*, so this
 * costs nothing to fix: 100 URLs took 32s at three and 42s at six, and 250 came back 250/250 clean
 * in 83s at three with no 429 at all. `--check-images-concurrency` is there for a host that is not
 * Wikimedia.
 */
internal const val PROBE_CONCURRENCY = 3

/** The most `--check-images-concurrency` may ask for. See [Args.imageCheckConcurrency]. */
internal const val MAX_PROBE_CONCURRENCY = 16

/** Rows of one bucket printed on stderr before the rest are left to `--json`. */
private const val MAX_REPORTED = 20

private const val RATE_LIMIT_ATTEMPTS = 3
private const val RATE_LIMIT_BACKOFF_MS = 500L
private const val MAX_RETRY_AFTER_MS = 5_000L
private const val MILLIS_PER_SECOND = 1_000L

private const val PROBE_CONNECT_TIMEOUT_MS = 5_000
private const val PROBE_READ_TIMEOUT_MS = 10_000
