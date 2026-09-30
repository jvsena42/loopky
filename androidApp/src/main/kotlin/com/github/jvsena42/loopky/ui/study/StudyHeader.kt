package com.github.jvsena42.loopky.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The study screen's header row, minus its close button: the deck name and counter in the middle,
 * and the trailing slot that carries the reversed badge.
 *
 * Their own file because `StudySessionScreen.kt` is at detekt's per-file function ceiling, not
 * because they are shared.
 */
/** Deck name over "3 / 12". The reversed badge sits at the row's trailing edge, not here. */
@Composable
internal fun StudyHeaderTitle(
    deckTitle: String,
    position: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = deckTitle.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = 1.sp,
            color = colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.study_position_of_total, position, total),
            fontSize = 16.sp,
            fontWeight = FontWeight.W700,
            color = colors.foregroundPrimary,
        )
    }
}

/**
 * The header's trailing slot.
 *
 * Holds the close button's width even when empty, so the title between them stays centred, and
 * grows to fit the badge — which the weighted title column gives way to.
 */
@Composable
internal fun StudyHeaderTrailing(reversed: Boolean) {
    Box(
        modifier = Modifier.widthIn(min = 40.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (reversed) ReversedBadge()
    }
}

/**
 * This card is being asked backwards.
 *
 * Worth saying out loud: a deck whose two sides look alike — two words in the same script, a pair
 * of dates — gives no other sign that the question turned round, and answering the wrong direction
 * without knowing it is a card lost to a misunderstanding rather than to not knowing it.
 */
@Composable
private fun ReversedBadge() {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.accentSecondarySoft)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("study_reversed_badge"),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.SwapHoriz,
            contentDescription = null,
            tint = colors.accentSecondary,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = stringResource(R.string.study_reversed_badge),
            fontSize = 10.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = 0.5.sp,
            color = colors.accentSecondary,
        )
    }
}
