package com.github.jvsena42.loopky.mcp.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.sql.Connection
import java.sql.DriverManager

/**
 * One SQLite file, one JDBC connection, one mutex: every [transaction] is serialized, which is
 * what makes the token store's read-check-write sequences (reuse detection, one-time codes)
 * atomic without `SELECT … FOR UPDATE`. SQLite would serialize writers anyway; the mutex only
 * keeps a reader from seeing a half-finished transaction on the shared connection.
 */
class McpDatabase private constructor(private val connection: Connection) : AutoCloseable {
    private val mutex = Mutex()

    suspend fun <T> transaction(block: (Connection) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val result = runCatching { block(connection) }
            result.onSuccess { connection.commit() }.onFailure { connection.rollback() }
            result.getOrThrow()
        }
    }

    override fun close() = connection.close()

    companion object {
        fun open(path: Path): McpDatabase {
            path.parent?.let { dir ->
                Files.createDirectories(dir)
                restrict(dir, DIR_PERMISSIONS)
            }
            val connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS")
            }
            restrict(path, FILE_PERMISSIONS)
            connection.autoCommit = false
            val migrated = runCatching { Migrations.apply(connection) }
            migrated.onSuccess { connection.commit() }.onFailure { connection.rollback() }
            migrated.getOrThrow()
            return McpDatabase(connection)
        }

        fun inMemory(): McpDatabase {
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            connection.autoCommit = false
            Migrations.apply(connection)
            connection.commit()
            return McpDatabase(connection)
        }

        private fun restrict(path: Path, permissions: Set<PosixFilePermission>) {
            runCatching { Files.setPosixFilePermissions(path, permissions) }
        }

        private const val BUSY_TIMEOUT_MS = 5_000
        private val FILE_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        private val DIR_PERMISSIONS = FILE_PERMISSIONS + PosixFilePermission.OWNER_EXECUTE
    }
}
