package com.github.jvsena42.loopky.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A `LOOPKY_SESSION` that is not a session secret at all is **bad input**, not an expiry.
 *
 * Exit 4 has a code of its own precisely so an agent can tell a dead session from a wobbly
 * network; letting the FFI reject a typo'd variable and classifying that as `session_expired`
 * teaches it "the hour is up" for a mistake no amount of waiting or re-logging-in fixes.
 *
 * Shape only. Whether the secret is *live* is the homeserver's answer.
 */
class SessionSecretShapeTest {

    /** A pubky is z32 and carries no colon of its own, so a real secret is exactly two parts. */
    @Test
    fun `a pubkey and cookie pair passes`() {
        requireSessionSecretShape("kfezy17cq8p3wgtn5r6uu6nhtzy8xoqdpo3ut8ci8pjbhcphgapy:s3cretcookie")
    }

    @Test
    fun `a bare string is refused`() {
        val error = assertFailsWith<CliError> { requireSessionSecretShape("not-a-real-session") }
        assertEquals(ExitCode.BadInput, error.exitCode)
    }

    @Test
    fun `an empty half is refused`() {
        assertFailsWith<CliError> { requireSessionSecretShape("abc123:") }
        assertFailsWith<CliError> { requireSessionSecretShape(":cookievalue") }
    }

    /** Three parts is not a pubkey-and-cookie pair, whatever else it might be. */
    @Test
    fun `more than two parts is refused`() {
        assertFailsWith<CliError> { requireSessionSecretShape("a:b:c") }
    }

    /**
     * The shape `loopky login` actually mints since the deeplink moved to grant auth (#130).
     * Refusing this refused every deeplink session as `bad_input`.
     */
    @Test
    fun `a grant credential passes`() {
        requireSessionSecretShape(
            "pubky-grant-credential-v1:" +
                "8um71us3fyw6h8wbcxb5ar3rwusy1a6u49956ikzojg3gcwd1dty:" +
                "c2VjcmV0:header.payload.signature",
        )
    }

    /** Matched by family, so a v2 token is not refused by a check that cannot try it. */
    @Test
    fun `a future grant credential version passes`() {
        requireSessionSecretShape("pubky-grant-credential-v2:homeserver:secret:jws")
    }

    /** The prefix is not a bypass: a grant token still has to have all four parts. */
    @Test
    fun `a truncated grant credential is refused`() {
        assertFailsWith<CliError> {
            requireSessionSecretShape("pubky-grant-credential-v1:homeserver:secret")
        }
        assertFailsWith<CliError> {
            requireSessionSecretShape("pubky-grant-credential-v1:homeserver::jws")
        }
    }

    @Test
    fun `the message names the shape rather than the value`() {
        val error = assertFailsWith<CliError> { requireSessionSecretShape("hunter2") }
        val message = error.message.orEmpty()
        assertEquals(true, message.contains("<pubkey>:<cookie>"), message)
        // Never echo what was supplied: this runs on a value the user believes is a credential.
        assertEquals(false, message.contains("hunter2"), message)
    }
}
