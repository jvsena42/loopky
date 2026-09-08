package com.github.jvsena42.loopky.ui.importflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.importflow.SampleCard
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

@Composable
internal fun SampleRow(card: SampleCard) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(colors.surfaceSecondary, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SampleSide(
            text = card.front,
            hasImage = card.hasFrontImage,
            fontSize = 15.sp,
            fontWeight = FontWeight.W600,
            color = colors.foregroundPrimary,
        )
        SampleSide(
            text = card.back,
            hasImage = card.hasBackImage,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            color = colors.foregroundSecondary,
        )
    }
}

/**
 * One side of a sample card: its words, clamped, and a note when it is a picture.
 *
 * Both halves matter on real Anki decks. Unclamped, a 400-character anatomy answer turned the
 * three-card sample into two screenfuls and pushed "Import N cards" four swipes away; and a
 * picture-only side drew as blank space, so a chemistry deck whose answers are all structural
 * diagrams looked like it had imported nothing at all.
 */
@Composable
private fun SampleSide(
    text: String,
    hasImage: Boolean,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    color: Color,
) {
    val colors = LoopkyTheme.colors
    if (text.isNotBlank()) {
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = color,
            maxLines = SAMPLE_SIDE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (hasImage) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = colors.accentPrimary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.bulk_sample_picture),
                fontSize = 12.sp,
                fontWeight = FontWeight.W600,
                color = colors.accentPrimary,
            )
        }
    }
}

/** Enough to recognise the card, not enough to make three of them a screenful. */
private const val SAMPLE_SIDE_MAX_LINES = 3
