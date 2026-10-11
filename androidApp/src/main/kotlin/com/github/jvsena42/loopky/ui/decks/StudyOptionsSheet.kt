package com.github.jvsena42.loopky.ui.decks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.StudyFeature
import com.github.jvsena42.loopky.domain.model.StudyFeatures
import com.github.jvsena42.loopky.presentation.decks.StudyOptionsModel
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.OptionToggleRow
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * What Edit raises on a deck you follow: your own study opt-ins, and under them the way to a copy.
 *
 * In that order on purpose. The switches cost nothing and are what most readers came to change; a
 * copy gives up the author's updates and the reader's progress, so it is the second step and never
 * the first thing asked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyOptionsSheet(
    options: StudyOptionsModel,
    onChange: (StudyFeature, Boolean) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Fully open: at half height the copy row sits below the fold, and it is the only way on.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LoopkyTheme.colors.surfaceSecondary,
    ) {
        StudyOptionsContent(options, onChange, onReset, onSave, onCopy)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun StudyOptionsContent(
    options: StudyOptionsModel,
    onChange: (StudyFeature, Boolean) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val chosen = options.chosen
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .contentPane(PaneWidth.Focused)
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
            .semantics { testTagsAsResourceId = true }
            .testTag("study_options_sheet"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.study_options_title),
            color = colors.foregroundPrimary,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = stringResource(R.string.study_options_subtitle),
            color = colors.foregroundSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        OptionToggleRow(
            title = stringResource(R.string.publish_listen_title),
            subtitle = stringResource(R.string.publish_listen_subtitle),
            checked = chosen.listen && options.speechReady,
            onToggle = { onChange(StudyFeature.Listen, !chosen.listen) },
            testTag = "study_options_listen",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            iconColor = colors.accentPrimary,
            iconBackground = colors.accentPrimarySoft,
            enabled = options.speechReady && !options.isSaving,
        )
        OptionToggleRow(
            title = stringResource(R.string.publish_speak_title),
            subtitle = stringResource(R.string.publish_speak_subtitle),
            checked = chosen.speak && options.speechReady,
            onToggle = { onChange(StudyFeature.Speak, !chosen.speak) },
            testTag = "study_options_speak",
            icon = Icons.Default.Mic,
            iconColor = colors.accentSecondary,
            iconBackground = colors.accentSecondarySoft,
            enabled = options.speechReady && !options.isSaving,
        )
        if (!options.speechReady) {
            Text(
                text = stringResource(R.string.study_options_speech_unavailable),
                color = colors.foregroundSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.testTag("study_options_speech_unavailable"),
            )
        }
        OptionToggleRow(
            title = stringResource(R.string.publish_type_title),
            subtitle = stringResource(R.string.publish_type_subtitle),
            checked = chosen.type,
            onToggle = { onChange(StudyFeature.Typing, !chosen.type) },
            testTag = "study_options_type",
            icon = Icons.Default.Keyboard,
            iconColor = colors.accentPrimary,
            iconBackground = colors.accentPrimarySoft,
            enabled = !options.isSaving,
        )
        OptionToggleRow(
            title = stringResource(R.string.publish_reverse_title),
            subtitle = stringResource(R.string.publish_reverse_subtitle),
            checked = chosen.reverse,
            onToggle = { onChange(StudyFeature.Reverse, !chosen.reverse) },
            testTag = "study_options_reverse",
            icon = Icons.Default.SwapHoriz,
            iconColor = colors.accentSecondary,
            iconBackground = colors.accentSecondarySoft,
            enabled = !options.isSaving,
        )
        if (options.differsFromAuthor) {
            TextButton(
                onClick = onReset,
                enabled = !options.isSaving,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .testTag("study_options_reset"),
            ) {
                Text(stringResource(R.string.study_options_reset), color = colors.accentPrimary)
            }
        }
        LoopkyPrimaryButton(
            label = stringResource(R.string.deck_editor_save),
            onClick = onSave,
            loading = options.isSaving,
            modifier = Modifier.testTag("study_options_save"),
        )
        CopyDeckRow(enabled = !options.isSaving, onClick = onCopy, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun CopyDeckRow(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
            .background(colors.surfaceCard)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(14.dp)
            .testTag("study_options_copy"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.study_options_copy_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = colors.foregroundPrimary,
            )
            Text(
                text = stringResource(R.string.study_options_copy_subtitle),
                fontSize = 12.sp,
                color = colors.foregroundSecondary,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.foregroundMuted,
        )
    }
}

@Preview
@Composable
private fun StudyOptionsContentPreview() {
    LoopkyTheme {
        StudyOptionsContent(
            options = StudyOptionsModel(
                chosen = StudyFeatures(listen = true, speak = false, type = true, reverse = false),
                author = StudyFeatures(listen = true, speak = true, type = false, reverse = false),
                speechReady = true,
            ),
            onChange = { _, _ -> },
            onReset = {},
            onSave = {},
            onCopy = {},
            modifier = Modifier.background(LoopkyTheme.colors.surfaceSecondary),
        )
    }
}
