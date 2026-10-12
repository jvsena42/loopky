package com.github.jvsena42.loopky.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The December invitation to the year's recap. On the *secondary* accent so it does not read as a
 * second daily-goal hero, which is the primary one directly beneath it.
 */
@Composable
fun RecapBanner(year: Int, onClick: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(colors.accentSecondary)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("recap_banner")
            .padding(start = 18.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "🎁", fontSize = 28.sp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.recap_banner_title, year),
                color = colors.foregroundOnAccent,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = stringResource(R.string.recap_banner_body),
                color = colors.foregroundOnAccentMuted,
                fontSize = 13.sp,
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.testTag("recap_banner_dismiss")) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.recap_banner_dismiss),
                tint = colors.foregroundOnAccent,
            )
        }
    }
}

@Preview
@Composable
private fun RecapBannerPreview() {
    LoopkyTheme { RecapBanner(year = 2026, onClick = {}, onDismiss = {}) }
}
