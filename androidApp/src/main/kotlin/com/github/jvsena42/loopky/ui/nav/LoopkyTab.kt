package com.github.jvsena42.loopky.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.github.jvsena42.loopky.R

enum class LoopkyTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    STUDY(R.string.nav_tab_study, Icons.Rounded.LocalFireDepartment),
    DECKS(R.string.nav_tab_decks, Icons.Rounded.Layers),
    DISCOVER(R.string.nav_tab_discover, Icons.Rounded.Explore),
    PROFILE(R.string.nav_tab_profile, Icons.Rounded.Person),
}
