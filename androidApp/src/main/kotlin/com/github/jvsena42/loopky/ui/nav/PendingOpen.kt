package com.github.jvsena42.loopky.ui.nav

import com.github.jvsena42.loopky.data.pubky.PubkyLink
import com.github.jvsena42.loopky.ui.importflow.IncomingFile

/**
 * What Loopky was opened *with*, held until the nav host is past onboarding and can act on it.
 *
 * State rather than a one-shot channel because a cold start delivers this before there is
 * anything on screen to receive it, and a sealed pair rather than two flows because the two are
 * mutually exclusive by construction — an intent carries a link or a file, never both.
 */
internal sealed interface PendingOpen {
    data class Link(val link: PubkyLink) : PendingOpen

    data class File(val state: IncomingFile) : PendingOpen
}
