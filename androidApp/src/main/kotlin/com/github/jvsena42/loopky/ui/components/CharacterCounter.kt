package com.github.jvsena42.loopky.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The "123/500" hint under a capped text field, so the limit is visible before it is hit rather
 * than only as an error after it. Turns danger-coloured once [current] passes [max] — the
 * field's own error text says what to do about it.
 */
@Composable
fun CharacterCounter(current: Int, max: Int, modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Text(
        text = stringResource(R.string.char_counter, current, max),
        fontSize = 12.sp,
        color = if (current > max) colors.danger else colors.foregroundMuted,
        modifier = modifier,
    )
}
