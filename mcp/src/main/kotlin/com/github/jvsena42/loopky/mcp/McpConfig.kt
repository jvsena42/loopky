package com.github.jvsena42.loopky.mcp

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.mcp.security.SharedEgress
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Everything a deployment decides, read once from the environment.
 *
 * **One process serves one network.** `switchNetwork` is process-global in the FFI, so staging and
 * production are two deployments (`mcp.staging.loopky.app`, `mcp.loopky.app`) with separate data
 * directories and master keys, never an `--env` per request (#214). And unlike the CLI, an
 * unrecognised value is a refusal to start rather than a fall back to production: a server that
 * quietly lands on the wrong network writes every user's decks there.
 */
data class McpConfig(
    val environment: PubkyEnvironment,
    /** The `https://` origin Claude reaches this server at; the OAuth issuer and the token audience. */
    val publicUrl: String,
    val host: String,
    val port: Int,
    /** Holds the SQLite database. Never backed up: losing it costs every user a re-link (#486). */
    val dataDir: Path,
    /** A root-only file holding the 32-byte AES master key, Base64. Never inside [dataDir]. */
    val masterKeyFile: Path,
    /** Ring lists the grant under this id, apart from the app's `loopky.app`, so it is revocable alone. */
    val pubkyClientId: String,
    /** Exact redirect URIs a registered client may use; Claude's callback at launch (#486). */
    val allowedRedirectUris: Set<String>,
    /** Where one address is many users; the OAuth backend routes key on a credential there (#538). */
    val sharedEgress: SharedEgress = SharedEgress.parse(SharedEgress.ANTHROPIC),
) {
    val resourceUrl: String get() = "$publicUrl/mcp"

    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv): McpConfig {
            fun required(name: String) = requireNotNull(env(name)?.trim()?.takeIf { it.isNotEmpty() }) {
                "$name must be set"
            }
            val envName = required("LOOPKY_MCP_ENV")
            val environment =
                requireNotNull(PubkyEnvironment.entries.find { it.name.equals(envName, ignoreCase = true) }) {
                    "LOOPKY_MCP_ENV must be one of ${PubkyEnvironment.entries.joinToString { it.name.lowercase() }}"
                }
            val publicUrl = required("LOOPKY_MCP_PUBLIC_URL").trimEnd('/')
            require(publicUrl.startsWith("https://") || env("LOOPKY_MCP_ALLOW_HTTP") == "1") {
                "LOOPKY_MCP_PUBLIC_URL must be https://"
            }
            return McpConfig(
                environment = environment,
                publicUrl = publicUrl,
                host = env("LOOPKY_MCP_HOST") ?: "127.0.0.1",
                port = env("LOOPKY_MCP_PORT")?.toInt() ?: DEFAULT_PORT,
                dataDir = Paths.get(required("LOOPKY_MCP_DATA_DIR")),
                masterKeyFile = Paths.get(required("LOOPKY_MCP_MASTER_KEY_FILE")),
                pubkyClientId = env("LOOPKY_MCP_PUBKY_CLIENT_ID") ?: publicUrl.removePrefix("https://"),
                allowedRedirectUris = (env("LOOPKY_MCP_REDIRECT_URIS") ?: CLAUDE_CALLBACK)
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                sharedEgress = SharedEgress.parse(env("LOOPKY_MCP_SHARED_EGRESS") ?: SharedEgress.ANTHROPIC),
            )
        }

        const val DEFAULT_PORT = 8080
        const val CLAUDE_CALLBACK = "https://claude.ai/api/mcp/auth_callback"
    }
}
