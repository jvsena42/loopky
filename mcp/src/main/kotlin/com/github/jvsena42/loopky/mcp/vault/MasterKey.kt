package com.github.jvsena42.loopky.mcp.vault

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64

class MasterKeyException(message: String) : IllegalStateException(message)

/** The vault's AES-256 key: 32 bytes, Base64 in a root-only file outside the data directory (#486). */
class MasterKey(val bytes: ByteArray) {
    init {
        if (bytes.size != LENGTH) throw MasterKeyException("Master key must be $LENGTH bytes, got ${bytes.size}")
    }

    companion object {
        const val LENGTH = 32

        /**
         * Refuses a file anyone but its owner can read: a key the other app on the shared VPS can
         * read protects nothing, and nothing after startup would notice.
         */
        fun load(path: Path): MasterKey {
            if (!Files.isRegularFile(path)) refuse("Master key file $path is missing")
            val tooOpen = runCatching { Files.getPosixFilePermissions(path) }.getOrNull()?.intersect(GROUP_OR_OTHERS)
            if (!tooOpen.isNullOrEmpty()) {
                refuse("Master key file $path is readable by group or others; chmod 600 it")
            }
            val decoded = runCatching { Base64.getDecoder().decode(Files.readString(path).trim()) }
                .getOrElse { refuse("Master key file $path is not Base64") }
            return MasterKey(decoded)
        }

        private fun refuse(reason: String): Nothing = throw MasterKeyException(reason)

        fun generate(random: SecureRandom = SecureRandom()): MasterKey = MasterKey(ByteArray(LENGTH).also(random::nextBytes))

        fun encode(key: MasterKey): String = Base64.getEncoder().encodeToString(key.bytes)

        private val GROUP_OR_OTHERS = setOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
    }
}
