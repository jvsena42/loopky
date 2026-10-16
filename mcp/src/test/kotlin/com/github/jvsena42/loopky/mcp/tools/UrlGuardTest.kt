package com.github.jvsena42.loopky.mcp.tools

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UrlGuardTest {

    @Test
    fun `a public https address passes`() {
        listOf(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/X.jpg/500px-X.jpg",
            "https://images.unsplash.com/photo-1?w=1080&fm=jpg",
            "https://8.8.8.8/picture.png",
            "https://cdn.example:8443/x.png",
        ).forEach { assertNull(UrlGuard.syntacticRefusal(it), it) }
    }

    @Test
    fun `local, private and credentialed addresses are refused`() {
        listOf(
            "http://example.com/x.png",
            "https://localhost/x.png",
            "https://127.0.0.1/x.png",
            "https://10.1.2.3/x.png",
            "https://192.168.1.10/x.png",
            "https://172.16.0.1/x.png",
            "https://169.254.169.254/latest/meta-data",
            "https://100.64.0.1/x.png",
            "https://[::1]/x.png",
            "https://[fd00::1]/x.png",
            "https://metadata.google.internal/x",
            "https://db.internal/x.png",
            "https://user:pw@example.com/x.png",
            "not a url",
            "https:///nohost",
        ).forEach { assertNotNull(UrlGuard.syntacticRefusal(it), it) }
    }
}
