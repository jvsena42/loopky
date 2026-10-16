package com.github.jvsena42.loopky.mcp.tools

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliEnvironment
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.commands.DRY_RUN_FLAG
import com.github.jvsena42.loopky.cli.commands.cardAdd
import com.github.jvsena42.loopky.cli.commands.cardEdit
import com.github.jvsena42.loopky.cli.commands.cardList
import com.github.jvsena42.loopky.cli.commands.cardMove
import com.github.jvsena42.loopky.cli.commands.cardRemove
import com.github.jvsena42.loopky.cli.commands.deckCreate
import com.github.jvsena42.loopky.cli.commands.deckEdit
import com.github.jvsena42.loopky.cli.commands.deckList
import com.github.jvsena42.loopky.cli.commands.deckShow
import com.github.jvsena42.loopky.cli.commands.import
import com.github.jvsena42.loopky.cli.commands.importDryRun
import com.github.jvsena42.loopky.cli.commands.requireImageCheckOptions
import com.github.jvsena42.loopky.cli.commands.tagTrending
import com.github.jvsena42.loopky.cli.requireKnownOptions
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.mcp.LOOPKY_CAPABILITIES
import com.github.jvsena42.loopky.mcp.Principal
import com.github.jvsena42.loopky.mcp.security.UserLimiter
import com.github.jvsena42.loopky.mcp.tenant.Tenant
import com.github.jvsena42.loopky.mcp.tenant.TenantPool
import com.github.jvsena42.loopky.mcp.tenant.TenantRefusal
import com.github.jvsena42.loopky.mcp.tools.ToolEnvelope.asToolResult
import com.github.jvsena42.loopky.util.Log
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * `tools/call`, as the MCP's own small `dispatch`: the whitelist, the argv, the tenant, the
 * command function and the envelope (#214).
 *
 * The routing table is the CLI's `route` for the commands the whitelist admits, calling the same
 * `:cli-core` functions with the tenant's repositories where `Main` passes Koin's. The two things
 * the CLI's `dispatch` does that this does not are deliberate: no `DeckWriteLock`, because the
 * pool already serializes a user's calls and a user owns their decks; and no update check, because
 * a hosted server is never the stale side.
 */
class ToolDispatcher(
    private val environment: PubkyEnvironment,
    private val tenants: TenantPool,
    private val scratch: InlineScratch,
    private val urlGuard: UrlGuard = UrlGuard.PUBLIC_HTTPS,
    private val tools: List<McpTool> = mcpTools(),
    private val userLimiter: UserLimiter? = null,
) {
    private val byName = tools.associateBy { it.name }

    fun tools(): List<Tool> = tools.map { it.toTool() }

    suspend fun call(principal: Principal, name: String, arguments: JsonObject): CallToolResult {
        val tool = byName[name] ?: return ToolEnvelope.failure(
            command = name,
            environment = environment,
            code = ExitCode.Usage.json,
            exit = ExitCode.Usage.code,
            message = "No tool `$name`. The tools are ${byName.keys.joinToString(", ")}.",
        ).asToolResult()
        val notes = mutableListOf<String>()
        return try {
            val invocation = ToolInvocation.of(tool, arguments)
            invocation.imageUrls.forEach { url ->
                urlGuard.refusal(url)?.let { throw CliError(ExitCode.BadInput, "$url $it.") }
            }
            if (tool.command.writes && !arguments.isDryRun()) chargeWrite(principal.pubky, invocation)
            val data = if (tool.command.path == WHOAMI) whoami(principal) else run(principal, invocation, notes)
            ToolEnvelope.success(tool.command.path, environment, data, notes)
        } catch (error: CliError) {
            failed(principal, tool, error, notes)
        } catch (refusal: TenantRefusal) {
            refused(tool, refusal, notes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            // The same classification the CLI's `main` makes of anything that escaped a command,
            // and for the same reason: an exit code the caller can branch on, never a stack trace.
            Log.e(TAG, "${tool.name} failed", error)
            failed(principal, tool, CliError(ExitCode.of(error), error.message ?: error::class.simpleName.orEmpty()), notes)
        }.asToolResult()
    }

    /**
     * The per-user write bucket and daily byte budget (#486): the homeserver's 1 GB quota is
     * shared with the user's own app, and a 507 is terminal, so a runaway agent stops here first.
     */
    private fun chargeWrite(pubky: String, invocation: ToolInvocation) {
        val limiter = userLimiter ?: return
        if (!limiter.tryWrite(pubky)) throw TenantRefusal.RateLimited(limiter.writeRetryAfterSeconds(pubky))
        val bytes = invocation.inline?.bytes?.size?.toLong() ?: 0L
        if (bytes > 0 && !limiter.tryChargeBytes(pubky, bytes)) {
            throw TenantRefusal.OverBudget(limiter.bytesRemainingToday(pubky))
        }
    }

    private fun JsonObject.isDryRun(): Boolean = (this["dry_run"] as? JsonPrimitive)?.booleanOrNull == true

    /** From the principal alone: no graph opened, no homeserver asked. */
    private fun whoami(principal: Principal): JsonObject = buildJsonObject {
        put("pubky", principal.pubky)
        put("client_id", principal.clientId)
        put("environment", environment.name.lowercase())
        put("indexer", environment.nexusBaseUrl)
        put("homeserver", environment.defaultHomeserver)
        put("capabilities", LOOPKY_CAPABILITIES)
    }

    private suspend fun run(principal: Principal, invocation: ToolInvocation, notes: MutableList<String>) =
        tenants.withTenant(principal.pubky) { tenant ->
            scratch.withFile(invocation.inline) { file ->
                val args = Args.parse(invocation.argv(file).toTypedArray())
                // The same two checks `dispatch` makes before any command, so a tool call is held
                // to the table the way a command line is.
                args.requireKnownOptions()
                args.requireImageCheckOptions()
                route(args, tenant, notes).data
            }
        }

    private suspend fun route(args: Args, tenant: Tenant, notes: MutableList<String>): CommandResult {
        val note: (String) -> Unit = { notes += it }
        // Progress is a counter for a terminal; a tool result carries the totals. Dropped.
        val progress: (String) -> Unit = {}
        return when (args.verb) {
            "deck list" -> deckList(tenant.decks)
            "deck show" -> deckShow(args, tenant.decks)
            "deck create" -> deckCreate(args, tenant.decks, tenant.session, note, progress)
            "deck edit" -> deckEdit(args, tenant.decks, note)
            "card list" -> cardList(args, tenant.decks, tenant.cards, note)
            "card add" -> cardAdd(args, tenant.decks, tenant.cards, note, progress)
            "card edit" -> cardEdit(args, tenant.decks, tenant.cards, note, progress)
            "card rm" -> cardRemove(args, tenant.decks)
            "card mv" -> cardMove(args, tenant.decks, tenant.cards)
            "import" -> if (args.has(DRY_RUN_FLAG)) {
                importDryRun(args, tenant.imports, note)
            } else {
                import(
                    args = args,
                    imports = tenant.imports,
                    decks = tenant.decks,
                    cards = tenant.cards,
                    media = tenant.media,
                    session = tenant.session,
                    onProgress = progress,
                    onNote = note,
                    lock = null,
                )
            }
            "tag trending" -> tagTrending(args, tenant.tags, CliEnvironment(environment, scratch.root))
            // Unreachable while the whitelist and this table agree; `ToolCatalogTest` holds them to it.
            else -> throw CliError(ExitCode.Usage, "`${args.verb}` is not served here.")
        }
    }

    private suspend fun failed(
        principal: Principal,
        tool: McpTool,
        error: CliError,
        notes: List<String>,
    ): JsonObject {
        // The FFI has already re-minted a bearer from the grant by the time a command reports
        // this, so what is left is a grant Ring revoked (#486): report it, drop the graph, and say
        // the user has to reconnect. Never a retry — the next call would open the same secret.
        val revoked = error.exitCode == ExitCode.SessionExpired
        if (revoked) tenants.revoke(principal.pubky)
        return ToolEnvelope.failure(
            command = tool.command.path,
            environment = environment,
            code = error.exitCode.json,
            exit = error.exitCode.code,
            message = if (revoked) REVOKED_MESSAGE else error.message.orEmpty(),
            data = error.data,
            notes = notes,
            reconnect = revoked,
        )
    }

    private fun refused(tool: McpTool, refusal: TenantRefusal, notes: List<String>): JsonObject {
        val (code, reconnect) = when (refusal) {
            is TenantRefusal.UserBusy -> "busy" to false
            is TenantRefusal.ServerBusy -> "busy" to false
            is TenantRefusal.TimedOut -> "call_timeout" to false
            is TenantRefusal.NotLinked -> ExitCode.NotSignedIn.json to true
            is TenantRefusal.Revoked -> ExitCode.SessionExpired.json to true
            is TenantRefusal.RateLimited, is TenantRefusal.OverBudget -> "rate_limited" to false
        }
        val exit = ExitCode.entries.firstOrNull { it.json == code }?.code
        return ToolEnvelope.failure(tool.command.path, environment, code, exit, refusal.message.orEmpty(), null, notes, reconnect)
    }

    private companion object {
        const val TAG = "Loopky/Mcp"
        const val WHOAMI = "whoami"
        const val REVOKED_MESSAGE = "The homeserver no longer accepts this account's Loopky session: the grant was " +
            "revoked in Pubky Ring. Ask the user to reconnect the Loopky connector; retrying cannot help."
    }
}
