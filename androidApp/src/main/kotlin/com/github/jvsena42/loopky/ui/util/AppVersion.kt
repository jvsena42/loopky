package com.github.jvsena42.loopky.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The app's `versionName`, or blank when the package manager will not say.
 *
 * Read from `PackageManager` rather than `BuildConfig` so it stays right in a module that does not
 * generate one, and so the value shown is the one the installer actually recorded.
 *
 * Shared by Settings and onboarding: the version has to be visible before sign-in, since a bug
 * report from someone who cannot get past the first screen is exactly the one where knowing the
 * build matters.
 */
@Composable
fun rememberAppVersion(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
}

/** Public policy documents, served from the repository so they need no hosting of their own. */
const val PRIVACY_POLICY_URL = "https://github.com/jvsena42/loopky/blob/main/PRIVACY.md"

const val LICENSE_URL = "https://github.com/jvsena42/loopky/blob/main/LICENSE"
