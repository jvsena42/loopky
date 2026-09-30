package com.github.jvsena42.loopky.util

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale

internal actual fun deviceRegionCode(): String? = NSLocale.currentLocale.countryCode
