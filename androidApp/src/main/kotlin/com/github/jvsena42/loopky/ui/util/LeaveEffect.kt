package com.github.jvsena42.loopky.ui.util

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Runs [onLeave] when the screen is genuinely being left — never for a configuration change.
 *
 * The plain `DisposableEffect { onDispose { viewModel.onLeave() } }` this replaces looked like a
 * teardown and was really a *rotation* handler as well, because Compose disposes the composition
 * on a configuration change while `koinViewModel()` deliberately retains the ViewModel across it.
 * Screens that clear their state in `onLeave` therefore woke up rotated, empty, and — where the
 * state came from `init`, which does not run twice for a retained ViewModel — with no way back.
 *
 * `isChangingConfigurations` is the one signal that separates the two, and it has to be read from
 * the Activity at dispose time rather than captured earlier.
 *
 * This is not a licence to reintroduce manual ViewModel disposal. It exists for the few screens
 * holding a secret — a recovery phrase, a passphrase mid-typing — that must not outlive the
 * screen. Everything else should let `viewModelScope` do its job.
 */
@Composable
fun LeaveEffect(onLeave: () -> Unit) {
    val activity = LocalContext.current as? Activity
    // `rememberUpdatedState` so the effect keys on nothing and still calls the latest lambda: a
    // key would re-arm the effect, and re-arming a *leave* handler fires it.
    val currentOnLeave by rememberUpdatedState(onLeave)
    DisposableEffect(Unit) {
        onDispose {
            if (activity?.isChangingConfigurations != true) currentOnLeave()
        }
    }
}
