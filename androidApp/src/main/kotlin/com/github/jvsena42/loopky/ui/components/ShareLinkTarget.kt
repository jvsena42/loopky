package com.github.jvsena42.loopky.ui.components

import androidx.annotation.StringRes

/**
 * A link a share button has raised [ShareLinkSheet] for.
 *
 * [message] is what leaves the app — the named line a recipient reads — while [link] is the bare
 * address that goes into the code and onto the clipboard. Sharing the message and copying the
 * address is deliberate: a pasted link is usually about to be opened, and a pasted sentence is not.
 */
data class ShareLinkTarget(
    val title: String,
    val link: String,
    val message: String,
    @StringRes val chooserTitle: Int,
)
