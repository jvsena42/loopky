package com.github.jvsena42.loopky.data.pubky

import kotlinx.serialization.Serializable

/**
 * Body of a pubky.app mute record (`/pub/pubky.app/mutes/{mutee}`), matching `PubkyAppMute`: the
 * record's existence is the mute, the payload only timestamps it, in **microseconds** as
 * pubky-app-specs encodes it.
 */
@Serializable
internal data class MuteDto(
    val created_at: Long,
)
