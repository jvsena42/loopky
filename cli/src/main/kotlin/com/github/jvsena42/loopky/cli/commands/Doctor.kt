package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliEnvironment
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.UpdateChecker
import com.github.jvsena42.loopky.cli.cliJson
import com.github.jvsena42.loopky.cli.requireUsableOperand
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

@Serializable
data class DoctorResult(
    /** `http://host:port`, never the credentials; null when no proxy is configured. */
    val proxy: String?,
    val homeserver: String,
    /**
     * The ordinary domain the homeserver's pkarr record names — or, when that lookup failed for the
     * environment's own default homeserver, the domain it is known to have. Null only for a
     * `--homeserver` whose record could not be read.
     */
    @SerialName("homeserver_host") val homeserverHost: String?,
    val hosts: List<HostCheck>,
    /** Every host above, deduplicated — what to paste into an allowlist. */
    val allowlist: List<String>,
    /** False when the homeserver's host could not be learned, so the list above is missing it. */
    @SerialName("allowlist_complete") val allowlistComplete: Boolean,
    /**
     * What to do about it, addressed to the agent reading this: which hosts to ask the human for,
     * and where each sandbox product keeps that setting. Null when nothing is wrong.
     */
    @SerialName("next_step") val nextStep: String? = null,
)

@Serializable
data class HostCheck(
    val host: String,
    @SerialName("needed_for") val neededFor: String,
    /**
     * `reachable` (any HTTP answer, even an error), `refused` (by a proxy), `intercepted` (TLS
     * re-signed by a CA this client does not trust), `unreachable`, or — for the homeserver only —
     * `method_blocked` (reads pass, but a proxy answers writes itself).
     */
    val status: String,
    val detail: String?,
    @SerialName("ms") val millis: Long,
)

/**
 * [resolveHttps] is [com.github.jvsena42.loopky.data.pubky.PubkyClient.resolveHttps].
 *
 * Every host `loopky` talks to, asked directly, so an agent behind an allowlist learns which hosts to
 * ask for rather than meeting them one failed command at a time (#212).
 *
 * Needs no session on purpose: a sandbox without egress is exactly where signing in fails, and the
 * diagnostic cannot depend on what it diagnoses. The homeserver is the environment's default unless
 * `--homeserver` names another; its ordinary domain comes from its own pkarr record, because that is
 * the host the client falls back to when the record's direct address is unreachable — which, behind
 * a proxy, it always is.
 */
internal suspend fun doctor(
    args: Args,
    resolveHttps: suspend (String) -> Result<String>,
    environment: CliEnvironment,
    probes: DoctorProbes = DoctorProbes(),
    proxy: String? = configuredProxy(),
): CommandResult {
    val probe = retryingTimeout(probes.read)
    val homeserver = args.option("homeserver")
        ?.let { requireUsableOperand(it.trim().removePrefix("pubky"), "--homeserver") }
        ?: environment.pubky.defaultHomeserver
    val lookup = resolveHttps(homeserver)
    val resolvedHost = lookup.getOrNull()?.let(::icannTarget)
    // In the sandbox #212 is about, the relays are blocked too, so the lookup fails exactly where
    // the list is needed — and an allowlist missing the homeserver costs a second round trip
    // through a human. The default homeserver's domain is known; only a `--homeserver` is not.
    val homeserverHost = resolvedHost ?: KNOWN_HOMESERVER_HOSTS[homeserver]
    val lookupFailure = lookup.exceptionOrNull()?.let { "pkarr lookup of $homeserver failed: ${it.message}" }
        ?: "pkarr record of $homeserver names no ordinary domain".takeIf { resolvedHost == null }

    val targets = buildList {
        add(Target(RELAY_URL, "login (Pubky Ring approval)"))
        PKARR_RELAYS.forEach { add(Target(it, "finding any homeserver (pkarr; either relay is enough)")) }
        homeserverHost?.let { add(Target("https://$it/", "every deck read and write")) }
        add(Target(environment.indexer, "tag trending and indexer reads"))
        add(Target(UpdateChecker.manifestUrl(), "install and loopky update"))
    }
    val probed = coroutineScope {
        targets.map { target -> async { target to probe(target.url) } }.awaitAll()
    }.flatMap { (target, outcome) -> followRedirects(target, outcome, probe) }
    val checks = withAssetCdn(probed, probe).withWriteCheck(homeserverHost, probes.write)

    val blocking = blockingChecks(checks, relaysWork = resolvedHost != null)
    // These probes run on the JVM; the lookup ran in the SDK. The JVM reaching a relay the SDK
    // could not use, behind a proxy, is the two stacks disagreeing — in practice a proxy re-signing
    // TLS with a CA the JVM was given and the SDK's bundled roots lack. Reporting 0 there, as the
    // JVM's view alone would, sends an agent on to commands that all fail.
    val sdkDisagrees = proxy != null && lookup.isFailure &&
        checks.any { it.host in PKARR_RELAYS.map(::hostOf) && it.status == REACHABLE }
    val exit = exitFor(blocking, hostKnown = homeserverHost != null, sdkDisagrees = sdkDisagrees)
    val base = DoctorResult(
        proxy = proxy,
        homeserver = homeserver,
        homeserverHost = homeserverHost,
        hosts = checks,
        allowlist = checks.map { it.host }.distinct(),
        allowlistComplete = homeserverHost != null,
    )
    val report = base.copy(nextStep = exit?.let { nextStep(it, blocking, base.allowlist, sdkDisagrees) })
    val text = render(report, lookupFailure)
    if (exit == null) return result(report, text)
    // The summary first: a failure's text reaches stderr behind `loopky: `, which would otherwise
    // knock the table's first row out of line with the rest.
    throw CliError(
        exit,
        "${summarize(blocking, sdkDisagrees)}\n$text",
        cliJson.encodeToJsonElement(DoctorResult.serializer(), report),
    )
}

private fun exitFor(blocking: List<HostCheck>, hostKnown: Boolean, sdkDisagrees: Boolean): ExitCode? = when {
    blocking.any { it.status == REFUSED || it.status == METHOD_BLOCKED } -> ExitCode.ProxyRefused
    blocking.any { it.status == INTERCEPTED } || sdkDisagrees -> ExitCode.TlsUntrusted
    !hostKnown || blocking.any { it.status == UNREACHABLE } -> ExitCode.Network
    else -> null
}

/**
 * Addressed to an agent, which cannot change a sandbox's network itself: stop, and ask the human
 * for exactly this. Both products keep the setting per environment.
 */
private fun nextStep(exit: ExitCode, blocking: List<HostCheck>, allowlist: List<String>, sdkDisagrees: Boolean): String =
    when (exit) {
        ExitCode.ProxyRefused if blocking.none { it.status == REFUSED } ->
            "Reads reach the homeserver but its proxy refuses writes, so every deck and card change would " +
                "fail. Ask the user to allow all HTTP methods in this sandbox's network settings, then run " +
                "`loopky doctor` again. Codex: the environment's settings, Agent internet access, and turn off " +
                "the restriction to GET, HEAD and OPTIONS — loopky writes with PUT and DELETE."
        ExitCode.ProxyRefused -> {
            val refused = blocking.filter { it.status == REFUSED }.map { it.host }
            "Ask the user to allow these hosts in this sandbox's network settings, then run `loopky doctor` " +
                "again: ${refused.joinToString(" ")}. Claude Code on the web: the environment's settings, " +
                "Network access: Custom, keep the default domains, and add them under Allowed domains. " +
                "Codex: the environment's settings, Agent internet access: On, and add them as allowed " +
                "domains — leave the GET/HEAD/OPTIONS-only restriction off, since loopky writes with PUT " +
                "and DELETE. The complete list for this machine: ${allowlist.joinToString(" ")}."
        }
        ExitCode.TlsUntrusted ->
            (if (sdkDisagrees) "The pubky SDK could not use relays this machine reaches. " else "") +
                "This proxy re-signs TLS, and the pubky SDK trusts only public certificate authorities, " +
                "so loopky cannot work through it yet (pubky/pubky-homeserver#648). Ask the user to exempt " +
                "these hosts from TLS inspection, or to run loopky where egress is not intercepted: " +
                "${allowlist.joinToString(" ")}."
        else ->
            "Nothing refused these hosts; they did not answer. Retry once; if it persists, the proxy or " +
                "the network in front of it is down — nothing on the allowlist will change that."
    }

/**
 * One slow answer under load must not read as `unreachable` and exit 5 (#369), so a timed-out probe
 * is asked once more. Only a timeout: it costs up to another [PROBE_TIMEOUT_MS], and only on a path
 * that is already failing.
 */
private fun retryingTimeout(probe: suspend (String) -> ProbeOutcome): suspend (String) -> ProbeOutcome = { url ->
    val first = probe(url)
    if (!first.timedOut) {
        first
    } else {
        val second = probe(url)
        val detail = if (second.timedOut) {
            "timed out twice: ${second.detail}"
        } else {
            listOfNotNull("answered on the second try, after a timeout", second.detail).joinToString("; ")
        }
        second.copy(detail = detail, millis = first.millis + second.millis)
    }
}

/**
 * Every other probe is a GET, so a proxy that lets only GET/HEAD/OPTIONS through passes all of them
 * and then refuses every write (#363). An unauthenticated PUT tells the two apart: the homeserver
 * answers it 401, while a method filter answers first with its own 403 or 405. Asked only once the
 * homeserver's GET got through — anything else is already reported.
 */
private suspend fun List<HostCheck>.withWriteCheck(
    homeserverHost: String?,
    writeProbe: suspend (String) -> WriteAnswer?,
): List<HostCheck> {
    val read = firstOrNull { it.host == homeserverHost && it.status == REACHABLE } ?: return this
    val answer = writeProbe("https://$homeserverHost$WRITE_PROBE_PATH") ?: return this
    if (answer.code !in METHOD_REFUSALS) return this
    val blocked = read.copy(
        status = METHOD_BLOCKED,
        detail = "a PUT was answered ${answer.code} by something other than the homeserver" +
            answer.body.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty(),
    )
    return map { if (it === read) blocked else it }
}

/** The asset CDN is only met through GitHub's redirect, which a refused github.com never issues. */
private suspend fun withAssetCdn(checks: List<HostCheck>, probe: suspend (String) -> ProbeOutcome): List<HostCheck> {
    if (checks.any { it.host == ASSET_CDN_HOST }) return checks
    val outcome = probe(ASSET_CDN_URL)
    return checks + HostCheck(ASSET_CDN_HOST, "install and loopky update", outcome.status, outcome.detail, outcome.millis)
}

/**
 * The checks that decide the exit code. The two pkarr relays are either/or — pkarr races them — so
 * one refused relay is not a failure while the other answers, or while a lookup through them just
 * succeeded, which is direct evidence one of them works. A real sandbox allows one and refuses the
 * other (#357).
 */
private fun blockingChecks(checks: List<HostCheck>, relaysWork: Boolean): List<HostCheck> {
    val relayHosts = PKARR_RELAYS.map(::hostOf).toSet()
    val relays = checks.filter { it.host in relayHosts }
    val relaysOk = relaysWork || relays.any { it.status == REACHABLE }
    return if (relaysOk) checks - relays.toSet() else checks
}

private fun summarize(blocking: List<HostCheck>, sdkDisagrees: Boolean): String {
    fun hosts(status: String) = blocking.filter { it.status == status }.map { it.host }
    return listOfNotNull(
        SDK_DISAGREES.takeIf { sdkDisagrees },
        hosts(REFUSED).takeIf { it.isNotEmpty() }?.let { "refused by the proxy: ${it.joinToString(", ")}" },
        hosts(METHOD_BLOCKED).takeIf { it.isNotEmpty() }
            ?.let { "writes refused by the proxy: ${it.joinToString(", ")}" },
        hosts(INTERCEPTED).takeIf { it.isNotEmpty() }?.let { "TLS intercepted: ${it.joinToString(", ")}" },
        hosts(UNREACHABLE).takeIf { it.isNotEmpty() }?.let { "unreachable: ${it.joinToString(", ")}" },
    ).joinToString("; ").ifEmpty { "the homeserver's host could not be learned" }
}

private data class Target(val url: String, val neededFor: String)

/**
 * GitHub answers a release asset github.com → github.com → its asset CDN, and an allowlist needs
 * every host on the way. Same-host hops are followed but not reported twice.
 */
private suspend fun followRedirects(
    target: Target,
    first: ProbeOutcome,
    probe: suspend (String) -> ProbeOutcome,
): List<HostCheck> {
    val origin = HostCheck(hostOf(target.url), target.neededFor, first.status, first.detail, first.millis)
    val checks = mutableListOf(origin)
    var from = target.url
    var next = first.redirect
    repeat(MAX_REDIRECTS) {
        // A Location may be relative (a captive portal's `/login`) or malformed; either ends the chain.
        val url = next?.let { location -> runCatching { URI(from).resolve(location).toString() }.getOrNull() }
            ?: return checks
        val host = hostOrNull(url) ?: return checks
        val outcome = probe(url)
        from = url
        if (checks.none { it.host == host }) {
            checks += HostCheck(host, target.neededFor, outcome.status, outcome.detail, outcome.millis)
        }
        next = outcome.redirect
    }
    return checks
}

private fun render(report: DoctorResult, lookupFailure: String?): String = buildString {
    appendLine("Proxy:      ${report.proxy ?: "none"}")
    appendLine("Homeserver: ${report.homeserver}")
    lookupFailure?.let { appendLine("            $it") }
    val width = report.hosts.maxOf { it.host.length }
    report.hosts.forEach { check ->
        val status = if (check.status == REACHABLE) check.status else check.status.uppercase()
        append("  ${status.padEnd(STATUS_WIDTH)} ${check.host.padEnd(width)}  ${check.neededFor}")
        appendLine(check.detail?.let { " — $it" }.orEmpty())
    }
    if (report.allowlistComplete) {
        append("Allowlist:  ${report.allowlist.joinToString(" ")}")
    } else {
        appendLine("Allowlist (incomplete — the homeserver's host is missing; allow the pkarr relays and re-run):")
        append("            ${report.allowlist.joinToString(" ")}")
    }
    report.nextStep?.let { append("\n\nNext step: $it") }
}

/**
 * The first HTTPS record target that is an ordinary domain rather than `.` or a pkarr key, from
 * `resolve_https`'s `{"https_records": [...]}`.
 */
internal fun icannTarget(resolved: String): String? =
    runCatching { Json.parseToJsonElement(resolved).jsonObject.getValue("https_records").jsonArray }.getOrNull()
        ?.mapNotNull { (it as? JsonObject)?.get("target")?.jsonPrimitive?.contentOrNull?.trimEnd('.') }
        ?.firstOrNull { '.' in it }

internal fun hostOf(url: String): String = requireNotNull(hostOrNull(url)) { "no host in $url" }

private fun hostOrNull(url: String): String? = runCatching { URI(url).host }.getOrNull()

/** The JVM's effective proxy, which [com.github.jvsena42.loopky.cli.ProxyEnvironment] set from the environment. */
private fun configuredProxy(): String? {
    val host = System.getProperty("https.proxyHost") ?: return null
    return "http://$host:${System.getProperty("https.proxyPort") ?: DEFAULT_PROXY_PORT}"
}

/** pubky's `DEFAULT_HTTP_RELAY_INBOX`, where `loopky login` waits for Ring. */
private const val RELAY_URL = "https://httprelay.pubky.app/"

/** pkarr's `DEFAULT_RELAYS`, which `libpubkycore` resolves every homeserver through. */
private val PKARR_RELAYS = listOf("https://pkarr.pubky.app/", "https://pkarr.pubky.org/")

/** Each environment's default homeserver and the ordinary domain its record names (checked 2026-09-28). */
private val KNOWN_HOMESERVER_HOSTS = mapOf(
    PubkyEnvironment.Production.defaultHomeserver to "homeserver.pubky.app",
    PubkyEnvironment.Staging.defaultHomeserver to "homeserver.staging.pubky.app",
)

private const val SDK_DISAGREES =
    "the pubky SDK could not use the pkarr relays this machine reached — TLS intercepted by the proxy"

private const val ASSET_CDN_HOST = "release-assets.githubusercontent.com"
private const val ASSET_CDN_URL = "https://$ASSET_CDN_HOST/"

internal const val REACHABLE = "reachable"
internal const val REFUSED = "refused"
internal const val UNREACHABLE = "unreachable"
internal const val INTERCEPTED = "intercepted"
internal const val METHOD_BLOCKED = "method_blocked"

private const val WRITE_PROBE_PATH = "/pub/loopky/doctor"
private val METHOD_REFUSALS = setOf(403, 405)

private const val STATUS_WIDTH = 11
private const val MAX_REDIRECTS = 4
internal const val PROBE_TIMEOUT_MS = 5_000
private const val DEFAULT_PROXY_PORT = 80
