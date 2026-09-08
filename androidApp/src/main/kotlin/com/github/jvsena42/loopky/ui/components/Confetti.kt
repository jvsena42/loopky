package com.github.jvsena42.loopky.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private const val PIECE_COUNT = 46
private const val FALL_MILLIS = 2600
private const val PIECE_WIDTH_PX = 16f
private const val PIECE_HEIGHT_PX = 26f

/** How far above the top a piece starts, as a fraction of height — they fall *into* the frame. */
private const val START_ABOVE = 0.35f
private const val TRAVEL = 1.6f
private const val SWAY_AMPLITUDE = 0.06f
private const val SWAY_CYCLES = 3f
private const val SPIN_TURNS = 4f
private const val FADE_STARTS_AT = 0.75f

/** Where a static (reduce-motion) burst sits, and how far the pieces scatter around it. */
private const val STATIC_POSITION = 0.25f
private const val STATIC_SPREAD = 0.9f

/**
 * A single burst of falling confetti, drawn on a [Canvas] rather than pulled in as a dependency —
 * it is forty-odd rotating rectangles, and a library for that would cost more than it saves.
 *
 * Runs **once** and stops. A loop would keep a frame callback alive behind whatever the user does
 * next, and a celebration that never settles stops reading as a celebration.
 *
 * Honours the system's "remove animations" setting: with [reduceMotion] the pieces are drawn once,
 * spread across the frame, and never move. Note that this cannot be the *end* of the fall — the
 * pieces finish below the bottom edge and fully faded, so freezing there would show a blank screen
 * to exactly the users who cannot be shown the animation.
 */
@Composable
fun Confetti(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    // Seeded once per composition, so a recomposition (dismissing a banner, a state refresh) does
    // not reshuffle the burst mid-flight.
    val pieces = remember(colors) {
        val random = Random(seed = PIECE_COUNT)
        List(PIECE_COUNT) {
            ConfettiPiece(
                xFraction = random.nextFloat(),
                delayFraction = random.nextFloat() * 0.4f,
                spin = random.nextFloat() * 2f - 1f,
                swayPhase = random.nextFloat() * 2f * PI.toFloat(),
                color = colors[random.nextInt(colors.size)],
            )
        }
    }

    // Driven from a LaunchedEffect so the first composition genuinely starts at 0. Reading a
    // flag flipped during composition would have the transition begin already at its target,
    // which draws every piece at the end of its fall — below the screen and fully faded.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) {
            progress.animateTo(1f, animationSpec = tween(durationMillis = FALL_MILLIS, easing = LinearEasing))
        }
    }

    Canvas(modifier = modifier) {
        pieces.forEach { piece ->
            val local = if (reduceMotion) {
                // Mid-fall, where the pieces are actually on screen and at full opacity.
                STATIC_POSITION + piece.delayFraction * STATIC_SPREAD
            } else {
                ((progress.value - piece.delayFraction) / (1f - piece.delayFraction)).coerceIn(0f, 1f)
            }
            if (local <= 0f) return@forEach

            val sway = sin(piece.swayPhase + local * SWAY_CYCLES * 2f * PI.toFloat()) * SWAY_AMPLITUDE
            val x = (piece.xFraction + sway) * size.width
            val y = (-START_ABOVE + local * TRAVEL) * size.height
            val alpha = when {
                reduceMotion -> 1f
                local < FADE_STARTS_AT -> 1f
                else -> 1f - (local - FADE_STARTS_AT) / (1f - FADE_STARTS_AT)
            }

            rotate(degrees = piece.spin * SPIN_TURNS * 360f * local, pivot = Offset(x, y)) {
                drawRect(
                    color = piece.color,
                    topLeft = Offset(x - PIECE_WIDTH_PX / 2f, y - PIECE_HEIGHT_PX / 2f),
                    size = Size(PIECE_WIDTH_PX, PIECE_HEIGHT_PX),
                    alpha = alpha.coerceIn(0f, 1f),
                )
            }
        }
    }
}

private data class ConfettiPiece(
    val xFraction: Float,
    /** Staggers the burst so the pieces do not fall as one sheet. */
    val delayFraction: Float,
    val spin: Float,
    val swayPhase: Float,
    val color: Color,
)
