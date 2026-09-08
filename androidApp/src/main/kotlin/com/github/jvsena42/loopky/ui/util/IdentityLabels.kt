package com.github.jvsena42.loopky.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyIdentity

/**
 * How a pubky user is named on screen — the one place that decides, so the same person never
 * reads as two different things. The display name wins; the pubky is the fallback, never the
 * primary identifier. Ownership is drawn separately as a "You" badge,
 * so it adds to the identity instead of replacing it.
 */
@Composable
fun PubkyIdentity.label(): String = label(LocalContext.current)

/** [label] from outside composition — a share sheet builds its text before there is a UI to read. */
fun PubkyIdentity.label(context: Context): String =
    displayName?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.identity_pubky_short, pubky.take(PUBKY_AFFIX_LEN))

/** [label] for someone we may not know yet — a signed-out greeting still needs a word. */
@Composable
fun PubkyIdentity?.labelOrFallback(): String {
    val context = LocalContext.current
    return this?.label(context) ?: stringResource(R.string.identity_unknown_person)
}

/** `abc123` — enough to tell two strangers apart in a feed. */
@Composable
fun shortPubky(pubky: String): String =
    stringResource(R.string.identity_pubky_short, pubky.take(PUBKY_AFFIX_LEN))

/** `abc123…xyz789` — for profile and settings rows, where the key itself is the subject. */
@Composable
fun truncatedPubky(pubky: String): String = stringResource(
    R.string.identity_pubky_truncated,
    pubky.take(PUBKY_AFFIX_LEN),
    pubky.takeLast(PUBKY_AFFIX_LEN),
)

private const val PUBKY_AFFIX_LEN = 6
