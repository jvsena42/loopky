package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.TerminalQr
import com.github.jvsena42.loopky.cli.result
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Pubky Ring's own page, which sends a phone to its store. The same one the apps open. */
const val PUBKY_RING_INSTALL_URL = "https://pubkyring.app"

@Serializable
data class RingResult(@SerialName("install_url") val installUrl: String)

/**
 * Where to get Pubky Ring, for someone who has to install it before `login` can work. The QR is
 * for a phone's **camera**, not for Ring, and goes to stderr like `login`'s; the link is the result.
 * Local: no session, no network, so it answers on a host that can reach nothing.
 */
fun ring(stderr: (String) -> Unit): CommandResult {
    stderr(TerminalQr.render(PUBKY_RING_INSTALL_URL))
    stderr("Scan this with your phone's camera to install Pubky Ring, then run `loopky login`.")
    return result(RingResult(PUBKY_RING_INSTALL_URL), "Pubky Ring: $PUBKY_RING_INSTALL_URL")
}
