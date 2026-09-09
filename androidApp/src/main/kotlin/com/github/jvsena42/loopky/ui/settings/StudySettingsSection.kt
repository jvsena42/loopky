package com.github.jvsena42.loopky.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.StudySettings
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

private const val MAX_DIGITS = 3

/**
 * The Studying section: today's new-card goal, and the interval each grade gives a card.
 *
 * Two things this deliberately does *not* say. The goal is never described as a limit — nothing
 * withholds cards once it is met, and copy promising otherwise would describe a feature Loopky does
 * not have. And the intervals are never described as first-review-only: they are what the card
 * gets on every grade, and the previous copy ("cards already in rotation keep their own schedule")
 * described the compounding scheduler these replaced.
 *
 * The mastery note stays, because Mastered % is measured against the longest of the three: without
 * it, a user who sets Easy to 30 days would watch their progress drop and have no way to know why.
 */
@Composable
internal fun StudySettingsSection(
    settings: StudySettings,
    enabled: Boolean,
    onGoalChange: (Int) -> Unit,
    onIntervalChange: (SrsGrade, Int) -> Unit,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceSecondary),
    ) {
        if (!enabled) {
            Text(
                text = stringResource(R.string.settings_study_unavailable),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = colors.foregroundMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            SettingsSectionDivider()
        }
        SettingsNumberRow(
            label = stringResource(R.string.settings_new_cards_goal_label),
            description = stringResource(R.string.settings_new_cards_goal_description),
            value = settings.newCardsPerDayGoal,
            range = StudySettings.GOAL_RANGE,
            enabled = enabled,
            testTag = "settings_new_cards_goal",
            onValueChange = onGoalChange,
        )
        SettingsSectionDivider()
        SettingsNumberRow(
            label = stringResource(R.string.settings_interval_hard_label),
            description = stringResource(R.string.settings_interval_description),
            value = settings.hardDays,
            range = StudySettings.INTERVAL_RANGE,
            enabled = enabled,
            testTag = "settings_interval_hard",
            onValueChange = { onIntervalChange(SrsGrade.Hard, it) },
        )
        SettingsSectionDivider()
        SettingsNumberRow(
            label = stringResource(R.string.settings_interval_good_label),
            description = null,
            value = settings.goodDays,
            range = StudySettings.INTERVAL_RANGE,
            enabled = enabled,
            testTag = "settings_interval_good",
            onValueChange = { onIntervalChange(SrsGrade.Good, it) },
        )
        SettingsSectionDivider()
        SettingsNumberRow(
            label = stringResource(R.string.settings_interval_easy_label),
            description = stringResource(R.string.settings_interval_mastery_note),
            value = settings.easyDays,
            range = StudySettings.INTERVAL_RANGE,
            enabled = enabled,
            testTag = "settings_interval_easy",
            onValueChange = { onIntervalChange(SrsGrade.Easy, it) },
        )
    }
}

/**
 * A whole-number preference.
 *
 * The draft lives in the field rather than in state, and only a value inside [range] is committed:
 * a half-typed "1" on the way to "15" must not be saved and echoed back as the setting. Emptying
 * the field is allowed while typing and simply commits nothing.
 */
@Composable
private fun SettingsNumberRow(
    label: String,
    description: String?,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    testTag: String,
    onValueChange: (Int) -> Unit,
) {
    val colors = LoopkyTheme.colors
    // Keyed on the committed value, so an external change (or a rejected write rolling back)
    // replaces the draft instead of being overwritten by it.
    var draft by remember(value) { mutableStateOf(value.toString()) }
    val parsed = draft.toIntOrNull()
    val isValid = parsed != null && parsed in range

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) colors.foregroundPrimary else colors.foregroundMuted,
            )
            description?.let {
                Text(text = it, fontSize = 12.sp, lineHeight = 16.sp, color = colors.foregroundMuted)
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { entry ->
                draft = entry.filter { it.isDigit() }.take(MAX_DIGITS)
                draft.toIntOrNull()?.takeIf { it in range }?.let(onValueChange)
            },
            enabled = enabled,
            isError = !isValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .width(88.dp)
                .testTag(testTag),
        )
    }
}

@Composable
private fun SettingsSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = LoopkyTheme.colors.borderSubtle,
    )
}
