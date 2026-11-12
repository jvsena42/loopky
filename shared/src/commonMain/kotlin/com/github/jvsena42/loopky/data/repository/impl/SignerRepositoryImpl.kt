package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.pubky.SessionProvider
import com.github.jvsena42.loopky.data.pubky.SessionRevalidator
import com.github.jvsena42.loopky.data.pubky.sessionCallWithRetry
import com.github.jvsena42.loopky.data.repository.SignerRepository
import com.github.jvsena42.loopky.data.storage.LocalKey
import com.github.jvsena42.loopky.data.storage.LocalKeyStore
import com.github.jvsena42.loopky.domain.model.AuthorizedSession
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.domain.model.RequestedAccess
import com.github.jvsena42.loopky.domain.model.SignInLinks
import com.github.jvsena42.loopky.domain.model.SignInRefusal
import com.github.jvsena42.loopky.domain.model.SignInRefusedException
import com.github.jvsena42.loopky.domain.model.SignInRequest
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * [SignerRepository] over the FFI's signer half: `parse_auth_url` to read a request,
 * `auth` to approve it with the held key, and `list_grants`/`revoke_grant` under the root session
 * a held key signs in with.
 *
 * The link is parsed by the FFI rather than in Kotlin, because the capabilities shown must be the
 * ones the SDK will sign, and only the SDK's own parser can promise that.
 */
internal class SignerRepositoryImpl(
    private val pubky: PubkyClient,
    private val keyStore: LocalKeyStore,
    private val session: SessionProvider,
    private val revalidator: SessionRevalidator,
) : SignerRepository {

    override val canApprove: Flow<Boolean> = keyStore.custody
        .map { it is KeyCustody.Loopky && it.registered }
        .distinctUntilChanged()

    override fun inspect(text: String): Result<SignInRequest> = runCatching {
        val link = SignInLinks.extract(text) ?: refuse(SignInRefusal.NotASignInLink)
        val details = pubky.parseAuthUrl(link)
            .mapCatching { loopkyJson.parseToJsonElement(it).jsonObject }
            .getOrElse { refuse(SignInRefusal.NotASignInLink) }

        when (details.string("kind")) {
            KIND_SIGNIN_GRANT -> Unit
            KIND_SIGNIN -> refuse(SignInRefusal.OldFormat)
            KIND_SIGNUP, KIND_SIGNUP_GRANT -> refuse(SignInRefusal.CreatesAccount)
            else -> refuse(SignInRefusal.NotASignInLink)
        }
        val access = (details["capabilities"] as? JsonArray).orEmpty().map { element ->
            val capability = element.jsonObject
            RequestedAccess(
                path = capability.string("path") ?: refuse(SignInRefusal.NotASignInLink),
                canWrite = capability.string("permission").orEmpty().contains('w'),
            )
        }
        if (access.any { it.path.trimEnd('/') in WHOLE_ACCOUNT_PATHS }) refuse(SignInRefusal.WholeAccount)

        SignInRequest(link = link, clientId = details.string("client_id")?.let(::displayableClientId), access = access)
    }

    override suspend fun approve(request: SignInRequest): Result<Unit> = runSuspendCatching {
        val reread = inspect(request.link).getOrThrow()
        check(reread == request) { "The sign-in request changed after it was shown" }
        val key = heldKey()
        pubky.auth(request.link, key.secretKeyHex).getOrThrow()
        Unit
    }

    override suspend fun authorizedSessions(): Result<List<AuthorizedSession>> = runSuspendCatching {
        heldKey()
        val listing = sessionCallWithRetry(session, revalidator) { pubky.listGrants(it) }.getOrThrow()
        val ownGrant = ownGrantId()
        loopkyJson.parseToJsonElement(listing).jsonArray
            .map { it.jsonObject.toAuthorizedSession(ownGrant) }
            .sortedByDescending { it.issuedAt }
    }

    override suspend fun revoke(grantId: String): Result<Unit> = runSuspendCatching {
        heldKey()
        // Revoking this device's own grant is a sign-out that skips everything sign-out clears.
        check(grantId != ownGrantId()) { "Refusing to revoke this device's own grant" }
        sessionCallWithRetry(session, revalidator) { pubky.revokeGrant(it, grantId) }.getOrThrow()
        Unit
    }

    /**
     * The key, only when it is the signed-in account's and has an account. A key held for a
     * signup that never finished would approve sessions for an identity with no homeserver.
     */
    private suspend fun heldKey(): LocalKey {
        val key = keyStore.current()
        val signedIn = session.current()
        if (key == null || !key.registered || signedIn?.identity?.pubky != key.pubky) {
            refuse(SignInRefusal.NoKeyHere)
        }
        return key
    }

    /**
     * Read from a revalidation, since the stored session does not keep it. Null only for a cookie
     * session, which has no grant to protect. A failed read throws rather than answering null:
     * null would unmark this device's row and let [revoke] sign the device out behind sign-out.
     */
    private suspend fun ownGrantId(): String? {
        val secret = session.current()?.sessionSecret ?: refuse(SignInRefusal.NoKeyHere)
        val payload = pubky.revalidateSession(secret).getOrThrow()
        return loopkyJson.parseToJsonElement(payload).jsonObject.string("grant_id")
    }

    /**
     * The `cid` is whatever the requester chose to call itself, so control and bidi characters
     * are dropped and the length capped before it is put in a sentence.
     */
    private fun displayableClientId(raw: String): String? =
        raw.filterNot { it.isISOControl() || it in BIDI_CONTROLS }
            .trim()
            .take(MAX_CLIENT_ID_CHARS)
            .takeIf { it.isNotEmpty() }

    private fun JsonObject.toAuthorizedSession(ownGrant: String?): AuthorizedSession {
        val grantId = requireNotNull(string("grant_id")) { "Grant without an id" }
        return AuthorizedSession(
            grantId = grantId,
            clientId = string("client_id").orEmpty(),
            access = string("capabilities").orEmpty().split(',').mapNotNull(::parseCapability),
            issuedAt = this["issued_at"]?.jsonPrimitive?.longOrNull ?: 0L,
            expiresAt = this["expires_at"]?.jsonPrimitive?.longOrNull ?: 0L,
            isThisDevice = grantId == ownGrant,
        )
    }

    private fun parseCapability(raw: String): RequestedAccess? {
        val separator = raw.trim().lastIndexOf(':')
        if (separator <= 0) return null
        val trimmed = raw.trim()
        return RequestedAccess(
            path = trimmed.substring(0, separator),
            canWrite = trimmed.substring(separator + 1).contains('w'),
        )
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun refuse(reason: SignInRefusal): Nothing = throw SignInRefusedException(reason)

    private companion object {
        const val KIND_SIGNIN = "signin"
        const val KIND_SIGNIN_GRANT = "signin_grant"
        const val KIND_SIGNUP = "signup"
        const val KIND_SIGNUP_GRANT = "signup_grant"
        val WHOLE_ACCOUNT_PATHS = setOf("", "/pub")
        const val MAX_CLIENT_ID_CHARS = 64
        val BIDI_CONTROLS = setOf(
            '\u061C', '\u200E', '\u200F', '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
            '\u2066', '\u2067', '\u2068', '\u2069',
        )
    }
}
