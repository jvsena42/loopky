package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * Marks something as the signed-in user's own. Sits *beside* their name rather than replacing it,
 * so one person reads the same everywhere and ownership is extra information.
 */
@Composable
fun YouBadge(modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Text(
        text = stringResource(R.string.identity_you_badge),
        fontSize = 10.sp,
        fontWeight = FontWeight.W700,
        color = colors.accentSecondary,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colors.accentSecondarySoft)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
