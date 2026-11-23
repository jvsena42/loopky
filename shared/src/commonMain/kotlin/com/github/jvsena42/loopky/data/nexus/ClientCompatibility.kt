package com.github.jvsena42.loopky.data.nexus

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the indexer has said this build uses an API it no longer serves.
 *
 * Set by [NexusClient] on **410 Gone** or **426 Upgrade Required**, never on a 400 or 404: those
 * also mean "bad input" and "no such thing", and a release that blocked itself on either would lock
 * readers out over one malformed search. Once set it stays set for the process; the apps answer it
 * with a full-screen "update Loopky" (#616). A release in the stores cannot learn new error
 * shapes, so this is the contract every later release can be retired by.
 */
class ClientCompatibility {
    private val _outdated = MutableStateFlow(false)
    val outdated: StateFlow<Boolean> = _outdated.asStateFlow()

    fun markOutdated() {
        _outdated.value = true
    }

    companion object {
        /** The statuses that mean "this client is too old", as opposed to "this request is wrong". */
        val OUTDATED_STATUSES = setOf(410, 426)
    }
}
