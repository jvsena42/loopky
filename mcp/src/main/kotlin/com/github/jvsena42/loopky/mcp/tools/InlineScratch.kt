package com.github.jvsena42.loopky.mcp.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Where inline content becomes the file a command reads.
 *
 * The command functions take a path, and a remote caller may name none, so the server writes the
 * content itself: a directory per call under [root], owner-only, a random file name inside it,
 * and both gone in `finally` whatever the command did. Nothing a caller sends ever chooses a path.
 */
class InlineScratch(val root: Path) {

    suspend fun <T> withFile(content: InlineContent?, block: suspend (Path?) -> T): T {
        if (content == null) return block(null)
        val dir = withContext(Dispatchers.IO) { createPrivateDir() }
        try {
            val file = dir.resolve("${UUID.randomUUID()}.${content.extension}")
            withContext(Dispatchers.IO) { Files.write(file, content.bytes) }
            return block(file)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
        }
    }

    private fun createPrivateDir(): Path {
        Files.createDirectories(root)
        return if (POSIX in FileSystems.getDefault().supportedFileAttributeViews()) {
            Files.createTempDirectory(root, PREFIX, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        } else {
            Files.createTempDirectory(root, PREFIX)
        }
    }

    private fun Path.deleteRecursively() {
        if (!Files.exists(this)) return
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private companion object {
        const val POSIX = "posix"
        const val PREFIX = "call-"
        val OWNER_ONLY = PosixFilePermissions.fromString("rwx------")
    }
}
