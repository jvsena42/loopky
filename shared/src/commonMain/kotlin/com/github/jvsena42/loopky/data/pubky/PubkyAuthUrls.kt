package com.github.jvsena42.loopky.data.pubky

/**
 * Turns the sign-in deeplink the FFI mints into the **signup** form Pubky Ring understands.
 *
 * Loopky cannot ask the FFI for a signup URL: `start_grant_auth_flow` hardcodes
 * `AuthFlowKind::signin()` (`pubky-core-ffi-fork/src/lib.rs`), and the fork exposes no way to
 * override it. The SDK does support the signup flow — it simply is not reachable through the
 * binding. Rewriting the URL it already returned is how Pubky App works around the same wall
 * (`HomeserverService.generateSignupAuthUrl`), and the two forms differ only by the intent host
 * and two extra params:
 *
 * ```
 * signin_grant:  pubkyauth://signin_grant?caps={caps}&relay={relay}&secret={secret}&cid={id}&cpk={pk}
 * signup_grant:  pubkyauth://signup_grant?…same…&hs={homeserver}&st={token}
 * ```
 *
 * `cid`/`cpk` are grant parameters and live in the query, so replacing the intent host carries
 * them over untouched — which is the whole reason this stayed a one-word change when the deeplink
 * moved from cookie to grant (#130). `SignupGrantParams` requires both, plus `hs`; `st` is
 * optional there.
 *
 * The relay channel and client secret are the same either way, so the handle returned by
 * `startAuthFlow` still collects the approval — Ring just mints a key and redeems the token
 * before authorising back.
 */

private const val SCHEME = "pubkyauth://"
private const val SIGNUP_INTENT = "signup_grant"

/**
 * @param homeserverPubky z-base32 key of the homeserver to sign up on — the one the signup token
 *   was issued for, never a configured default, or the token is spent against the wrong server.
 * @param signupToken the single-use code from the approval step.
 *
 * Both are appended verbatim rather than percent-encoded: Ring reads `st` straight out of the
 * query without decoding (`parseSignupParams`), so encoding it would hand Ring a token that no
 * longer matches the one the homeserver issued. Neither value's charset — z-base32 and Crockford
 * base32 with hyphens — contains anything that needs escaping.
 *
 * @throws IllegalArgumentException if [this] is not a `pubkyauth://` URL with a query string.
 *   Deliberately loud: a half-built URL would send the user to Ring with a token that cannot be
 *   redeemed, and the token is single-use.
 *
 * Reached only from `IdentityRepository.beginSignUp`, which itself has no production caller any
 * more — see the note there before wiring one up.
 */
internal fun String.asSignupUrl(homeserverPubky: String, signupToken: String): String {
    require(startsWith(SCHEME)) { "Not a pubkyauth URL" }
    val queryStart = indexOf('?')
    require(queryStart != -1) { "Auth URL has no query string" }

    val query = substring(queryStart + 1)
    require(query.isNotEmpty()) { "Auth URL has an empty query string" }
    require(homeserverPubky.isNotBlank()) { "Signup needs a homeserver" }
    require(signupToken.isNotBlank()) { "Signup needs a token" }

    // The intent lives in the *host* position, so everything between the scheme and the query is
    // replaced wholesale. That also normalises the older empty-authority form (`pubkyauth:///?…`)
    // which earlier SDK versions emitted and which Ring still accepts.
    return "$SCHEME$SIGNUP_INTENT?$query&hs=$homeserverPubky&st=$signupToken"
}
