package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.mcp.McpConfig
import com.github.jvsena42.loopky.mcp.security.RedactingLogger
import com.github.jvsena42.loopky.mcp.store.AccessTokenRow
import com.github.jvsena42.loopky.mcp.store.AuthorizationCode
import com.github.jvsena42.loopky.mcp.store.CodeConsumption
import com.github.jvsena42.loopky.mcp.store.McpStore
import com.github.jvsena42.loopky.mcp.store.RefreshTokenRow
import com.github.jvsena42.loopky.mcp.store.Rotation
import com.github.jvsena42.loopky.mcp.store.TokenFamily
import io.ktor.http.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant

class TokenGrant(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long) {
    fun toJson(): JsonObject = buildJsonObject {
        put("access_token", accessToken)
        put("token_type", "Bearer")
        put("expires_in", expiresInSeconds)
        put("refresh_token", refreshToken)
    }
}

/** The `/token` and `/revoke` logic; throws [OAuthException] for anything the client did wrong. */
class TokenService(
    private val store: McpStore,
    private val config: McpConfig,
    private val clock: Clock,
    private val lifetimes: Lifetimes,
    private val minter: TokenMinter,
    private val unlinker: Unlinker,
) {
    private val log = RedactingLogger("loopky-mcp.token")

    /** A one-time code bound to everything the authorize request said, plus the pubky Ring approved. */
    suspend fun issueCode(pending: PendingAuthorization, pubky: String): String {
        val code = minter.mint(TokenKind.Code)
        store.codes.insert(
            AuthorizationCode(
                codeHash = TokenHash.of(code),
                clientId = pending.request.clientId,
                redirectUri = pending.request.redirectUri,
                codeChallenge = pending.request.codeChallenge,
                resource = pending.request.resource,
                pubky = pubky,
                expiresAt = clock.instant().plus(lifetimes.code),
            ),
        )
        return code
    }

    suspend fun exchangeCode(form: Parameters): TokenGrant {
        val rawCode = form.required("code")
        val clientId = form.required("client_id")
        val redirectUri = form.required("redirect_uri")
        val verifier = form.required("code_verifier")
        val familyId = minter.opaque()
        val code = when (val outcome = store.codes.consume(TokenHash.of(rawCode), clock.instant(), familyId)) {
            is CodeConsumption.Consumed -> outcome.code
            is CodeConsumption.Replayed -> {
                outcome.familyId?.let { store.tokens.revokeFamily(it, clock.instant()) }
                log.warn("Authorization code replayed by client $clientId; its tokens are revoked")
                invalidGrant("Authorization code already used")
            }
            CodeConsumption.Unknown, CodeConsumption.Expired -> invalidGrant("Authorization code is invalid or expired")
        }
        if (code.clientId != clientId) invalidGrant("Code was issued to another client")
        if (code.redirectUri != redirectUri) invalidGrant("redirect_uri does not match the authorization request")
        if (!Pkce.verify(verifier, code.codeChallenge)) invalidGrant("PKCE verification failed")
        form["resource"]?.let { if (it != code.resource) invalidTarget() }
        return issue(TokenFamily(familyId, code.pubky, clientId, clock.instant()))
            ?: invalidGrant("The account is no longer linked")
    }

    suspend fun refresh(form: Parameters): TokenGrant {
        val raw = form.required("refresh_token")
        val clientId = form.required("client_id")
        form["resource"]?.let { if (it != config.resourceUrl) invalidTarget() }
        val hash = TokenHash.of(raw)
        val known = store.tokens.findRefresh(hash) ?: invalidGrant("Refresh token is invalid")
        if (known.clientId != clientId) invalidGrant("Refresh token belongs to another client")
        if (store.links.find(known.pubky) == null) {
            store.tokens.revokeFamily(known.familyId, clock.instant())
            invalidGrant("The account is no longer linked")
        }
        var grant: TokenGrant? = null
        val rotation = store.tokens.rotate(hash, clock.instant()) { previous ->
            val minted = mintPair(previous.familyId, previous.pubky, previous.clientId, clock.instant())
            grant = minted.grant
            minted.access to minted.refresh
        }
        return when (rotation) {
            is Rotation.Rotated -> requireNotNull(grant) { "rotation without a mint" }
            is Rotation.Reused -> {
                // The family goes (OAuth 2.1 §4.3.1: the server cannot tell the thief from the
                // owner), but not the link. The ordinary reuse is the owner's own retry after
                // a lost response, and revoking the Ring grant on top of the family buys
                // nothing: the owner has to sign in again either way, and that sign-in
                // replaces the grant. A link with no live family is the sweep's within the
                // hour. The plaintext of the pair the owner lost is not kept, so it cannot be
                // handed back; that would be the grace window this does not have.
                log.warn("Refresh token reuse for ${rotation.pubky}; family revoked")
                invalidGrant("Refresh token was already used")
            }
            is Rotation.Expired -> {
                unlinker.unlinkIfOrphaned(rotation.pubky)
                invalidGrant("Refresh token has expired")
            }
            Rotation.Unlinked -> invalidGrant("The account is no longer linked")
            Rotation.Unknown, Rotation.Revoked -> invalidGrant("Refresh token is invalid")
        }
    }

    /** RFC 7009: a refresh token takes its family with it; an access token goes alone. Unknown tokens are not an error. */
    suspend fun revoke(token: String) {
        val hash = TokenHash.of(token)
        val now = clock.instant()
        store.tokens.findRefresh(hash)?.let { refresh ->
            store.tokens.revokeFamily(refresh.familyId, now)
            unlinker.unlinkIfOrphaned(refresh.pubky)
            return
        }
        store.tokens.findAccess(hash)?.let { store.tokens.revokeAccess(hash, now) }
    }

    /** Null when the account was unlinked before the tokens could be stored. */
    private suspend fun issue(family: TokenFamily): TokenGrant? {
        val minted = mintPair(family.familyId, family.pubky, family.clientId, clock.instant())
        return minted.grant.takeIf { store.tokens.issue(family, minted.access, minted.refresh) }
    }

    private class Minted(val access: AccessTokenRow, val refresh: RefreshTokenRow, val grant: TokenGrant)

    private fun mintPair(familyId: String, pubky: String, clientId: String, now: Instant): Minted {
        val accessToken = minter.mint(TokenKind.Access)
        val refreshToken = minter.mint(TokenKind.Refresh)
        return Minted(
            access = AccessTokenRow(
                tokenHash = TokenHash.of(accessToken),
                familyId = familyId,
                pubky = pubky,
                clientId = clientId,
                audience = config.resourceUrl,
                expiresAt = now.plus(lifetimes.accessToken),
            ),
            refresh = RefreshTokenRow(
                tokenHash = TokenHash.of(refreshToken),
                familyId = familyId,
                pubky = pubky,
                clientId = clientId,
                expiresAt = now.plus(lifetimes.refreshToken),
            ),
            grant = TokenGrant(accessToken, refreshToken, lifetimes.accessToken.seconds),
        )
    }

    private fun Parameters.required(name: String): String =
        this[name]?.takeIf { it.isNotBlank() } ?: invalidRequest("$name is required")
}
