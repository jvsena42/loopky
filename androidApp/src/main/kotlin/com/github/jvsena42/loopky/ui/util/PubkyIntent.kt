package com.github.jvsena42.loopky.ui.util

import android.content.Intent
import com.github.jvsena42.loopky.data.pubky.PubkyLink
import com.github.jvsena42.loopky.data.pubky.PubkyLinks
import com.github.jvsena42.loopky.domain.model.SignInLinks

/**
 * The Loopky address this intent carries, if any.
 *
 * Two ways in. A tapped `https://loopky.app` App Link, or a `pubky://` link in a client that
 * linkifies the scheme, arrives as `ACTION_VIEW` with the URI as data; one that does not leaves it
 * as text the user shares into Loopky, which arrives as `ACTION_SEND` with the whole message —
 * greeting line and all — in `EXTRA_TEXT`. [PubkyLinks] pulls the address out of either.
 */
fun Intent.pubkyLink(): PubkyLink? {
    val text = when (action) {
        Intent.ACTION_VIEW -> dataString
        Intent.ACTION_SEND -> getStringExtra(Intent.EXTRA_TEXT)
        else -> null
    }
    return text?.let(PubkyLinks::parse)
}

/**
 * The `pubkyauth://` sign-in request this intent opened Loopky with, reaching it through the
 * handler alias (only enabled while a key is held). Never logged: it carries the relay secret.
 */
fun Intent.signInLink(): String? =
    dataString?.takeIf { action == Intent.ACTION_VIEW && SignInLinks.isIn(it) }
