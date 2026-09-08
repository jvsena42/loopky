package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * Loopky primary action button: native Material 3 [Button] tinted with `accent-primary` and shaped
 * as a pill. Per the native-first rule we apply brand tokens to the native component rather than
 * rebuilding the chrome from primitives.
 */
@Composable
fun LoopkyPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    val colors = LoopkyTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled && !loading,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accentPrimary,
            contentColor = colors.foregroundOnAccent,
            // A 0.6-alpha accent pill with crisp white text reads as a live CTA: on device the
            // disabled Publish button looked tappable and swallowed taps silently.
            disabledContainerColor = colors.borderSubtle,
            disabledContentColor = colors.foregroundMuted,
        ),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = colors.foregroundOnAccent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.size(10.dp))
        } else if (leadingIcon != null) {
            leadingIcon()
            Spacer(modifier = Modifier.size(10.dp))
        }
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
        )
    }
}

@Preview
@Composable
private fun LoopkyPrimaryButtonPreview() {
    LoopkyTheme {
        Column(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(16.dp),
        ) {
            LoopkyPrimaryButton(
                label = "Continue",
                onClick = {},
            )
            Spacer(modifier = Modifier.size(12.dp))
            LoopkyPrimaryButton(
                label = "Add deck",
                onClick = {},
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = LoopkyTheme.colors.foregroundOnAccent,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
            Spacer(modifier = Modifier.size(12.dp))
            LoopkyPrimaryButton(
                label = "Publishing",
                onClick = {},
                loading = true,
            )
        }
    }
}
