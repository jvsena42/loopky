package com.github.jvsena42.loopky.ui.importflow

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.AgentApp
import com.github.jvsena42.loopky.domain.model.AgentPrompt
import com.github.jvsena42.loopky.domain.model.PluginInstall
import com.github.jvsena42.loopky.ui.components.LoopkySecondaryButton
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.delay

/**
 * "Let your AI build the deck": the landing page's prompt box, on the screen whose premise is that
 * the deck comes from somewhere else. An idea fills the request, the reader edits it, and Copy or
 * an agent's icon hands over [AgentPrompt.build] — every icon copies too, because Codex and Jules
 * open empty.
 *
 * [detailed] is for a window with room to spare: the full prompt stays open rather than behind a
 * toggle, and the plugin's install commands are shown, each copied on its own.
 *
 * [idea] and [request] are hoisted because the screen moves this card between layouts on
 * rotation, and state saved inside it would be keyed to the call site it left. A null [request]
 * means the idea's own text, untouched.
 */
@Composable
internal fun AgentPromptCard(
    idea: PromptIdea,
    request: String?,
    onIdeaChange: (PromptIdea) -> Unit,
    onRequestChange: (String) -> Unit,
    detailed: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    val requestText = request ?: stringResource(idea.request)
    var showFullPrompt by rememberSaveable { mutableStateOf(false) }
    val prompt = AgentPrompt.build(requestText)

    // Copying is silent below API 33, where the system shows no confirmation of its own.
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_LABEL_MILLIS)
            copied = false
        }
    }
    val copyPrompt = {
        clipboard.setText(AnnotatedString(prompt))
        copied = true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
            .background(colors.surfaceCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.bulk_ai_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = colors.foregroundPrimary,
        )
        Text(
            text = stringResource(R.string.bulk_ai_body),
            fontSize = 13.sp,
            color = colors.foregroundSecondary,
        )

        SectionLabel(stringResource(R.string.bulk_ai_ideas_label))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            PromptIdea.entries.forEach { entry ->
                FilterChip(
                    selected = entry == idea,
                    onClick = { onIdeaChange(entry) },
                    label = { Text("${entry.emoji} ${stringResource(entry.title)}", fontSize = 13.sp) },
                    modifier = Modifier.testTag("bulk_ai_idea_${entry.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = colors.surfacePrimary,
                        labelColor = colors.foregroundPrimary,
                        selectedContainerColor = colors.accentPrimarySoft,
                        selectedLabelColor = colors.accentPrimary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = entry == idea,
                        borderColor = colors.borderSubtle,
                        selectedBorderColor = colors.accentPrimary,
                    ),
                )
            }
        }

        OutlinedTextField(
            value = requestText,
            onValueChange = onRequestChange,
            modifier = Modifier.fillMaxWidth().testTag("bulk_ai_request"),
            label = { Text(stringResource(R.string.bulk_ai_request_label)) },
            minLines = 3,
            textStyle = TextStyle(fontSize = 14.sp, color = colors.foregroundPrimary),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accentPrimary,
                unfocusedBorderColor = colors.borderSubtle,
                cursorColor = colors.accentPrimary,
                focusedLabelColor = colors.accentPrimary,
            ),
        )

        LoopkySecondaryButton(
            text = stringResource(if (copied) R.string.bulk_cli_copied else R.string.bulk_cli_copy),
            onClick = copyPrompt,
            modifier = Modifier.fillMaxWidth().testTag("bulk_cli_copy"),
            icon = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(stringResource(R.string.bulk_ai_open_in))
            AgentApp.entries.forEach { app ->
                AgentAppButton(
                    app = app,
                    onClick = {
                        copyPrompt()
                        uriHandler.openUri(AgentPrompt.openUrl(app, requestText))
                    },
                )
            }
        }
        Text(
            text = stringResource(R.string.bulk_ai_open_hint),
            fontSize = 12.sp,
            color = colors.foregroundMuted,
        )

        if (detailed) {
            FullPrompt(prompt)
            PluginInstalls(onCopy = { clipboard.setText(AnnotatedString(it)) })
        } else {
            TextButton(
                onClick = { showFullPrompt = !showFullPrompt },
                modifier = Modifier.testTag("bulk_ai_full_prompt"),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(stringResource(R.string.bulk_ai_full_prompt), color = colors.accentPrimary)
            }
            if (showFullPrompt) FullPrompt(prompt)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = LoopkyTheme.colors.foregroundMuted,
    )
}

/**
 * A plain clickable circle rather than an `IconButton`, whose minimum touch size draws a 48dp
 * circle over its 40dp slot — four of them in a row overlapped.
 */
@Composable
private fun AgentAppButton(app: AgentApp, onClick: () -> Unit) {
    val colors = LoopkyTheme.colors
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.surfacePrimary)
            .border(1.dp, colors.borderSubtle, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("bulk_ai_open_${app.name.lowercase()}"),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(app.icon),
            contentDescription = app.title,
            modifier = Modifier.size(22.dp),
            // Cursor's mark is a single ink; the others carry their own colours.
            colorFilter = if (app == AgentApp.Cursor) ColorFilter.tint(colors.foregroundPrimary) else null,
        )
    }
}

@Composable
private fun FullPrompt(prompt: String) {
    val colors = LoopkyTheme.colors
    Text(
        text = prompt,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = colors.foregroundSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceSecondary)
            .padding(12.dp)
            .testTag("bulk_ai_prompt_text"),
    )
}

/** One row per command: two pasted together are refused by Add Marketplace (loopky.github.io#19). */
@Composable
private fun PluginInstalls(onCopy: (String) -> Unit) {
    val colors = LoopkyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.bulk_ai_plugin_title),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = colors.foregroundPrimary,
        )
        Text(
            text = stringResource(R.string.bulk_ai_plugin_body),
            fontSize = 13.sp,
            color = colors.foregroundSecondary,
        )
        AgentPrompt.pluginInstalls.forEach { install: PluginInstall ->
            Spacer(Modifier.height(2.dp))
            SectionLabel(install.app)
            install.commands.forEach { command -> CommandRow(command, onCopy) }
        }
    }
}

@Composable
private fun CommandRow(command: String, onCopy: (String) -> Unit) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceSecondary)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = command,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = colors.foregroundPrimary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onCopy(command) }, modifier = Modifier.testTag("bulk_ai_copy_command")) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.bulk_ai_copy_command),
                tint = colors.foregroundMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

internal enum class PromptIdea(val emoji: String, @param:StringRes val title: Int, @param:StringRes val request: Int) {
    Anime("🍥", R.string.bulk_ai_idea_anime_title, R.string.bulk_ai_idea_anime_request),
    Series("📺", R.string.bulk_ai_idea_series_title, R.string.bulk_ai_idea_series_request),
    Playlist("🎧", R.string.bulk_ai_idea_playlist_title, R.string.bulk_ai_idea_playlist_request),
    Interview("💼", R.string.bulk_ai_idea_interview_title, R.string.bulk_ai_idea_interview_request),
    Trip("✈️", R.string.bulk_ai_idea_trip_title, R.string.bulk_ai_idea_trip_request),
    Notes("📝", R.string.bulk_ai_idea_notes_title, R.string.bulk_ai_idea_notes_request),
}

@get:DrawableRes
private val AgentApp.icon: Int
    get() = when (this) {
        AgentApp.ClaudeCode -> R.drawable.ic_agent_claude_code
        AgentApp.Codex -> R.drawable.ic_agent_codex
        AgentApp.Jules -> R.drawable.ic_agent_jules
        AgentApp.Cursor -> R.drawable.ic_agent_cursor
    }

/** How long the copy button stands in for the confirmation Android 12 and below never show. */
private const val COPIED_LABEL_MILLIS = 2_000L
