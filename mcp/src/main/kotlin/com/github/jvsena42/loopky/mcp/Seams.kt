package com.github.jvsena42.loopky.mcp

/**
 * The three seams between the OAuth half of the server (`auth/`, `vault/`) and the MCP half
 * (`tools/`, `tenant/`). Each half is tested against fakes of the other through these, so neither
 * reaches into the other's storage.
 */

/** Who a verified access token speaks for. The Pubky credential itself never appears here. */
data class Principal(val pubky: String, val clientId: String)

/** Turns an `Authorization: Bearer` value into a [Principal], or null for anything not live. */
fun interface AccessTokenVerifier {
    suspend fun verify(accessToken: String): Principal?
}

/**
 * The decrypted session secret for a linked user, which is a long-lived grant credential (#486).
 *
 * [revoked] is how the MCP half reports that the homeserver refused the credential outright (Ring
 * revoked the grant): the link is dead, and the user has to reconnect — never a retry.
 */
interface SessionSecrets {
    suspend fun sessionSecretFor(pubky: String): String?
    suspend fun revoked(pubky: String)
}

/**
 * The Pubky Ring grant flow, one handle per pending login so concurrent sign-ins never share a
 * slot (#214). Implemented over the FFI's handle-based grant functions.
 */
interface RingLogin {
    /** Starts a flow for `/pub/loopky/:rw` only, under the server's own client id. */
    suspend fun start(): PendingLogin
    suspend fun poll(flowId: String): LoginPoll
    suspend fun cancel(flowId: String)

    /** Revokes the grant on the homeserver; works with an expired bearer (#486, unlink). */
    suspend fun revoke(sessionSecret: String): Result<Unit>
}

data class PendingLogin(val flowId: String, val authorizationUrl: String)

sealed interface LoginPoll {
    data object Pending : LoginPoll
    data class Approved(val pubky: String, val sessionSecret: String) : LoginPoll
    data class Failed(val reason: String) : LoginPoll
}

/** The only capability the server ever asks Ring for — the CLI's scope, so it can never post or follow. */
const val LOOPKY_CAPABILITIES = "/pub/loopky/:rw"
