package com.github.jvsena42.loopky.mcp.security

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginValidatorTest {
    private val validator = OriginValidator(setOf("https://claude.ai", "https://Localhost:8443"))

    @Test
    fun `absent Origin is a non-browser client and passes`() {
        assertTrue(validator.allows(null))
    }

    @Test
    fun `an allowed origin matches by normalized scheme, host and port`() {
        assertTrue(validator.allows("https://claude.ai"))
        assertTrue(validator.allows("HTTPS://CLAUDE.AI"))
        assertTrue(validator.allows("https://claude.ai:443"))
        assertTrue(validator.allows("https://localhost:8443"))
        assertTrue(validator.allows("https://claude.ai/"))
    }

    @Test
    fun `anything else is refused`() {
        assertFalse(validator.allows("http://claude.ai"))
        assertFalse(validator.allows("https://claude.ai.evil.example"))
        assertFalse(validator.allows("https://evil.example"))
        assertFalse(validator.allows("https://claude.ai:8443"))
        assertFalse(validator.allows("https://localhost"))
        assertFalse(validator.allows("null"))
        assertFalse(validator.allows(""))
        assertFalse(validator.allows("https://claude.ai/path"))
        assertFalse(validator.allows("https://user@claude.ai"))
    }

    @Test
    fun `the route plugin answers 403 before the handler`() = testApplication {
        application {
            routing {
                route("/mcp") {
                    install(OriginGuard) { validator = this@OriginValidatorTest.validator }
                    get { call.respondText("ok") }
                }
            }
        }
        assertEquals(HttpStatusCode.OK, client.get("/mcp").status)
        assertEquals(HttpStatusCode.OK, client.get("/mcp") { header("Origin", "https://claude.ai") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/mcp") { header("Origin", "https://evil.example") }.status)
    }
}
