package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * The Loopky fox on a coloured plate — the brand mark as the app wears it.
 *
 * The fox is the 🦊 emoji rather than an image, so it is drawn by the platform's own emoji font
 * and matches everywhere the app writes it. The launcher icon and the splash window are the same
 * glyph baked to a bitmap, because neither of those can render text.
 */
@Composable
fun FoxPlate(
    size: Dp,
    shape: Shape,
    glyphSize: TextUnit,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(containerColor)
            // Decorative: every place this appears also spells out "Loopky" next to it.
            .semantics { hideFromAccessibility() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = FOX, fontSize = glyphSize)
    }
}

private const val FOX = "🦊"
