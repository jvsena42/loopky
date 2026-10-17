package com.github.jvsena42.loopky.mcp.tenant

import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.domain.model.Session

/**
 * One linked user's repository graph: the repositories the command functions take, over a
 * session adopted from that user's secret and nobody else's.
 *
 * The repositories are single-tenant by design — each keeps a per-session cache and authors its
 * writes from one `SessionProvider` — so a server has to hold one graph per user and never let a
 * request reach another's (#214). `:shared`'s `TenantGraph` is the real implementation; this is
 * the contract [TenantPool] manages it through, so the pool is testable with a fake.
 */
interface Tenant {
    /** The session the graph was opened with; what `deck create` and `import` author as. */
    val session: Session
    val decks: DeckRepository
    val cards: CardRepository
    val imports: ImportRepository
    val media: MediaRepository
    val tags: TagRepository

    /** Releases the graph. Called once, after the last call on it has returned. */
    suspend fun close()
}

/**
 * Opens a [Tenant] from a decrypted session secret, adopting and validating the session on the
 * way — a secret the homeserver refuses fails here, before any command runs.
 */
fun interface TenantFactory {
    suspend fun open(sessionSecret: String): Tenant
}
