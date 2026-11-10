package com.github.jvsena42.loopky.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.ListenSpeed
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.domain.model.StudySettings
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import java.text.NumberFormat

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
 * The speed Listen starts each card at. A card of its own rather than a row in
 * [StudySettingsSection]: that card waits on the synced study record, and this is a device
 * preference with nothing to wait for.
 *
 * The slider holds its own position while dragged and commits on release, so a drag across the
 * range is one write rather than ten.
 */
@Composable
internal fun ListenSpeedSection(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
) {
    val colors = LoopkyTheme.colors
    var draft by remember(speed) { mutableFloatStateOf(speed) }
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 } }
    val label = stringResource(R.string.settings_listen_speed_label)
    val speedText = "${format.format(ListenSpeed.sanitized(draft))}×"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceSecondary),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.foregroundPrimary,
                )
            },
            supportingContent = {
                Text(
                    text = stringResource(R.string.settings_listen_speed_description),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colors.foregroundMuted,
                )
            },
            trailingContent = {
                Text(
                    text = speedText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.foregroundPrimary,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onSpeedChange(ListenSpeed.sanitized(draft)) },
            valueRange = ListenSpeed.MIN..ListenSpeed.MAX,
            steps = ListenSpeed.SLIDER_STEPS,
            colors = SliderDefaults.colors(
                thumbColor = colors.accentPrimary,
                activeTrackColor = colors.accentPrimary,
                activeTickColor = colors.foregroundOnAccent,
                inactiveTrackColor = colors.accentPrimarySoft,
                inactiveTickColor = colors.accentPrimary,
            ),
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                // Without these TalkBack reads the slider as a nameless percentage of its range.
                .semantics {
                    contentDescription = label
                    stateDescription = speedText
                }
                .testTag("settings_listen_speed"),
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

    ListItem(
        headlineContent = {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) colors.foregroundPrimary else colors.foregroundMuted,
            )
        },
        supportingContent = description?.let {
            {
                Text(text = it, fontSize = 12.sp, lineHeight = 16.sp, color = colors.foregroundMuted)
            }
        },
        trailingContent = {
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
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SettingsSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = LoopkyTheme.colors.borderSubtle,
    )
}
