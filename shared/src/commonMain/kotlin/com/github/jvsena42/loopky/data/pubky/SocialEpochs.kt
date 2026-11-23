package com.github.jvsena42.loopky.data.pubky

import com.github.jvsena42.loopky.util.epochMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which [SocialLayout] each user's social records are in (#616).
 *
 * **The homeserver decides, not Nexus.** Nexus flips a user to `v1` when it handles their
 * `/pub/social/v1/profile.json` PUT, and it handles one homeserver's events in order. So once that
 * file exists, a later `pubky.app` write reaches Nexus after the flip and is ignored, even while
 * Nexus's own `epoch` still reads `v0`. Reading the file is the only answer that is never behind.
 *
 * [SocialEpoch.V1] is kept for good, since nothing moves a user back. [SocialEpoch.V0] is asked
 * again after [V0_TTL_MILLIS], because pubky.app can migrate the user from another device while
 * Loopky runs. A read that fails for any reason but a 404 throws: a write routed on a guess would
 * land where Nexus ignores it.
 */
class SocialEpochs(
    private val pubky: PubkyClient,
    private val now: () -> Long = ::epochMillis,
) {
    private val known = mutableMapOf<String, Pair<SocialEpoch, Long>>()
    private val lock = Mutex()

    suspend fun of(user: String): SocialEpoch {
        lock.withLock { known[user] }?.let { (epoch, at) ->
            if (epoch == SocialEpoch.V1 || now() - at < V0_TTL_MILLIS) return epoch
        }
        val epoch = pubky.get(SocialLayout.SocialV1.profile(user)).fold(
            onSuccess = { SocialEpoch.V1 },
            onFailure = { if (it.isNotFound()) SocialEpoch.V0 else throw it },
        )
        lock.withLock { known[user] = epoch to now() }
        return epoch
    }

    suspend fun layoutOf(user: String): SocialLayout = SocialLayout.of(of(user))

    /** For Loopky's own write of [user]'s `social/v1/profile.json`, which flips them. */
    suspend fun markV1(user: String) {
        lock.withLock { known[user] = SocialEpoch.V1 to now() }
    }

    private companion object {
        const val V0_TTL_MILLIS = 5 * 60 * 1000L
    }
}
