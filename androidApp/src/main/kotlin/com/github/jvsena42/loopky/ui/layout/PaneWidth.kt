package com.github.jvsena42.loopky.ui.layout

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How wide Loopky lets a block of content grow before it stops using the extra room.
 *
 * A phone layout stretched to a landscape tablet is the single biggest thing wrong with Loopky on
 * a big screen: a 1280dp-wide row puts the label at one edge and its control at the other, and a
 * paragraph set across the full width is past the ~75-character line the eye can track. So content
 * gets a ceiling and is centred in whatever is left, rather than being smeared across the panel.
 *
 * The three ceilings are about what the content *is*, not which screen it is on — they apply at
 * every width and simply never bite on a phone, where the window is narrower than all of them.
 */
object PaneWidth {
    /** A single column of prose, form fields or settings rows. Roughly a 70-character measure. */
    val Reading: Dp = 680.dp

    /** A focused, self-contained task: onboarding, a study card, a confirmation. */
    val Focused: Dp = 520.dp

    /** Grids and tile walls, which genuinely do get better with more room — but not unbounded. */
    val Wide: Dp = 1160.dp
}

/**
 * Caps this content's width at [max] and centres it in the space available.
 *
 * `fillMaxWidth().wrapContentWidth()` first claims the full width and then re-measures the child
 * with a zero minimum, which is what lets [widthIn] actually bind — a bare `widthIn(max = …)` on a
 * child that fills its parent does nothing, because the parent's minimum width is already the full
 * width and a minimum outranks a maximum.
 */
fun Modifier.contentPane(max: Dp = PaneWidth.Reading): Modifier = this
    .fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = max)

/**
 * How many columns a deck grid should use at the current width.
 *
 * Two columns is right on a phone and wrong on a landscape tablet, where it produces 600dp-wide
 * tiles with a cover the size of a paperback. Counting columns from the width — rather than from
 * "is this a tablet" — is also what keeps a split-screen pane honest.
 */
@Composable
@ReadOnlyComposable
fun deckGridColumns(): Int = when (windowWidthClass()) {
    WindowWidthClass.Compact -> COMPACT_DECK_COLUMNS
    WindowWidthClass.Medium -> MEDIUM_DECK_COLUMNS
    WindowWidthClass.Expanded -> EXPANDED_DECK_COLUMNS
}

private const val COMPACT_DECK_COLUMNS = 2
private const val MEDIUM_DECK_COLUMNS = 3
private const val EXPANDED_DECK_COLUMNS = 4
