package com.github.jvsena42.loopky.ui.nav

import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

/**
 * Navigates to [route] unless it is already the current destination, guarding against
 * duplicate destinations caused by rapid taps or re-emitted navigation effects. Prefer this
 * over [NavController.navigate] for all in-app navigation. [builder] forwards `NavOptions`
 * (e.g. `popUpTo`) for the cases that need them.
 */
fun NavController.navigateTo(route: String, builder: NavOptionsBuilder.() -> Unit = {}) {
    if (currentDestination?.route == route) return
    navigate(route, builder)
}
