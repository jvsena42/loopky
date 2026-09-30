package com.github.jvsena42.loopky.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every failure observed in #240 carried a doubled prefix — `"Request failed: Request failed: …"`.
 * The FFI produces it; this only stops repeating it.
 */
class RepeatedPrefixTest {

    @Test
    fun `says a doubled wrap once`() {
        assertEquals(
            "Request failed: Invalid request/URI: path contains empty segment ('//')",
            CliError(
                ExitCode.Internal,
                "Request failed: Request failed: Invalid request/URI: path contains empty segment ('//')",
            ).message,
        )
    }

    @Test
    fun `leaves a message with no repeat alone`() {
        val message = "Failed to import session: Request failed: HTTP transport error: connection reset"
        assertEquals(message, CliError(ExitCode.Network, message).message)
    }

    /** Two identical words with a frame between them are two frames, not a double wrap. */
    @Test
    fun `only collapses adjacent segments`() {
        val message = "failed: middle: failed: done"
        assertEquals(message, CliError(ExitCode.Internal, message).message)
    }

    /**
     * The leading segment only, because this runs on **every** message the CLI reports and the
     * later segments are frequently the caller's own data quoted back.
     *
     * Collapsing adjacent duplicates anywhere silently rewrote the value being complained about: a
     * card whose front is `Hola: Hola` came back as `Hola`. `--json` is the verification channel,
     * which is the worst place to be lossy about the thing that failed.
     */
    @Test
    fun `never edits a repeat inside the message`() {
        val quoted = "Request failed: card front is 'Hola: Hola: adios'"
        assertEquals(quoted, CliError(ExitCode.BadInput, quoted).message)

        val doubled = "Request failed: Request failed: card front is 'Hola: Hola'"
        assertEquals(
            "Request failed: card front is 'Hola: Hola'",
            CliError(ExitCode.BadInput, doubled).message,
            "the wrap goes, the quoted value stays",
        )
    }

    @Test
    fun `leaves a message with no segments alone`() {
        assertEquals("plain", CliError(ExitCode.Internal, "plain").message)
    }
}
