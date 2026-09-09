package com.github.jvsena42.loopky.ui.util

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.github.jvsena42.loopky.BuildConfig
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import org.koin.compose.koinInject

/**
 * Blocks screenshots and screen recording while the calling composable is on screen.
 *
 * Scoped to a screen rather than set once on the Activity: the flag is a property of the window,
 * so leaving it on would make the whole app — decks, study, profiles — uncapturable, and sharing a
 * deck screenshot is a thing people legitimately do.
 *
 * Debug keeps capture working, or the android-cli journeys lose `android screen capture` on every
 * screen that calls this — but **only away from production**. A debug build can be pointed at
 * production from `local.properties` or from the Settings environment switch, and on that build
 * every phrase this protects belongs to a real account. Keying the carve-out on the build type
 * alone left exactly that combination capturable; what decides it is whether the secret on screen
 * is real.
 */
@Composable
fun SecureScreen() {
    val window = (LocalContext.current as? Activity)?.window ?: return
    // The resolved environment, not `BuildConfig.PUBKY_ENV`: the Settings switch overrides the
    // build-time default, and it is this singleton — settled once at startup, which is why the
    // switch is documented as taking effect on the next launch — that says where writes actually
    // go this session.
    val environment = koinInject<PubkyEnvironment>()
    val secure = !BuildConfig.DEBUG || environment == PubkyEnvironment.Production

    DisposableEffect(window, secure) {
        if (secure) {
            // Reference-counted, because the flag is a property of the *window* while this is
            // scoped to a composable, and Compose Navigation composes the incoming destination
            // before disposing the outgoing one. With a plain add/clear, moving between two secure
            // screens — the recovery phrase to its confirm quiz, and back — cleared the flag on
            // arrival and left the screen showing twelve words capturable. Nothing reports that;
            // it only shows up in a screen recording that should have been black.
            if (secureRequests++ == 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        onDispose {
            if (secure) {
                if (--secureRequests == 0) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}

/**
 * How many secure screens are currently composed.
 *
 * Single-threaded by construction: composition and disposal both run on the main thread.
 */
private var secureRequests = 0
