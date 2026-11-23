package com.github.jvsena42.loopky.ui.update

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.data.nexus.ClientCompatibility
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import org.koin.compose.koinInject

/**
 * Covers the app once the indexer says this build is too old ([ClientCompatibility], 410 or 426).
 * There is no way past it but the store: every global read would fail behind it, and a screen
 * that half works reads as a broken app rather than an old one.
 */
@Composable
fun ForceUpdateRoute(modifier: Modifier = Modifier) {
    val compatibility = koinInject<ClientCompatibility>()
    val isOutdated by compatibility.outdated.collectAsStateWithLifecycle()
    if (!isOutdated) return
    val context = LocalContext.current
    BackHandler(enabled = true) {}
    ForceUpdateScreen(
        onUpdate = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        },
        modifier = modifier,
    )
}

@Composable
fun ForceUpdateScreen(onUpdate: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            // Swallows taps so nothing underneath can be reached.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding()
            .padding(horizontal = 32.dp)
            .testTag("force_update_screen"),
    ) {
        Text(
            text = stringResource(R.string.force_update_title),
            color = colors.foregroundPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.force_update_message),
            color = colors.foregroundMuted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        LoopkyPrimaryButton(
            label = stringResource(R.string.force_update_action),
            onClick = onUpdate,
            modifier = Modifier.testTag("force_update_action"),
        )
    }
}

private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.github.jvsena42.loopky"

@Preview
@Composable
private fun ForceUpdateScreenPreview() {
    LoopkyTheme { ForceUpdateScreen(onUpdate = {}) }
}
