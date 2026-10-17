package com.github.jvsena42.loopky.mcp.store

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Another process — the server, or an admin command — holds the data directory. */
class DataDirBusyException(dataDir: Path) : IllegalStateException(
    "Another loopky-mcp process (the server, or an admin command) holds $dataDir; stop it first.",
)

/**
 * An exclusive lock on the data directory, held for as long as the store is open. The admin
 * commands rewrite what the running server caches — `rotate-key` leaves its vault on the old
 * version, so every credential stops opening and a login approved meanwhile is sealed under a
 * key `vault_meta` no longer names — and the runbook's "while the server is stopped" was the
 * only thing enforcing that. Taken by [McpStore.open], so whichever of the two starts second
 * refuses, and so does a second server on the same directory.
 */
class DataDirLock private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        const val FILE_NAME = "loopky-mcp.lock"

        fun acquire(dataDir: Path): DataDirLock {
            Files.createDirectories(dataDir)
            val channel = FileChannel.open(dataDir.resolve(FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            // tryLock answers null for another process's lock and throws for this process's own.
            val lock = runCatching { channel.tryLock() }.getOrElse { if (it is OverlappingFileLockException) null else throw it }
            if (lock == null) {
                channel.close()
                throw DataDirBusyException(dataDir)
            }
            return DataDirLock(channel, lock)
        }
    }
}
