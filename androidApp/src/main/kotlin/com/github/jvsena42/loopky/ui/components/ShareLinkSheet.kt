package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.shareLinkWithQr
import kotlinx.coroutines.delay

/**
 * [ShareLinkSheet] for [target], or nothing when there is none.
 *
 * The copy and share behaviour is the same wherever a share button is, so it lives here rather
 * than in each screen that raises one.
 */
@Composable
fun ShareLinkSheetHost(target: ShareLinkTarget?, onDismiss: () -> Unit) {
    if (target == null) return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // On the button rather than in a toast: Android 13 raises its own clipboard confirmation, and
    // a toast lands on top of it — two notices, both over the buttons that just moved out of reach.
    var copied by remember(target.link) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_LABEL_MS)
            copied = false
        }
    }
    ShareLinkSheet(
        title = target.title,
        link = target.link,
        copied = copied,
        onCopy = {
            clipboard.setText(AnnotatedString(target.link))
            copied = true
        },
        onShare = {
            context.shareLinkWithQr(
                text = target.message,
                link = target.link,
                chooserTitle = context.getString(target.chooserTitle),
            )
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/**
 * What a share button raises: the link as a QR code, with the ways out of the app underneath.
 *
 * The code is the point of the sheet. Sharing used to go straight to the system chooser, which
 * only ever helps someone who already has the recipient in a messaging app — the person sitting
 * across the table had no way to take the link off the screen. A code they can point a camera at
 * needs no channel at all, and the same picture is what [onShare] attaches.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ShareLinkSheet(
    title: String,
    link: String,
    copied: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // A raised surface cannot take the ground's colour, or in dark mode the sheet has no edge.
        containerColor = colors.surfaceSecondary,
    ) {
        Column(
            modifier = Modifier
                // A sheet spans the window, so on a tablet the buttons would otherwise sit a
                // thousand dp apart with the code stranded between them.
                .contentPane(PaneWidth.Focused)
                .semantics { testTagsAsResourceId = true }
                .testTag("share_link_sheet")
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                color = colors.foregroundPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.share_sheet_hint),
                color = colors.foregroundSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            // White in both themes: a QR inverted for dark mode is not one any scanner will read,
            // and this padding is the quiet zone the encoder is only asked for one module of.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(16.dp),
            ) {
                QrCode(
                    content = link,
                    contentDescription = stringResource(R.string.share_sheet_qr_content_description),
                )
            }
            Text(
                text = link,
                color = colors.foregroundMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                // Middle, not tail: a pubky URI's two ends are what identify it — the account at
                // the front, the deck id at the back — and eliding the back leaves 60 characters
                // of key saying nothing. One line, since a wrapped address never elides at all.
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                softWrap = false,
                modifier = Modifier.testTag("share_link_uri"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LoopkyOutlinedButton(
                    label = stringResource(
                        if (copied) R.string.share_sheet_copied else R.string.share_sheet_copy,
                    ),
                    onClick = onCopy,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("share_link_copy"),
                )
                LoopkyPrimaryButton(
                    label = stringResource(R.string.share_sheet_send),
                    onClick = onShare,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("share_link_send"),
                )
            }
        }
    }
}

@Preview
@Composable
private fun ShareLinkSheetPreview() {
    LoopkyTheme {
        Column(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfaceSecondary)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Spanish Verbs", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(16.dp),
            ) {
                QrCode(content = "pubky://abc/pub/loopky/decks/deck1/manifest.json")
            }
        }
    }
}

private const val COPIED_LABEL_MS = 2000L
