package com.github.jvsena42.loopky.presentation.settings

import kotlin.concurrent.Volatile

/**
 * Hands a pasted sign-in link from the screen it was pasted into (Search) to Settings → Sessions,
 * which shows it for approval. Held in memory only, and taken once: the link carries the relay
 * secret, so it must not wait around for a second reader.
 */
class SignInInbox {
    @Volatile
    private var pending: String? = null

    fun deliver(text: String) {
        pending = text
    }

    fun take(): String? = pending.also { pending = null }
}
