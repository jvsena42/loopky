package com.github.jvsena42.loopky.mcp

import com.github.jvsena42.loopky.data.pubky.UniffiPubkyClient
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** [RingLogin] over the FFI's id-addressed grant flows, which is what lets two people sign in at once. */
class FfiRingLogin(private val pubky: UniffiPubkyClient) : RingLogin {

    override suspend fun start(): PendingLogin {
        val started = Json.parseToJsonElement(pubky.startGrantAuthFlowHandle(LOOPKY_CAPABILITIES).getOrThrow()).jsonObject
        return PendingLogin(flowId = started.text("flow_id"), authorizationUrl = started.text("authorization_url"))
    }

    override suspend fun poll(flowId: String): LoginPoll {
        // An FFI error is thrown, which the host reads as "ask again" (a relay blip must not fail a
        // sign-in the user already approved), except a flow the FFI no longer has: that one is over.
        val payload = pubky.pollGrantAuthFlowHandle(flowId).getOrElse { error ->
            if (error.message.orEmpty().startsWith(NO_SUCH_FLOW)) return LoginPoll.Expired
            throw error
        }
        val answer = Json.parseToJsonElement(payload).jsonObject
        when (answer.text("status")) {
            "approved" -> Unit
            "expired" -> return LoginPoll.Expired
            else -> return LoginPoll.Pending
        }
        val session = answer["session"]!!.jsonObject
        // Ring can only narrow what was asked for; anything but exactly the one scope is refused
        // rather than stored, so the server can never end up holding more than `/pub/loopky/`.
        val granted = (session["capabilities"] as? JsonArray)?.map { it.jsonPrimitive.content }
            ?: listOfNotNull((session["capabilities"] as? JsonPrimitive)?.content)
        if (granted != listOf(LOOPKY_CAPABILITIES)) {
            pubky.revokeOwnGrant(session.text("grant_secret"))
            return LoginPoll.Failed("Pubky Ring granted $granted; Loopky for Claude needs exactly $LOOPKY_CAPABILITIES")
        }
        return LoginPoll.Approved(pubky = session.text("pubky"), sessionSecret = session.text("grant_secret"))
    }

    override suspend fun cancel(flowId: String) {
        pubky.cancelGrantAuthFlowHandle(flowId)
    }

    override suspend fun revoke(sessionSecret: String): Result<Unit> = runSuspendCatching {
        pubky.revokeOwnGrant(sessionSecret).onFailure { error ->
            // Already revoked (in Ring, or by an earlier unlink) is the outcome unlinking wants.
            if (!error.message.orEmpty().startsWith(SESSION_REJECTED)) throw error
        }
        Unit
    }

    private fun JsonObject.text(name: String): String =
        requireNotNull(this[name]?.jsonPrimitive?.content) { "FFI answer is missing '$name'" }

    private companion object {
        const val SESSION_REJECTED = "Session rejected"
        const val NO_SUCH_FLOW = "No auth flow with id"
    }
}

/**
 * Sizes the FFI's process-wide state for a server: the session cache to the tenant pool, and the
 * pending-flow cap and lifetime to the OAuth half's own (`PendingLoginCaps`, `Lifetimes`), so the
 * FFI is never the limit a sign-in hits first.
 */
fun UniffiPubkyClient.configureForServer(sessions: Int, pendingFlows: Int, flowSeconds: Int) {
    configureSessionCache(sessions).getOrThrow()
    configureGrantAuthFlows(pendingFlows, flowSeconds).getOrThrow()
}
