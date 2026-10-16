package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.mcp.McpConfig
import com.github.jvsena42.loopky.mcp.RingLogin
import com.github.jvsena42.loopky.mcp.store.McpStore
import com.github.jvsena42.loopky.mcp.vault.MasterKey
import com.github.jvsena42.loopky.mcp.vault.VaultBootstrap
import com.github.jvsena42.loopky.util.runSuspendCatching
import java.nio.file.Paths

const val ADMIN_USAGE = """usage: loopky-mcp admin <command>
  revoke-all              revoke every stored grant on the homeserver and wipe every token
  rotate-key <key-file>   re-wrap every credential under the key in <key-file>, then swap the files"""

const val ADMIN_EXIT_USAGE = 2

/**
 * The mass-revocation runbook's entry point (#486): with the database and key leaked, every grant
 * is live for ~2 years unless something revokes it. Runs against the live database file while
 * the server is stopped, so [McpStore.open] is the same path the server takes.
 */
suspend fun runAdminCommand(args: List<String>, config: McpConfig, ringLogin: RingLogin, out: (String) -> Unit = ::println): Int =
    when (args.firstOrNull()) {
        "revoke-all" -> revokeAll(config, ringLogin, out)
        "rotate-key" -> args.getOrNull(1)?.let { rotateKey(config, it, out) } ?: usage(out)
        else -> usage(out)
    }

private fun usage(out: (String) -> Unit): Int {
    out(ADMIN_USAGE)
    return ADMIN_EXIT_USAGE
}

/**
 * A link whose revoke failed is kept, not deleted: deleting it would stop the next run from
 * retrying, and the attacker's copy of the row is the problem, not ours. Tokens go regardless.
 */
private suspend fun revokeAll(config: McpConfig, ringLogin: RingLogin, out: (String) -> Unit): Int {
    val key = MasterKey.load(config.masterKeyFile)
    McpStore.open(config.dataDir).use { store ->
        val vault = VaultBootstrap.open(store, key, config.environment)
        val links = store.links.listAll()
        val failed = mutableListOf<String>()
        for (link in links) {
            val revoked = runSuspendCatching { ringLogin.revoke(vault.open(link.pubky, link.credential)).getOrThrow() }
            if (revoked.isSuccess) store.links.delete(link.pubky) else failed += link.pubky
        }
        store.tokens.wipeAll()
        out("Revoked ${links.size - failed.size} of ${links.size} grants; every token is wiped.")
        failed.forEach { out("  still linked (revoke failed, rerun to retry): $it") }
        return if (failed.isEmpty()) 0 else 1
    }
}

private suspend fun rotateKey(config: McpConfig, newKeyFile: String, out: (String) -> Unit): Int {
    val current = MasterKey.load(config.masterKeyFile)
    val next = MasterKey.load(Paths.get(newKeyFile))
    McpStore.open(config.dataDir).use { store ->
        val vault = VaultBootstrap.open(store, current, config.environment)
        val count = VaultBootstrap.rotate(store, vault, next, config.environment)
        out("Re-wrapped $count credentials under key version ${vault.keyVersion + 1}.")
        out("Now replace ${config.masterKeyFile} with $newKeyFile before starting the server; it refuses the old key.")
    }
    return 0
}
