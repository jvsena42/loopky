package com.github.jvsena42.loopky.mcp.store

import java.nio.file.Path

/** The four tables' DAOs over one [McpDatabase]; `loopky-mcp.db` under the deployment's data directory. */
class McpStore(val db: McpDatabase) : AutoCloseable {
    val clients = ClientStore(db)
    val codes = CodeStore(db)
    val tokens = TokenStore(db)
    val links = LinkStore(db)

    override fun close() = db.close()

    companion object {
        const val FILE_NAME = "loopky-mcp.db"

        fun open(dataDir: Path): McpStore = McpStore(McpDatabase.open(dataDir.resolve(FILE_NAME)))

        fun inMemory(): McpStore = McpStore(McpDatabase.inMemory())
    }
}
