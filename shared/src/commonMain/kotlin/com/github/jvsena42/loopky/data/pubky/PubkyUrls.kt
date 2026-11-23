package com.github.jvsena42.loopky.data.pubky

/**
 * A listed entry as a `pubky://` URL. The FFI's typed listing may hand back paths relative to the
 * owner (`/priv/social/v1/mutes/…`) where the untyped one returns URLs; the owner comes from the
 * [listed] address.
 */
fun toPubkyUrl(listed: String, entry: String): String = when {
    entry.startsWith("pubky://") -> entry
    entry.startsWith("/") -> "pubky://" + listed.removePrefix("pubky://").substringBefore('/') + entry
    else -> entry
}
