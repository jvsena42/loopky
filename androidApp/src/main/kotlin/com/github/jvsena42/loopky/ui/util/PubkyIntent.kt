package com.github.jvsena42.loopky.ui.util

import android.content.Intent
import com.github.jvsena42.loopky.data.pubky.PubkyLink
import com.github.jvsena42.loopky.data.pubky.PubkyLinks

/**
 * The Loopky address this intent carries, if any.
 *
 * Two ways in, because a `pubky://` link travels two ways. A chat client that linkifies the
 * scheme sends `ACTION_VIEW` with the URI as data; one that does not leaves it as text the user
 * shares into Loopky, which arrives as `ACTION_SEND` with the whole message — greeting line and
 * all — in `EXTRA_TEXT`. [PubkyLinks] pulls the address out of either.
 */
fun Intent.pubkyLink(): PubkyLink? {
    val text = when (action) {
        Intent.ACTION_VIEW -> dataString
        Intent.ACTION_SEND -> getStringExtra(Intent.EXTRA_TEXT)
        else -> null
    }
    return text?.let(PubkyLinks::parse)
}
