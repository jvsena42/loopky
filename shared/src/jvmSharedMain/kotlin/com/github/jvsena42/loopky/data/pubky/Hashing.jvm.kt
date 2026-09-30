package com.github.jvsena42.loopky.data.pubky

import java.security.MessageDigest

internal actual fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xff
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0f])
    }
    return sb.toString()
}

private val HEX = "0123456789abcdef".toCharArray()
