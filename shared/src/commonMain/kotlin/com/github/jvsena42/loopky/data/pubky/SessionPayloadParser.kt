package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses the session JSON payload returned by `pubky-core-ffi-fork` into a [Session].
 *
 * Payload shape (from `utils::session_to_json_with_cookie_secret`):
 * ```json
 * { "pubky": "...", "capabilities": ["/pub/loopky/:rw"], "session_secret": "..." }
 * ```
 *
 * Extra/aliased field names are tolerated so a future FFI bump continues to work. `grant_secret`
 * is no longer one of the dormant ones: since #130 the Ring deeplink asks for the grant flow (see
 * [PubkyClient.startAuthFlow]), which names the field that, so this is the alias every sign-in
 * now arrives on. `session_secret` stays first because [PubkyClient.signIn]/[PubkyClient.signUp]
 * still take the cookie flow. The two are interchangeable downstream — the FFI's `restore_session`
 * sniffs which kind of token it was handed.
 */
internal fun parseSessionPayload(payload: String, json: Json): Session {
    val obj: JsonObject = json.parseToJsonElement(payload).jsonObject

    val pubkey = obj.stringField("pubky", "public_key", "publicKey")
        ?: error("session payload missing 'pubky'")
    val secret = obj.stringField("session_secret", "sessionSecret", "grant_secret", "grantSecret", "secret")
        ?: error("session payload missing 'session_secret'")
    val homeserver = obj.stringField("homeserver", "home_server").orEmpty()
    val caps = obj["capabilities"]
        ?.let { runCatching { it.jsonArray }.getOrNull() }
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.map(::Capability)
        ?: emptyList()

    return Session(
        identity = PubkyIdentity(
            pubky = pubkey,
            displayName = null,
            avatarUrl = null,
            bio = null,
        ),
        sessionSecret = secret,
        capabilities = caps,
        homeserver = homeserver,
    )
}

private fun JsonObject.stringField(vararg names: String): String? {
    for (name in names) {
        val v = this[name]?.jsonPrimitive?.contentOrNull
        if (!v.isNullOrEmpty()) return v
    }
    return null
}
