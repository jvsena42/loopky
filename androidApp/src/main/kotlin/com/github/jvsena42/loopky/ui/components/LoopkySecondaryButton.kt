package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * Loopky secondary action button: native Material 3 [FilledTonalButton] tinted with the soft accent
 * background and accent-primary content, shaped as a pill. Brand tokens applied to the native
 * component per the native-first rule.
 */
@Composable
fun LoopkySecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val colors = LoopkyTheme.colors
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.accentPrimarySoft,
            contentColor = colors.accentPrimary,
        ),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
        }
        Text(
            text = text,
            fontSize = 17.sp,
            fontWeight = FontWeight.W700,
        )
    }
}

@Preview
@Composable
private fun LoopkySecondaryButtonPreview() {
    LoopkyTheme {
        Column(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(16.dp),
        ) {
            LoopkySecondaryButton(
                text = "Skip",
                onClick = {},
            )
            Spacer(modifier = Modifier.size(12.dp))
            LoopkySecondaryButton(
                text = "Add card",
                onClick = {},
                icon = Icons.Default.Add,
            )
        }
    }
}
