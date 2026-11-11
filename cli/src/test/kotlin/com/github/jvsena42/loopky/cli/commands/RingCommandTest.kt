package com.github.jvsena42.loopky.cli.commands

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RingCommandTest {

    @Test
    fun `the result is the install link and the QR goes to stderr`() {
        val stderr = mutableListOf<String>()

        val result = ring(stderr::add)

        assertEquals("https://pubkyring.app", result.data.jsonObject["install_url"]?.jsonPrimitive?.content)
        assertTrue(result.text.contains(PUBKY_RING_INSTALL_URL), result.text)
        assertTrue(stderr.first().lines().size > 10, "no QR code on stderr")
        assertTrue(stderr.last().contains("camera"), stderr.last())
    }
}
