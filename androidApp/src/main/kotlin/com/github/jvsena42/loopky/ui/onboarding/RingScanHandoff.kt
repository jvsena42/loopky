package com.github.jvsena42.loopky.ui.onboarding

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.components.LoopkySecondaryButton
import com.github.jvsena42.loopky.ui.components.QrCode
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The tablet way in: the pending authorisation as a QR code for Pubky Ring on the user's phone.
 *
 * The code encodes the same one-shot `pubkyauth://` URL the deeplink would have carried, and the
 * relay poll behind it is the same one — Ring does not care whether it was opened by a tap here or
 * a camera over there. The consent tick is deliberately absent: it was already ticked to get to
 * this state, and re-asking mid-handoff would be a second gate on a decision already made.
 */
@Composable
internal fun RingScanPanel(
    authUrl: String,
    ringInstalledHere: Boolean,
    onOpenRingHere: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    RingScanContent(
        authUrl = authUrl,
        title = stringResource(R.string.onboarding_qr_title),
        body = stringResource(R.string.onboarding_qr_body),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceCard)
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        // Only when something on this device actually claims pubkyauth://. Offering it otherwise
        // is a button that opens nothing, on the one screen where a dead end means the user cannot
        // get into the app at all.
        if (ringInstalledHere) {
            LoopkySecondaryButton(
                text = stringResource(R.string.onboarding_qr_open_here),
                onClick = onOpenRingHere,
                modifier = Modifier.testTag("onboarding_qr_open_here"),
            )
        }
        CopyLinkButton(authUrl = authUrl)
        // The panel's only way out: it replaces the sign-in call to action rather than covering
        // it, so without this the screen has no control that abandons the wait.
        TextButton(
            onClick = onCancel,
            modifier = Modifier.testTag("onboarding_qr_cancel"),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.foregroundMuted),
        ) {
            Text(
                text = stringResource(R.string.onboarding_qr_cancel),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The phone way in when the deeplink has nowhere to go.
 *
 * Shown only where Ring is *not* installed on this device. With Ring here the handoff is a tap and
 * Ring is already in the foreground; a sheet behind it would just be something to dismiss on the
 * way back. Without it, the authorisation is live all the same and the key is presumably in Ring
 * on another phone — which no deeplink can reach, so the code that phone can scan is the way in.
 * This is the case that used to end on "Pubky Ring isn't installed" with nowhere to go but back.
 *
 * There is no Cancel button. A sheet already has two ways out that a panel does not — the scrim
 * and the swipe — and both land on [onDismiss], so a third control would be a button restating
 * the gesture the user's thumb is already on.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun RingScanSheet(
    authUrl: String,
    onGetRing: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    ModalBottomSheet(
        // Dismissing abandons the authorisation, exactly like the panel's Cancel — a sheet swiped
        // away with the relay still polling would leave a live flow with no surface to complete
        // or abandon it.
        onDismissRequest = onDismiss,
        modifier = modifier,
        // Straight to full height. The half-height resting position is for a sheet you peek at
        // while the screen behind still matters; this one is the whole task, and partially
        // expanded it opened with the QR mid-scroll and every button below the fold.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceCard,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        RingScanContent(
            authUrl = authUrl,
            title = stringResource(R.string.onboarding_qr_title),
            body = stringResource(R.string.onboarding_qr_sheet_body),
            modifier = Modifier
                .fillMaxWidth()
                // A sheet is its own window, so the activity's setting does not reach it and the
                // test tags below are invisible to `android layout` without this.
                .semantics { testTagsAsResourceId = true }
                .testTag("onboarding_ring_qr_sheet")
                // A QR big enough to scan plus its controls does not fit a short phone even fully
                // expanded, and a Column that overflows clips in silence — the first cut of this
                // sheet lost everything under the code below the fold with nothing to say it was
                // there.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            CopyLinkButton(authUrl = authUrl)
            // The other half of "Ring is not on this device": someone who has it on another phone
            // scans the code, and someone who does not have it at all needs to be told where it
            // comes from. Without this, removing the not-installed error removed the only pointer
            // to the app this whole screen depends on.
            TextButton(
                onClick = onGetRing,
                modifier = Modifier.testTag("onboarding_qr_get_ring"),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.foregroundMuted),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_get_ring),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * The handoff itself, shared by the tablet panel and the phone sheet so the two cannot drift.
 *
 * Only the words and the trailing controls differ — the panel is a surface with no other way out
 * and may be able to open Ring here, the sheet is dismissible and by definition cannot — so both
 * are passed in rather than branched on a window size this composable has no business knowing
 * about.
 */
@Composable
private fun RingScanContent(
    authUrl: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val colors = LoopkyTheme.colors

    Column(
        modifier = modifier.testTag("onboarding_ring_qr"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = title,
            color = colors.foregroundPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            color = colors.foregroundSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
        // A white plate under the code regardless of theme: a QR inverted for dark mode is not a
        // QR any scanner will read, and the quiet zone is this padding rather than encoded margin.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(16.dp),
        ) {
            QrCode(content = authUrl, size = QR_SIZE)
        }
        Text(
            text = stringResource(R.string.onboarding_qr_waiting),
            color = colors.foregroundMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        actions()
    }
}

/**
 * The escape hatch for a camera that will not read the code: the same one-shot URL, on the
 * clipboard, to be pasted into Ring by hand.
 */
@Composable
private fun CopyLinkButton(authUrl: String) {
    val colors = LoopkyTheme.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    TextButton(
        onClick = {
            clipboard.setText(AnnotatedString(authUrl))
            Toast.makeText(context, R.string.onboarding_qr_copied, Toast.LENGTH_SHORT).show()
        },
        colors = ButtonDefaults.textButtonColors(contentColor = colors.accentSecondary),
    ) {
        Text(
            text = stringResource(R.string.onboarding_qr_copy),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private val QR_SIZE = 220.dp
