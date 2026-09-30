package com.github.jvsena42.loopky.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.LoopkySecondaryButton
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * "Nothing to study yet" empty state: a white card with a circular
 * book-open badge, title + subtitle, followed by the "Create a deck" / "Browse examples" actions.
 */
@Composable
fun HomeEmptyContent(
    onCreateDeckClick: () -> Unit,
    onBrowseExamplesClick: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = colors.shadowElevationHigh,
                spotColor = colors.shadowElevationHigh,
            )
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceCard)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(colors.accentPrimarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = colors.accentPrimary,
                modifier = Modifier.size(48.dp),
            )
        }
        Text(
            text = stringResource(R.string.home_empty_title),
            color = colors.foregroundPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.home_empty_subtitle),
            color = colors.foregroundMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp,
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LoopkyPrimaryButton(
            label = stringResource(R.string.home_create_first_deck),
            onClick = onCreateDeckClick,
            modifier = Modifier.testTag("home_create_deck"),
        )
        LoopkySecondaryButton(
            text = stringResource(R.string.home_browse_examples),
            onClick = onBrowseExamplesClick,
            modifier = Modifier
                .testTag("home_browse_examples")
                .fillMaxWidth(),
        )
    }
}

@Preview
@Composable
private fun HomeEmptyContentPreview() {
    LoopkyTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            HomeEmptyContent(
                onCreateDeckClick = {},
                onBrowseExamplesClick = {},
            )
        }
    }
}
