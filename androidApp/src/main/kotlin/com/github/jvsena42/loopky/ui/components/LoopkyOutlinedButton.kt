package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The quiet half of a stacked pair — "Not now" under "Share on Pubky", "Undo" under "Done".
 *
 * A native Material 3 [OutlinedButton] rather than the hand-rolled bordered `Row`s it replaces.
 * Those set `padding(vertical = 16.dp)` where [LoopkyPrimaryButton] uses Material's 8 dp content
 * padding, so the two buttons in a pair rendered at visibly different heights. Sharing
 * [ButtonDefaults.ContentPadding] and the default 40 dp minimum is what keeps them equal, so
 * neither this nor the primary should override the height.
 *
 * [LoopkySecondaryButton] is not a substitute: it is a `FilledTonalButton`, a filled accent chip
 * with no border, which reads as a second *primary* action rather than as a way out.
 */
@Composable
fun LoopkyOutlinedButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LoopkyTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(1.5.dp, colors.borderSubtle),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.foregroundPrimary,
            disabledContentColor = colors.foregroundMuted,
        ),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}

@Preview
@Composable
private fun LoopkyOutlinedButtonPreview() {
    LoopkyTheme {
        Column(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(16.dp),
        ) {
            // The pair, so the preview fails visibly if their heights ever diverge again.
            LoopkyPrimaryButton(label = "Share on Pubky", onClick = {})
            Spacer(modifier = Modifier.size(12.dp))
            LoopkyOutlinedButton(label = "Not now", onClick = {})
        }
    }
}
