package com.github.jvsena42.loopky.ui.settings

import android.app.Activity
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
import com.github.jvsena42.loopky.locale.AppLocale
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The app-language row.
 *
 * Deliberately outside [SettingsViewModel]: the language is the device's setting rather than
 * Loopky's (see [AppLocale]), so there is nothing to hold in shared state and nothing to sync. On
 * API 33+ the value on screen is read back from the framework, which means a change made in the
 * system's own Settings screen shows up here without Loopky being told about it.
 *
 * An `ExposedDropdownMenuBox` rather than a dialog of radio rows, because the list is meant to
 * grow: the menu scrolls and sizes itself, so the fifth language costs a line in
 * [AppLocale.SUPPORTED] and `locales_config.xml` and nothing here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSection(modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val selected = AppLocale.current(context)
    val systemLabel = stringResource(R.string.settings_language_system)

    fun pick(tag: String?) {
        expanded = false
        if (tag == selected) return
        AppLocale.set(context, tag)
        // The framework recreates the activity itself once it owns the preference; below 33
        // nothing does, and the screen would keep rendering the old language until something
        // else happened to recreate it.
        if (!AppLocale.isDeviceManaged) (context as? Activity)?.recreate()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceSecondary)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_language_label),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.foregroundPrimary,
        )
        Text(
            text = stringResource(
                if (AppLocale.isDeviceManaged) {
                    R.string.settings_language_description_system
                } else {
                    R.string.settings_language_description_app
                },
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
                value = selected?.let(AppLocale::displayName) ?: systemLabel,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .testTag("settings_language"),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                // "System default" first and always offered: it is the only entry that keeps
                // working when a later release adds a language the device already prefers.
                DropdownMenuItem(
                    text = { Text(systemLabel) },
                    onClick = { pick(null) },
                    modifier = Modifier.testTag("settings_language_option_system"),
                )
                AppLocale.SUPPORTED.forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(AppLocale.displayName(tag)) },
                        onClick = { pick(tag) },
                        modifier = Modifier.testTag("settings_language_option_$tag"),
                    )
                }
            }
        }
    }
}
