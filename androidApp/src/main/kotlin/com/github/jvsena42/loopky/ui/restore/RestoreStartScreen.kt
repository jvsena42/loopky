package com.github.jvsena42.loopky.ui.restore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.signup.SignupScaffold
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * Pick how to restore.
 *
 * One option today; the encrypted recovery file joins it in the next phase, which is why this is a
 * list rather than a button that goes straight to the phrase screen.
 */
@Composable
fun RestoreStartRoute(
    onBack: () -> Unit,
    onRestoreWithPhrase: () -> Unit,
    onRestoreWithFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SignupScaffold(
        title = stringResource(R.string.restore_start_title),
        subtitle = stringResource(R.string.restore_start_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        RestoreMethodCard(
            label = stringResource(R.string.restore_method_phrase),
            detail = stringResource(R.string.restore_method_phrase_detail),
            onClick = onRestoreWithPhrase,
            modifier = Modifier.testTag("restore_method_phrase"),
        )
        Spacer(Modifier.height(12.dp))
        RestoreMethodCard(
            label = stringResource(R.string.restore_method_file),
            detail = stringResource(R.string.restore_method_file_detail),
            onClick = onRestoreWithFile,
            modifier = Modifier.testTag("restore_method_file"),
        )
    }
}

@Composable
private fun RestoreMethodCard(
    label: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceSecondary)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(
            text = label,
            color = colors.foregroundPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = detail,
            color = colors.foregroundSecondary,
            fontSize = 13.sp,
        )
    }
}

@Preview
@Composable
private fun RestoreStartPreview() {
    LoopkyTheme {
        RestoreStartRoute(onBack = {}, onRestoreWithPhrase = {}, onRestoreWithFile = {})
    }
}
