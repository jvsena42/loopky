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

    /**
     * One thread of its own, not a slot in [Dispatchers.IO]: a hung FFI call sits on an IO
     * thread until the homeserver answers, and 64 of those used to take bearer verification and
     * the whole OAuth half down with them. A limited view of IO is elastic — it is not counted
     * against IO's cap — and one thread is all a serialized connection can use.
     */
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    suspend fun <T> transaction(block: (Connection) -> T): T = mutex.withLock {
        withContext(dispatcher) {
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
            }
            restrict(path, FILE_PERMISSIONS)
            return prepared(connection)
        }

        fun inMemory(): McpDatabase = prepared(DriverManager.getConnection("jdbc:sqlite::memory:"))

        /**
         * The pragmas both kinds share, and the migrations. `foreign_keys` is per connection and
         * off by default, and the cascades the token store relies on are silently plain columns
         * without it — which the in-memory database used to be.
         */
        private fun prepared(connection: Connection): McpDatabase {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS")
            }
            connection.autoCommit = false
            val migrated = runCatching { Migrations.apply(connection) }
            migrated.onSuccess { connection.commit() }.onFailure { connection.rollback() }
            migrated.getOrThrow()
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
