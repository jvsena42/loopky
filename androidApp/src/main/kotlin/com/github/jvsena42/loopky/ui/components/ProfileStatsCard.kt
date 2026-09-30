package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The rounded stats strip both profile screens carry. Takes however many stats the screen has —
 * the signed-in user gets three (decks / cards / due), someone else gets the two that can be
 * derived from their published decks, since "due" is a fact about *your* review queue.
 */
@Composable
fun ProfileStatsCard(
    stats: List<ProfileStat>,
    modifier: Modifier = Modifier,
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
        stats.forEach { stat ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (stat.onClick != null) {
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = stat.onClick)
                        } else {
                            Modifier
                        },
                    )
                    .then(stat.testTag?.let { Modifier.testTag(it) } ?: Modifier)
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stat.value,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.W800,
                    color = stat.valueColor,
                )
                Text(
                    text = stat.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W500,
                    color = colors.foregroundMuted,
                )
            }
        }
    }
}

/** One column of [ProfileStatsCard]. [valueColor] is what distinguishes the counts at a glance. */
@Immutable
data class ProfileStat(
    val value: String,
    val label: String,
    val valueColor: Color,
    /** Set where the count leads somewhere — the column becomes the tap target. */
    val onClick: (() -> Unit)? = null,
    /** Only where a journey test has to find this column; the card itself has no id. */
    val testTag: String? = null,
)

@Preview
@Composable
private fun ProfileStatsCardPreview() {
    LoopkyTheme {
        Column(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileStatsCard(
                stats = listOf(
                    ProfileStat("8", "Decks", LoopkyTheme.colors.foregroundPrimary),
                    ProfileStat("240", "Cards", LoopkyTheme.colors.accentPrimary),
                    ProfileStat("12", "Due", LoopkyTheme.colors.srsGood),
                ),
            )
            ProfileStatsCard(
                stats = listOf(
                    ProfileStat("3", "Decks", LoopkyTheme.colors.foregroundPrimary),
                    ProfileStat("58", "Cards", LoopkyTheme.colors.accentPrimary),
                ),
            )
        }
    }
}
