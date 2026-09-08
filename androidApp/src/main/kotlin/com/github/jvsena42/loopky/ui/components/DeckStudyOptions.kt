package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.SpeechLanguages
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import java.util.Locale

/**
 * The deck's study opt-ins — Listen, Speak, Type and both directions — and, when a *speech* one is
 * on, the language of each card side.
 *
 * Shared by the publish flow and the deck editor so an already-published deck can be given the
 * pair it was written without — before this, the opt-ins were set at publish and never editable.
 */
@Composable
fun DeckStudyOptions(
    listenEnabled: Boolean,
    speakEnabled: Boolean,
    typeEnabled: Boolean,
    reverseEnabled: Boolean,
    frontLang: String?,
    backLang: String?,
    availableLanguages: List<String>,
    onToggleListen: () -> Unit,
    onToggleSpeak: () -> Unit,
    onToggleType: () -> Unit,
    onToggleReverse: () -> Unit,
    onFrontLangSelected: (String) -> Unit,
    onBackLangSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier) {
        Text(
            stringResource(R.string.publish_card_options_label),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = colors.foregroundMuted,
        )
        OptionToggleRow(
            title = stringResource(R.string.publish_listen_title),
            subtitle = stringResource(R.string.publish_listen_subtitle),
            checked = listenEnabled,
            onToggle = onToggleListen,
            testTag = "publish_listen_toggle",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            iconColor = colors.accentPrimary,
            iconBackground = colors.accentPrimarySoft,
        )
        OptionToggleRow(
            title = stringResource(R.string.publish_speak_title),
            subtitle = stringResource(R.string.publish_speak_subtitle),
            checked = speakEnabled,
            onToggle = onToggleSpeak,
            testTag = "publish_speak_toggle",
            icon = Icons.Default.Mic,
            iconColor = colors.accentSecondary,
            iconBackground = colors.accentSecondarySoft,
        )
        // Above the language block, not below it: typing needs no pair, and a row that sits under
        // the pickers reads as one more thing they gate.
        OptionToggleRow(
            title = stringResource(R.string.publish_type_title),
            subtitle = stringResource(R.string.publish_type_subtitle),
            checked = typeEnabled,
            onToggle = onToggleType,
            testTag = "publish_type_toggle",
            icon = Icons.Default.Keyboard,
            iconColor = colors.accentPrimary,
            iconBackground = colors.accentPrimarySoft,
        )
        // Above the languages for the same reason Type is: asking a card backwards swaps two sides
        // and needs no declared pair, so it must not read as something the pickers gate.
        OptionToggleRow(
            title = stringResource(R.string.publish_reverse_title),
            subtitle = stringResource(R.string.publish_reverse_subtitle),
            checked = reverseEnabled,
            onToggle = onToggleReverse,
            testTag = "publish_reverse_toggle",
            icon = Icons.Default.SwapHoriz,
            iconColor = colors.accentSecondary,
            iconBackground = colors.accentSecondarySoft,
        )

        if (listenEnabled || speakEnabled) {
            Text(
                stringResource(R.string.deck_languages_hint),
                fontSize = 12.sp,
                color = colors.foregroundSecondary,
            )
            LanguagePickerRow(
                label = stringResource(R.string.deck_front_language_label),
                selected = frontLang,
                options = availableLanguages,
                onSelected = onFrontLangSelected,
                testTag = "deck_front_language",
            )
            LanguagePickerRow(
                label = stringResource(R.string.deck_back_language_label),
                selected = backLang,
                options = availableLanguages,
                onSelected = onBackLangSelected,
                testTag = "deck_back_language",
            )
        }
    }
}

/**
 * The languages to offer: what the installed TTS engine can actually voice, or
 * [SpeechLanguages.COMMON] when it reports nothing (still initializing, or no engine at all).
 * The pair is metadata other people's devices read, so it must be settable either way.
 */
fun speechLanguageOptions(engineLanguages: List<String>): List<String> =
    engineLanguages.ifEmpty { SpeechLanguages.COMMON }

/** "Spanish (Spain)" for `es-ES`, in the reader's own language — no strings to translate. */
fun languageDisplayName(tag: String): String = Locale.forLanguageTag(tag)
    .getDisplayName(Locale.getDefault())
    .replaceFirstChar { it.uppercase(Locale.getDefault()) }
    .ifBlank { tag }

@Composable
private fun LanguagePickerRow(
    label: String,
    selected: String?,
    options: List<String>,
    onSelected: (String) -> Unit,
    testTag: String,
) {
    val colors = LoopkyTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
                .background(colors.surfaceCard)
                .clickable { expanded = true }
                .testTag(testTag)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.accentPrimarySoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Translate,
                    null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, fontSize = 12.sp, color = colors.foregroundSecondary)
                Text(
                    selected?.let(::languageDisplayName)
                        ?: stringResource(R.string.deck_language_unset),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected == null) colors.foregroundMuted else colors.foregroundPrimary,
                )
            }
            Icon(Icons.Default.ExpandMore, null, tint = colors.foregroundMuted)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            options.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(languageDisplayName(tag)) },
                    onClick = {
                        onSelected(tag)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun OptionToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
    testTag: String,
    icon: ImageVector,
    iconColor: Color,
    iconBackground: Color,
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
            .background(colors.surfaceCard)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = iconColor, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.foregroundPrimary)
            Text(subtitle, fontSize = 12.sp, color = colors.foregroundSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.foregroundOnAccent,
                checkedTrackColor = colors.accentPrimary,
                uncheckedTrackColor = colors.borderSubtle,
            ),
        )
    }
}
