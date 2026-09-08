package com.github.jvsena42.loopky.ui.util

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * A short toast for a one-shot outcome the user does not have to act on — the share-on-Pubky
 * result being the case that introduced it: the post either went out or it didn't, and neither
 * says anything about the deck it was announcing.
 */
fun Context.toast(@StringRes message: Int) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
