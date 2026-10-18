package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.data.nexus.HttpError
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestRefusedTest {

    @Test
    fun `an indexer 400 is refused, not internal`() {
        // The message #532 reported, verbatim.
        val error = HttpError(
            400,
            "GET https://nexus.staging.pubky.app/v0/stream/resources?app=loopky&tags=loopky-deck" +
                "&sorting=taggers_count&limit=50&skip=0 failed with HTTP 400",
        )
        assertEquals(ExitCode.RequestRefused, ExitCode.of(error))
        assertEquals(ExitCode.RequestRefused, ExitCode.of(RuntimeException("wrapped", error)))
    }

    @Test
    fun `a transient or absent 4xx keeps its own code`() {
        assertEquals(ExitCode.Network, ExitCode.of(HttpError(429, "GET https://nexus.pubky.app/x failed with HTTP 429")))
        assertEquals(ExitCode.NotFound, ExitCode.of(HttpError(404, "GET https://nexus.pubky.app/x failed with HTTP 404")))
        assertEquals(ExitCode.ProxyRefused, ExitCode.of(HttpError(407, "GET https://nexus.pubky.app/x failed with HTTP 407")))
    }

    @Test
    fun `a WAF's 403 or 451 does not advise an update`() {
        listOf(403, 451).forEach { status ->
            val code = ExitCode.of(HttpError(status, "GET https://nexus.pubky.app/x failed with HTTP $status"))
            assertEquals(ExitCode.Internal, code, "HTTP $status")
        }
    }
}
