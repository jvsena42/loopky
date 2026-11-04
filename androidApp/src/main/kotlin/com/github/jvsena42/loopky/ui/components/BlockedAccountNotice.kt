package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * What a blocked account's profile or deck shows instead of itself, when a link reaches one. No
 * name, no content and no Unblock: unblocking lives in Settings → Blocked accounts (#577).
 */
@Composable
fun BlockedAccountNotice(modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceCard)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Block,
            contentDescription = null,
            tint = colors.foregroundMuted,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = stringResource(R.string.blocked_account_title),
            color = colors.foregroundPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.W700,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.blocked_account_body),
            color = colors.foregroundSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview
@Composable
private fun BlockedAccountNoticePreview() {
    LoopkyTheme { BlockedAccountNotice() }
}
