package com.github.jvsena42.loopky.ui.settings

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.AppTheme
import com.github.jvsena42.loopky.domain.model.DayNightSchedule
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import java.util.Calendar

/**
 * The theme row — four segments, the same shape iOS has had since the setting existed.
 *
 * It used to be an `ExposedDropdownMenuBox`, matching [LanguageSection] beside it. The two are not
 * the same kind of choice: a language list is open-ended and a theme is four options that fit on
 * one row, and putting four behind a menu cost two taps and hid what the alternatives even were.
 * The language picker stays a dropdown for the reason it always was.
 *
 * Nothing recreates the activity here, unlike the language picker below API 33: the palette is a
 * `CompositionLocal` fed from a `StateFlow`, so the tap repaints the screen the user is standing
 * on — including this row.
 */
@Composable
internal fun AppearanceSection(
    selected: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceSecondary)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_theme_label),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.foregroundPrimary,
        )
        Text(
            text = stringResource(
                R.string.settings_theme_description,
                hourLabel(context, DayNightSchedule.darkFromHour),
                hourLabel(context, DayNightSchedule.darkUntilHour),
            ),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = colors.foregroundMuted,
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_theme"),
        ) {
            AppTheme.entries.forEachIndexed { index, theme ->
                SegmentedButton(
                    selected = theme == selected,
                    onClick = { if (theme != selected) onThemeChange(theme) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = AppTheme.entries.size,
                    ),
                    modifier = Modifier.testTag("settings_theme_option_${theme.name.lowercase()}"),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = colors.accentPrimarySoft,
                        activeContentColor = colors.accentPrimary,
                        activeBorderColor = colors.accentPrimary,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = colors.foregroundSecondary,
                        inactiveBorderColor = colors.borderSubtle,
                    ),
                    label = {
                        // One line, ellipsised: four segments across a 360dp phone leave about
                        // 80dp each, and "Automático" is longer than that at any readable size.
                        Text(
                            text = stringResource(theme.labelRes()),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

private fun AppTheme.labelRes(): Int = when (this) {
    AppTheme.System -> R.string.settings_theme_system
    AppTheme.Scheduled -> R.string.settings_theme_auto
    AppTheme.Light -> R.string.settings_theme_light
    AppTheme.Dark -> R.string.settings_theme_dark
}

/**
 * An o'clock hour as the reader's own device writes it — "8 PM" or "20:00".
 *
 * `DateFormat.getTimeFormat` reads the system's 24-hour switch, so this follows a setting the user
 * has already made elsewhere. Interpolating the raw number gave "20:00" to someone whose phone has
 * never shown them a 24-hour clock.
 */
private fun hourLabel(context: Context, hour: Int): String {
    val time = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
    }.time
    return DateFormat.getTimeFormat(context).format(time)
}
