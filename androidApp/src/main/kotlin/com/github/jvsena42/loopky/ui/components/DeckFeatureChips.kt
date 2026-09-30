package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * What this deck can be studied *with* — Listen, Speak, Type the answer and both directions.
 *
 * The four opt-ins were settable at publish and in the editor and shown nowhere else, so the one
 * person who could not find out what a deck offered was the reader deciding whether to keep it.
 * Icons and labels are the editor's own (`DeckStudyOptions`), so the row a reader sees names the
 * switches its author actually flipped.
 *
 * Read-only: these are the author's decisions, not the reader's, and a control here would be
 * editing someone else's deck.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeckFeatureChips(
    listenEnabled: Boolean,
    speakEnabled: Boolean,
    typeEnabled: Boolean,
    reverseEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.deck_detail_study_modes_label),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = LoopkyTheme.colors.foregroundMuted,
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("deck_detail_features"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (listenEnabled) {
                FeatureChip(
                    label = stringResource(R.string.publish_listen_title),
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    testTag = "deck_feature_listen",
                )
            }
            if (speakEnabled) {
                FeatureChip(
                    label = stringResource(R.string.publish_speak_title),
                    icon = Icons.Default.Mic,
                    testTag = "deck_feature_speak",
                )
            }
            if (typeEnabled) {
                FeatureChip(
                    label = stringResource(R.string.publish_type_title),
                    icon = Icons.Default.Keyboard,
                    testTag = "deck_feature_type",
                )
            }
            if (reverseEnabled) {
                FeatureChip(
                    label = stringResource(R.string.publish_reverse_title),
                    icon = Icons.Default.SwapHoriz,
                    testTag = "deck_feature_reverse",
                )
            }
        }
    }
}

/**
 * The native Material 3 chip with Loopky's accent tokens on it, as `OwnedBadgeRow` does next door
 * — inert, so the tap does nothing.
 *
 * All four take the *primary* accent, rather than the editor's alternating pair: the secondary one
 * is what [TagChip] is painted in, and a purple pill under the tags reads as one more tag.
 */
@Composable
private fun FeatureChip(
    label: String,
    icon: ImageVector,
    testTag: String,
) {
    val colors = LoopkyTheme.colors
    AssistChip(
        onClick = {},
        label = {
            Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.W700)
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        shape = RoundedCornerShape(50),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = colors.accentPrimarySoft,
            labelColor = colors.accentPrimary,
            leadingIconContentColor = colors.accentPrimary,
        ),
        border = null,
        modifier = Modifier.testTag(testTag),
    )
}
