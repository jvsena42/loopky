package com.github.jvsena42.loopky.mcp.store

import java.nio.file.Path

/** The four tables' DAOs over one [McpDatabase]; `loopky-mcp.db` under the deployment's data directory. */
class McpStore(val db: McpDatabase, private val lock: DataDirLock? = null) : AutoCloseable {
    val clients = ClientStore(db)
    val codes = CodeStore(db)
    val tokens = TokenStore(db)
    val links = LinkStore(db)

    override fun close() {
        db.close()
        lock?.close()
    }

    companion object {
        const val FILE_NAME = "loopky-mcp.db"

        /** Holds the directory's [DataDirLock] until [close]; a second opener, in this process or another, gets [DataDirBusyException]. */
        fun open(dataDir: Path): McpStore {
            val lock = DataDirLock.acquire(dataDir)
            val db = runCatching { McpDatabase.open(dataDir.resolve(FILE_NAME)) }.getOrElse {
                lock.close()
                throw it
            }
            return McpStore(db, lock)
        }

        fun inMemory(): McpStore = McpStore(McpDatabase.inMemory())
    }
}
