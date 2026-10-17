package com.github.jvsena42.loopky.mcp.tools

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InlineScratchTest {

    private val root: Path = Files.createTempDirectory("scratch-test")
    private val scratch = InlineScratch(root.resolve("inline"))

    @Test
    fun `the file is private to the call and gone afterwards`() = runBlocking {
        var seen: Path? = null
        scratch.withFile(InlineContent("a\tb".toByteArray(), "tsv")) { file ->
            seen = file
            assertEquals("a\tb", Files.readString(file!!))
            assertTrue(file.fileName.toString().endsWith(".tsv"))
            if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
                val perms = Files.getPosixFilePermissions(file.parent)
                assertEquals(emptySet(), perms - setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE), "group or world access")
            }
        }
        assertTrue(Files.notExists(seen!!), "file left behind")
        assertTrue(Files.notExists(seen!!.parent), "directory left behind")
        assertEquals(emptyList(), scratch.root.listDirectoryEntries())
    }

    @Test
    fun `the file is gone even when the command throws`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            scratch.withFile(InlineContent("x".toByteArray(), "txt")) { error("the command failed") }
        }
        assertEquals(emptyList(), scratch.root.listDirectoryEntries())
    }

    @Test
    fun `no content means no file`() = runBlocking {
        scratch.withFile(null) { file -> assertEquals(null, file) }
        assertTrue(Files.notExists(scratch.root) || scratch.root.listDirectoryEntries().isEmpty())
    }

    private companion object {
        val OWNER_READ = PosixFilePermission.OWNER_READ
        val OWNER_WRITE = PosixFilePermission.OWNER_WRITE
        val OWNER_EXECUTE = PosixFilePermission.OWNER_EXECUTE
    }
}
