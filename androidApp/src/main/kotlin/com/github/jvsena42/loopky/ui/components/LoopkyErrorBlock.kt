package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * Shared error state: title + explanation + optional retry, rendered from an [ErrorReason]
 * rather than an exception message. Replaces the near-identical private `ErrorBlock`
 * composables that Home and Discover each carried.
 */
@Composable
fun LoopkyErrorBlock(
    reason: ErrorReason,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    canRetry: Boolean = true,
) {
    LoopkyErrorBlock(
        title = errorTitle(reason),
        message = errorMessage(reason),
        onRetry = onRetry,
        modifier = modifier,
        canRetry = canRetry,
    )
}

/**
 * The same block for failures [ErrorReason] doesn't model. That enum is network/session/auth
 * throughout, so a local one — a file that won't open, a picked photo — supplies its own copy
 * rather than forcing members into an enum eight unrelated screens exhaustively `when` over.
 */
@Composable
fun LoopkyErrorBlock(
    title: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    canRetry: Boolean = true,
    retryLabel: String = stringResource(R.string.home_retry),
) {
    val colors = LoopkyTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.padding(horizontal = 24.dp),
    ) {
        Text(
            text = title,
            color = colors.foregroundPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            color = colors.foregroundMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        if (canRetry) {
            TextButton(onClick = onRetry, modifier = Modifier.testTag("error_retry")) {
                Text(retryLabel, color = colors.accentPrimary)
            }
        }
    }
}
