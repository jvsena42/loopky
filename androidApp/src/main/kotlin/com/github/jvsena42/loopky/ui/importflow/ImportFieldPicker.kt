package com.github.jvsena42.loopky.ui.importflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.data.anki.ApkgFieldMapping
import com.github.jvsena42.loopky.presentation.importflow.ApkgFields
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/** Which two Anki fields became the card, and an invitation to pick differently. */
@Composable
internal fun FieldsChip(fields: ApkgFields, onClick: () -> Unit) {
    val colors = LoopkyTheme.colors
    Text(
        text = if (fields.canChoose) {
            stringResource(R.string.bulk_fields_changeable, fields.frontName, fields.backName)
        } else {
            stringResource(R.string.bulk_fields, fields.frontName, fields.backName)
        },
        fontSize = 13.sp,
        fontWeight = FontWeight.W600,
        color = if (fields.canChoose) colors.accentPrimary else colors.foregroundSecondary,
        modifier = Modifier
            .testTag("bulk_fields_chip")
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = fields.canChoose, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Pick which two Anki fields become the card.
 *
 * Front first, then back, because that is the order the answer matters in and it keeps the sheet to
 * one list rather than two side by side. Each row shows the field's name and what the deck actually
 * has in it — a name like `Ranking` or `Picture` is only half the story, and the sample is what
 * makes an id column obvious at a glance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FieldMappingSheet(
    fields: ApkgFields,
    onPick: (ApkgFieldMapping) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    var front by remember(fields) { mutableStateOf<Int?>(null) }
    val choosingFront = front == null

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surfaceSecondary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .semantics { testTagsAsResourceId = true },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Two questions in a row, so the sheet says which one this is; without it, picking
            // the front looked like the whole job and the second question came as a surprise.
            Text(
                text = stringResource(R.string.bulk_fields_sheet_step, if (choosingFront) 1 else 2),
                color = colors.foregroundMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 0.8.sp,
            )
            Text(
                text = stringResource(
                    if (choosingFront) R.string.bulk_fields_sheet_front else R.string.bulk_fields_sheet_back,
                ),
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            fields.names.forEachIndexed { ord, name ->
                // The front cannot also be the back; hiding it beats offering a choice that makes
                // a card with the same text twice.
                if (!choosingFront && ord == front) return@forEachIndexed
                val selected = if (choosingFront) {
                    ord == fields.mapping.frontOrd
                } else {
                    ord == fields.mapping.backOrd
                }
                FieldOption(
                    name = name.ifBlank { stringResource(R.string.bulk_fields_unnamed, ord + 1) },
                    sample = fields.sampleAt(ord),
                    selected = selected,
                    onClick = {
                        val chosen = front
                        if (chosen == null) front = ord else onPick(ApkgFieldMapping(chosen, ord))
                    },
                )
            }
            Text(
                text = stringResource(R.string.bulk_fields_sheet_hint),
                color = colors.foregroundSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
            // A way out that is not a swipe: the sheet asks two questions and had no control
            // saying you could leave the mapping as it was.
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.bulk_fields_sheet_cancel),
                    color = colors.accentPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W600,
                )
            }
        }
    }
}

/**
 * One field to choose from: its name, and what it actually holds.
 *
 * The sample is the whole point. Anki names fields "Field 3" — or nothing — often enough that a
 * list of names alone made this a guess, and getting it wrong builds every card in the deck
 * backwards.
 */
@Composable
private fun FieldOption(name: String, sample: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("bulk_field_option")
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = name,
            color = if (selected) colors.accentPrimary else colors.foregroundPrimary,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        sample?.let {
            Text(
                text = it,
                color = colors.foregroundMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
