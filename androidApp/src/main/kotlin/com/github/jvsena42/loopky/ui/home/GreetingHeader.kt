package com.github.jvsena42.loopky.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

@Composable
fun GreetingHeader(name: String) {
    val colors = LoopkyTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.home_greeting_hello),
            color = colors.foregroundMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.home_greeting_name, name),
            color = colors.foregroundPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Preview
@Composable
private fun GreetingHeaderPreview() {
    LoopkyTheme {
        Column(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(20.dp),
        ) {
            GreetingHeader(name = "Alex")
        }
    }
}
