package com.github.jvsena42.loopky.mcp.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /**
     * Every string in [element] with a scratch path replaced by [INLINE_NAME]. The commands name
     * their file — `import`'s `source`, "Nothing importable in …", "… held no cards" — and the
     * path they would name is the server's directory layout, which a caller has no business
     * learning. Applied to the whole envelope rather than field by field, so a new message
     * carrying the path is scrubbed without anyone remembering to.
     */
    fun redact(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(element.map(::redact))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(redact(element.content)) else element
        JsonNull -> element
    }

    fun redact(text: String): String = if (root.toString() in text) text.replace(scratchPath, INLINE_NAME) else text

    private val scratchPath =
        Regex(Regex.escape(root.resolve(PREFIX).toString()) + """[^\\/\s"']+[\\/][0-9a-f-]+\.[a-z]+""")

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

    companion object {
        /** What a tool result calls the file it was given in place of a path. */
        const val INLINE_NAME = "inline"
        private const val POSIX = "posix"
        private const val PREFIX = "call-"
        private val OWNER_ONLY = PosixFilePermissions.fromString("rwx------")
    }
}
