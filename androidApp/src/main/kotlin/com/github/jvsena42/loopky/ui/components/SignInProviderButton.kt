package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * One way into an account, styled as a "continue with…" row.
 *
 * **Why a single component with a variant rather than two call sites.** These read as a *set* — one
 * decision with two answers — and that only works if they are the same height, the same shape, and
 * have their marks on the same vertical line. Composing them from [LoopkyPrimaryButton] and
 * [LoopkyOutlinedButton] would leave that to two independent sets of metrics that no test covers
 * and that drift the first time either is touched.
 *
 * The icon sits at the leading edge and the label is centred in what remains, which is the
 * convention every social sign-in button follows. Centring the icon *with* the label instead would
 * put the two marks in different places, because the labels are different lengths — the one thing
 * that makes a row of provider buttons look accidental.
 *
 * Native Material 3 [Button] / [OutlinedButton] underneath, with Loopky's tokens applied, per the
 * native-first rule.
 */
@Composable
fun SignInProviderButton(
    label: String,
    icon: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: SignInProviderVariant = SignInProviderVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    /** Null keeps the icon's own colours; most marks here are monochrome and take a tint. */
    contentDescription: String? = null,
) {
    val colors = LoopkyTheme.colors
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = when (variant) {
                        SignInProviderVariant.Primary -> colors.foregroundOnAccent
                        SignInProviderVariant.Secondary -> colors.accentPrimary
                    },
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(ICON_SIZE),
                )
            } else {
                Icon(
                    painter = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(ICON_SIZE),
                )
            }
            Spacer(Modifier.size(12.dp))
            // The label centres in the space left over, so a long provider name and a short one
            // still put their marks in the same place.
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(text = label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            // Balances the icon so the label is centred against the button, not against the
            // remaining space.
            Spacer(Modifier.size(ICON_SIZE + 12.dp))
        }
    }

    when (variant) {
        SignInProviderVariant.Primary -> Button(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().height(BUTTON_HEIGHT),
            enabled = enabled && !loading,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accentPrimary,
                contentColor = colors.foregroundOnAccent,
                disabledContainerColor = colors.borderSubtle,
                disabledContentColor = colors.foregroundMuted,
            ),
            contentPadding = ButtonDefaults.ContentPadding,
        ) { content() }

        SignInProviderVariant.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().height(BUTTON_HEIGHT),
            enabled = enabled && !loading,
            shape = CircleShape,
            border = BorderStroke(1.5.dp, colors.borderSubtle),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = colors.foregroundPrimary,
                disabledContentColor = colors.foregroundMuted,
            ),
            contentPadding = ButtonDefaults.ContentPadding,
        ) { content() }
    }
}

/**
 * How much a route is being recommended.
 *
 * Only one [Primary] on a screen. Pubky Ring holds the key in a separate app, which is the safer
 * arrangement and the one Loopky recommends; everything else is [Secondary].
 */
enum class SignInProviderVariant { Primary, Secondary }

/** Both variants share it, so the set has one silhouette rather than two similar ones. */
private val BUTTON_HEIGHT = 56.dp
private val ICON_SIZE = 22.dp
