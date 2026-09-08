package com.github.jvsena42.loopky.ui.settings

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.AppTheme
import com.github.jvsena42.loopky.domain.model.DayNightSchedule
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import java.util.Calendar

/**
 * The theme row — an `ExposedDropdownMenuBox` for the same reason [LanguageSection] is one, and so
 * that the two settings that both offer "System default" look like the same kind of choice.
 *
 * Nothing recreates the activity here, unlike the language picker below API 33: the palette is a
 * `CompositionLocal` fed from a `StateFlow`, so the tap repaints the screen the user is standing
 * on — including this menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSection(
    selected: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

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
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = stringResource(selected.labelRes()),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .testTag("settings_theme"),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AppTheme.entries.forEach { theme ->
                    DropdownMenuItem(
                        text = { Text(stringResource(theme.labelRes())) },
                        onClick = {
                            expanded = false
                            if (theme != selected) onThemeChange(theme)
                        },
                        modifier = Modifier.testTag("settings_theme_option_${theme.name.lowercase()}"),
                    )
                }
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
