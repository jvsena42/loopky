package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/** Labels offered as one-tap chips, matching the iOS `AddTagSheet`. */
private val SUGGESTED_TAGS = listOf("language", "beginner", "travel", "daily")

/**
 * Bottom sheet for authoring a deck tag: a free-text field, one-tap suggestions, and the deck's
 * current tags with a remove affordance.
 *
 * Shared by the publish flow and the deck editor (#83) — the editor used to hardcode the label
 * `new`, since this input only existed inside `PublishDeckScreen`. Both screens' ViewModels expose
 * the same `onAddTag`/`onRemoveTag` pair, which normalizes and validates the label, so the sheet
 * only needs [tags] plus those two callbacks — the same shape as iOS's `AddTagSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddTagSheet(
    tags: List<String>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    var tagInput by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(),
        containerColor = colors.surfaceCard,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.borderSubtle)
                )
            }
        },
    ) {
        val suggestedTags = remember(tags) { SUGGESTED_TAGS.filter { it !in tags } }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = stringResource(R.string.tag_sheet_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfacePrimary)
                    .border(1.5.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "#",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.accentSecondary,
                )
                BasicTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.foregroundPrimary,
                    ),
                    cursorBrush = SolidColor(colors.accentPrimary),
                    singleLine = true,
                    modifier = Modifier
                        .testTag("tag_sheet_input")
                        .weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (tagInput.isEmpty()) {
                                Text(
                                    stringResource(R.string.tag_sheet_input_placeholder),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.foregroundMuted,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            if (suggestedTags.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.tag_sheet_suggested_label),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = colors.foregroundMuted,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestedTags.forEach { tag ->
                            TagChip(tag = tag, onClick = { onAddTag(tag) })
                        }
                    }
                }
            }

            if (tags.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.tag_sheet_current_label),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = colors.foregroundMuted,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tags.forEach { tag ->
                            TagChip(tag = tag, onRemove = { onRemoveTag(tag) })
                        }
                    }
                }
            }

            LoopkyPrimaryButton(
                label = stringResource(R.string.tag_sheet_add_button),
                onClick = {
                    onAddTag(tagInput)
                    tagInput = ""
                },
                enabled = tagInput.isNotBlank(),
                modifier = Modifier.testTag("tag_sheet_add_button"),
                leadingIcon = {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = colors.foregroundOnAccent,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}
