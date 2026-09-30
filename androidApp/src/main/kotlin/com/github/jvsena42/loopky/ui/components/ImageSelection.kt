package com.github.jvsena42.loopky.ui.components

/** The image a user chose: either a web URL (saved as-is) or compressed gallery bytes. */
sealed interface ImageSelection {
    data class Web(val url: String) : ImageSelection
    data class Gallery(val bytes: ByteArray, val mime: String) : ImageSelection
}
