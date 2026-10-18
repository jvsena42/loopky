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
            "https://134744072/picture.png",
            "https://[2606:4700:4700::1111]/picture.png",
            "https://[64:ff9b::808:808]/picture.png",
            "https://[2002:808:808::1]/picture.png",
            "https://[2001:db8:1::1]/picture.png",
            "https://example.com./x.png",
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

    @Test
    fun `alternate spellings of a local address are refused`() {
        listOf(
            "https://2130706433/c.jpg",
            "https://3232235786/c.jpg",
            "https://0x7f000001/c.jpg",
            "https://0177.0.0.1/c.jpg",
            "https://0x7f.1/c.jpg",
            "https://localhost./c.jpg",
            "https://[0:0:0:0:0:0:0:1]/c.jpg",
            "https://[::ffff:127.0.0.1]/c.jpg",
            "https://[::127.0.0.1]/c.jpg",
            "https://[fe80::1]/c.jpg",
            "https://[64:ff9b::a00:1]/c.jpg",
            "https://[64:ff9b:1::808:808]/c.jpg",
            "https://[2002:7f00:1::]/c.jpg",
            "https://[2002:a00:1::]/c.jpg",
            "https://[2001:0:4136:e378:8000:63bf:3fff:fdd2]/c.jpg",
            "https://4294967296/c.jpg",
        ).forEach { assertNotNull(UrlGuard.syntacticRefusal(it), it) }
    }
}
