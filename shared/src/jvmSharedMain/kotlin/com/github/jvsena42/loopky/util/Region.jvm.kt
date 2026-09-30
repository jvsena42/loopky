package com.github.jvsena42.loopky.util

import java.util.Locale

internal actual fun deviceRegionCode(): String? = Locale.getDefault().country.takeIf { it.isNotEmpty() }
