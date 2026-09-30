package com.github.jvsena42.loopky.data.pubky

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The cheapest thing in the signup flow to get right and the most expensive to get wrong: a
 * malformed URL means Ring cannot redeem the token, and the token is single-use.
 */
class PubkyAuthUrlsTest {

    private val relay = "https://httprelay.pubky.app/inbox"
    private val secret = "U55XnoH6vsMCpx1pxHtt8fReVg4Brvu9C0gUBuw-Jkw"
    private val caps = "/pub/loopky/:rw,/pub/pubky.app/:rw"
    private val homeserver = "8um71us3fyw6h8wbcxb5ar3rwusy1a6u49956ikzojg3gcwd1dty"
    private val token = "ABCD-1234-EFGH"
    private val clientId = "loopky.app"
    private val clientPk = "5jsjx1o6fzu6aeeo697r3i5rx15zq41kikcye8wtwdqm4nb4tryo"

    private val signinUrl = "pubkyauth://signin?caps=$caps&relay=$relay&secret=$secret"

    @Test
    fun theIntentHostBecomesSignup() {
        // Ring reads the intent out of the host position — this is what routes the deeplink to
        // "mint a key and redeem a token" instead of "pick an existing pubky".
        val url = signinUrl.asSignupUrl(homeserver, token)

        assertTrue(url.startsWith("pubkyauth://signup?"))
    }

    @Test
    fun theRelayChannelAndSecretSurviveUntouched() {
        // The handle returned by startAuthFlow is already polling this exact channel; change
        // either value and the approval is collected by nobody.
        val url = signinUrl.asSignupUrl(homeserver, token)

        assertTrue("relay=$relay" in url)
        assertTrue("secret=$secret" in url)
        assertTrue("caps=$caps" in url)
    }

    @Test
    fun theGrantParamsSurviveTheHostRewrite() {
        // The rewrite replaces the host and nothing else, which is what lets the grant form's
        // required cid/cpk ride through untouched when the cookie pin is lifted (#321).
        val grantUrl =
            "pubkyauth://signin_grant?caps=$caps&relay=$relay&secret=$secret" +
                "&cid=$clientId&cpk=$clientPk"

        val url = grantUrl.asSignupUrl(homeserver, token)

        assertTrue("cid=$clientId" in url)
        assertTrue("cpk=$clientPk" in url)
    }

    @Test
    fun theHomeserverAndTokenAreAppendedVerbatim() {
        // Ring reads `st` without decoding it, so a percent-encoded token would no longer match
        // the one the homeserver issued.
        val url = signinUrl.asSignupUrl(homeserver, token)

        assertTrue("&hs=$homeserver" in url)
        assertTrue("&st=$token" in url)
    }

    @Test
    fun theWholeUrlIsExactlyTheSigninUrlPlusTheTwoSignupParams() {
        assertEquals(
            "pubkyauth://signup?caps=$caps&relay=$relay&secret=$secret&hs=$homeserver&st=$token",
            signinUrl.asSignupUrl(homeserver, token),
        )
    }

    @Test
    fun theOlderEmptyAuthorityFormIsNormalised() {
        // Earlier SDK versions emitted `pubkyauth:///?…`; Ring still accepts it, and the fork's
        // parser treats a missing intent as legacy, so this must not produce `signup//`.
        val legacy = "pubkyauth:///?caps=$caps&relay=$relay&secret=$secret"

        val url = legacy.asSignupUrl(homeserver, token)

        assertEquals(
            "pubkyauth://signup?caps=$caps&relay=$relay&secret=$secret&hs=$homeserver&st=$token",
            url,
        )
    }

    @Test
    fun aUrlWithNoQueryFailsRatherThanProducingAHalfBuiltLink() {
        // Sending the user to Ring with an unredeemable URL burns a single-use token.
        assertFailsWith<IllegalArgumentException> {
            "pubkyauth://signin".asSignupUrl(homeserver, token)
        }
    }

    @Test
    fun aNonPubkyAuthUrlIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            "https://example.test/?caps=x".asSignupUrl(homeserver, token)
        }
    }

    @Test
    fun aBlankHomeserverOrTokenIsRejected() {
        assertFailsWith<IllegalArgumentException> { signinUrl.asSignupUrl("", token) }
        assertFailsWith<IllegalArgumentException> { signinUrl.asSignupUrl(homeserver, "  ") }
    }

    @Test
    fun redactionStillHidesTheTokenAfterTheRewrite() {
        // The two features have to compose: this URL is logged on the way to Ring.
        val redacted = signinUrl.asSignupUrl(homeserver, token).redactAuthUrl()

        assertTrue(token !in redacted)
        assertTrue(secret !in redacted)
        assertTrue("hs=$homeserver" in redacted, "which homeserver was targeted is not secret")
    }
}
