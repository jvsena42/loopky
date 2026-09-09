package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

@Composable
fun StatsBar(
    totalCards: Int,
    dueLabel: String,
    newCards: Int,
    masteredPercent: String,
    modifier: Modifier = Modifier,
    /**
     * False for a deck you have neither published, followed nor cloned. Due and Mastered are
     * facts about *your* study of a deck, and on a stranger's deck they are necessarily zero and
     * necessarily meaningless — "442 Due" beside a Follow button promises study you cannot start.
     */
    showProgress: Boolean = true,
) {
    val colors = LoopkyTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceSecondary)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Total
        StatColumn(
            value = totalCards.toString(),
            label = stringResource(R.string.component_stats_bar_total),
            valueColor = colors.foregroundPrimary,
            mutedColor = colors.foregroundMuted,
            modifier = Modifier.weight(1f),
        )

        if (showProgress) {
            StatDivider(colors.borderSubtle)

            // Due
            StatColumn(
                value = dueLabel,
                label = stringResource(R.string.component_stats_bar_due),
                valueColor = colors.accentPrimary,
                mutedColor = colors.foregroundMuted,
                modifier = Modifier.weight(1f),
            )

            StatDivider(colors.borderSubtle)

            // New — cards never studied. Kept apart from Due because a fresh import has hundreds
            // of these and none of them is late; showing them as Due was the wall in #101 §7.
            StatColumn(
                value = newCards.toString(),
                label = stringResource(R.string.component_stats_bar_new),
                valueColor = colors.foregroundPrimary,
                mutedColor = colors.foregroundMuted,
                modifier = Modifier.weight(1f),
            )

            StatDivider(colors.borderSubtle)

            // Mastered
            StatColumn(
                value = masteredPercent,
                label = stringResource(R.string.component_stats_bar_mastered),
                valueColor = colors.srsGood,
                mutedColor = colors.foregroundMuted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatDivider(color: Color) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(32.dp)
            .background(color),
    )
}

@Composable
private fun StatColumn(
    value: String,
    label: String,
    valueColor: Color,
    mutedColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            fontSize = 22.sp,
            fontWeight = FontWeight.W800,
            color = valueColor,
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.W500,
            color = mutedColor,
        )
    }
}

@Preview
@Composable
private fun StatsBarPreview() {
    LoopkyTheme {
        Box(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(16.dp),
        ) {
            StatsBar(
                totalCards = 42,
                dueLabel = "8",
                newCards = 12,
                masteredPercent = "65%",
            )
        }
    }
}
