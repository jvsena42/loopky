package com.github.jvsena42.loopky.mcp.tenant

import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.di.TenantGraph
import com.github.jvsena42.loopky.di.TenantServices
import com.github.jvsena42.loopky.domain.model.Session

/** The pool's [TenantFactory] over `:shared`'s real per-user graph. */
class TenantGraphFactory(private val services: TenantServices) : TenantFactory {
    override suspend fun open(sessionSecret: String): Tenant =
        GraphTenant(TenantGraph.forSession(services, sessionSecret).getOrThrow())
}

private class GraphTenant(private val graph: TenantGraph) : Tenant {
    override val session: Session = requireNotNull(graph.session) { "forSession returned a graph with no session" }
    override val decks: DeckRepository get() = graph.decks
    override val cards: CardRepository get() = graph.cards
    override val imports: ImportRepository get() = graph.imports
    override val media: MediaRepository get() = graph.media
    override val tags: TagRepository get() = graph.tags

    override suspend fun close() = graph.close()
}
