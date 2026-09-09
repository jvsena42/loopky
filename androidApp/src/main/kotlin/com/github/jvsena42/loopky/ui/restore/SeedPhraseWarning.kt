package com.github.jvsena42.loopky.ui.restore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The Bitcoin-seed warning, shown permanently above any field that accepts a recovery phrase.
 *
 * **Not a toast and not a popover, on purpose.** The FFI accepts *any* valid BIP-39 phrase,
 * including a Bitcoin wallet's — `mnemonic.to_seed("")` then takes the first 32 bytes as an
 * ed25519 key. It will not error. It will derive a real, deterministic pubky and sign the user in.
 *
 * Two failures follow from that, and only one of them is about keys. The derivation is one-way, so
 * a leaked *pubky* secret does not expose the wallet — the exposure is **the phrase itself**, now
 * typed into a flashcards app, sitting in a text field, in the IME's learned-word cache, and
 * possibly on its way into a password manager or a file in Drive by a backup step we also offer.
 * The quieter one: a user who does this believes Loopky and their wallet share an account, and
 * every later instruction about rotating or deleting the phrase reads as advice about their money.
 *
 * **There is deliberately no detection.** A Bitcoin seed and a Pubky seed are the same twelve words
 * from the same wordlist; no discriminator exists. A heuristic would be wrong sometimes, and a
 * false negative is worse than no check at all because it reads as an all-clear.
 *
 * @param text the warning to show. Entry screens warn against putting a wallet phrase *in*;
 *   generated-phrase screens carry the inverted form — this is not a wallet and holds no funds.
 */
@Composable
fun SeedPhraseWarning(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.danger.copy(alpha = WARNING_BACKGROUND_ALPHA))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("restore_seed_warning"),
    ) {
        Text(text = "⚠️", fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            color = colors.foregroundPrimary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

private const val WARNING_BACKGROUND_ALPHA = 0.12f
