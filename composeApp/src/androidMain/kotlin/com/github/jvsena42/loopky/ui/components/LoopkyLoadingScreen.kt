package com.github.jvsena42.loopky.ui.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

private const val DOT_COUNT = 3
private const val DOT_SIZE_DP = 11
private const val DOT_GAP_DP = 8
private const val BOUNCE_HEIGHT_DP = 10
private const val BOUNCE_DURATION_MS = 480
private const val DOT_STAGGER_MS = 140

/**
 * Cozy full-screen loader: three bouncing accent dots with an optional caption,
 * centered on the whole viewport. Render this as a direct child of a fill-size
 * container — never inside a `verticalScroll` column, or it will collapse and
 * lose vertical centering.
 */
@Composable
fun LoopkyLoadingScreen(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val colors = LoopkyTheme.colors
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LoopkyBouncingDots()
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    color = colors.foregroundMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W600,
                )
            }
        }
    }
}

/**
 * The same loader sized to a section rather than the viewport, for a screen that keeps its own
 * chrome on while it loads — [LoopkyLoadingScreen] fills the window, so inside a `verticalScroll`
 * column it collapses.
 */
@Composable
fun LoopkyLoadingBlock(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        LoopkyBouncingDots()
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                color = colors.foregroundMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.W600,
            )
        }
    }
}

@Composable
private fun LoopkyBouncingDots() {
    val colors = LoopkyTheme.colors
    val reduceMotion = rememberReduceMotion()
    val transition = rememberInfiniteTransition(label = "dots")

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(DOT_GAP_DP.dp),
    ) {
        repeat(DOT_COUNT) { index ->
            val offset by transition.animateFloat(
                initialValue = 0f,
                targetValue = if (reduceMotion) 0f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = BOUNCE_DURATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * DOT_STAGGER_MS),
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(DOT_SIZE_DP.dp)
                    .graphicsLayer { translationY = -offset * BOUNCE_HEIGHT_DP.dp.toPx() }
                    .clip(CircleShape)
                    .background(colors.accentPrimary),
            )
        }
    }
}

/**
 * Reads the OS animation scale; returns true when animations are disabled
 * ("Remove animations" / Reduce Motion), so animated UI stays static.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

@Preview
@Composable
private fun LoopkyLoadingScreenPreview() {
    LoopkyTheme {
        Box(modifier = Modifier.background(LoopkyTheme.colors.surfacePrimary)) {
            LoopkyLoadingScreen(message = "Shuffling your decks…")
        }
    }
}
