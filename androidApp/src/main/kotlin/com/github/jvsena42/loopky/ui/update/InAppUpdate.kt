package com.github.jvsena42.loopky.ui.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.data.storage.UpdatePromptGate
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.layout.windowWidthClass
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.util.runSuspendCatching
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import com.google.android.play.core.ktx.requestCompleteUpdate
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Play's flexible in-app update: Play asks, downloads in the background, and [UpdateReadyBar]
 * offers the restart that installs it. Either prompt appears at most once a day — see
 * [UpdatePromptGate].
 *
 * Every Play call is allowed to fail quietly. A debug build, a sideloaded APK and a device without
 * Play all answer `requestAppUpdateInfo` with an error, and none of them has an update to offer.
 */
@Composable
fun InAppUpdateRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember(context) { AppUpdateManagerFactory.create(context) }
    val gate = koinInject<UpdatePromptGate>()
    val scope = rememberCoroutineScope()
    // One prompt per launch even if the day rolls over under a running process.
    var hasPrompted by rememberSaveable { mutableStateOf(false) }
    var isReady by remember { mutableStateOf(false) }
    var isHidden by rememberSaveable { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(StartIntentSenderForResult()) {}

    // A download finishing in front of the reader is the answer to a prompt they just accepted,
    // so it is not a second prompt and does not go through the gate.
    DisposableEffect(manager) {
        val listener = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) isReady = true
        }
        manager.registerListener(listener)
        onDispose { manager.unregisterListener(listener) }
    }

    // On every resume, not once: a download can finish while Loopky is in the background, and the
    // listener above is only told about it while this is composed.
    LifecycleResumeEffect(manager) {
        val check = scope.launch {
            val info = runSuspendCatching { manager.requestAppUpdateInfo() }.getOrNull()
                ?: return@launch
            val isDownloaded = info.installStatus() == InstallStatus.DOWNLOADED
            val isOffered = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
            val hasSomethingToShow = (isDownloaded || isOffered) && !isReady && !hasPrompted
            if (!hasSomethingToShow || !gate.tryAcquire()) return@launch
            hasPrompted = true
            if (isDownloaded) {
                isReady = true
            } else {
                runCatching {
                    manager.startUpdateFlowForResult(
                        info,
                        consent,
                        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                    )
                }
            }
        }
        onPauseOrDispose { check.cancel() }
    }

    if (isReady && !isHidden) {
        UpdateReadyBar(
            onRestart = { scope.launch { runSuspendCatching { manager.requestCompleteUpdate() } } },
            onDismiss = { isHidden = true },
            modifier = modifier,
        )
    }
}

@Composable
internal fun UpdateReadyBar(
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    // Hosted above the nav host, so it cannot know whether a tab bar is under it. Below expanded
    // width it clears one anyway: sitting on the bar hides the tabs behind the same dark colour.
    val bottomClearance = if (windowWidthClass().isExpanded) 0.dp else TAB_BAR_CLEARANCE
    Box(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp)
            .padding(bottom = bottomClearance),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Snackbar(
            modifier = Modifier
                .contentPane(PaneWidth.Focused)
                .testTag("update_ready_bar"),
            containerColor = colors.navBarBackground,
            contentColor = colors.foregroundOnAccent,
            action = {
                TextButton(
                    onClick = onRestart,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                    modifier = Modifier.testTag("update_ready_restart"),
                ) {
                    Text(
                        text = stringResource(R.string.update_ready_restart),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissAction = {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.navBarInactive),
                    modifier = Modifier.testTag("update_ready_dismiss"),
                ) {
                    Text(text = stringResource(R.string.permission_not_now), fontSize = 13.sp)
                }
            },
        ) {
            Text(text = stringResource(R.string.update_ready_message), fontSize = 13.sp)
        }
    }
}

private val TAB_BAR_CLEARANCE = 72.dp
