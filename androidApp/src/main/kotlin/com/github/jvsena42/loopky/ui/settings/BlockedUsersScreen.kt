package com.github.jvsena42.loopky.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.settings.BlockedUsersEffect
import com.github.jvsena42.loopky.presentation.settings.BlockedUsersUiState
import com.github.jvsena42.loopky.presentation.settings.BlockedUsersViewModel
import com.github.jvsena42.loopky.ui.components.AuthorRow
import com.github.jvsena42.loopky.ui.components.LoopkyErrorBlock
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.ScreenBottomInsetSpacer
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.layout.screenSides
import com.github.jvsena42.loopky.ui.layout.screenTop
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.toast
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun BlockedUsersRoute(
    onBack: () -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
) {
    val viewModel = koinViewModel<BlockedUsersViewModel>()
    val context = LocalContext.current
    val currentOpenProfile by rememberUpdatedState(onOpenProfile)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is BlockedUsersEffect.OpenProfile -> currentOpenProfile(effect.pubky)
                // The row is still there with its button live again, so a toast is enough.
                is BlockedUsersEffect.ShowError -> context.toast(R.string.unblock_failed)
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    BlockedUsersScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::onRetry,
        onOpenProfile = viewModel::onOpenProfile,
        onUnblock = viewModel::onUnblock,
    )
}

@Composable
private fun BlockedUsersScreen(
    state: BlockedUsersUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onUnblock: (String) -> Unit,
) {
    val colors = LoopkyTheme.colors
    val errorReason = state.errorReason

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.screenTop.union(WindowInsets.screenSides))
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.surfaceCard)
                .clickable(onClick = onBack)
                .testTag("blocked_users_back"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.settings_back_content_description),
                tint = colors.foregroundPrimary,
                modifier = Modifier.size(20.dp),
            )
        }

        Text(
            text = stringResource(R.string.settings_blocked_users),
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.foregroundPrimary,
        )
        // The list is pubky.app's mute list, so someone muted there appears here too.
        Text(
            text = stringResource(R.string.blocked_users_caption),
            fontSize = 13.sp,
            color = colors.foregroundMuted,
        )

        when {
            state.isLoading -> Box(modifier = Modifier.fillMaxSize()) {
                LoopkyLoadingScreen(message = stringResource(R.string.blocked_users_loading))
            }

            errorReason != null -> LoopkyErrorBlock(
                reason = errorReason,
                onRetry = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )

            state.isEmpty -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.blocked_users_empty),
                    fontSize = 14.sp,
                    color = colors.foregroundMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .testTag("blocked_users_empty")
                        .padding(horizontal = 24.dp),
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .contentPane(PaneWidth.Reading),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(state.people, key = { _, person -> person.pubky }) { index, person ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AuthorRow(
                            identity = person,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenProfile(person.pubky) }
                                .testTag("blocked_row_$index")
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        )
                        TextButton(
                            onClick = { onUnblock(person.pubky) },
                            enabled = person.pubky !in state.unblocking,
                            modifier = Modifier.testTag("blocked_unblock_$index"),
                        ) {
                            Text(
                                text = stringResource(R.string.unblock_action),
                                color = colors.accentPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                item(key = "bottom_inset") { ScreenBottomInsetSpacer() }
            }
        }
    }
}

@Preview
@Composable
private fun BlockedUsersScreenPreview() {
    LoopkyTheme {
        BlockedUsersScreen(
            state = BlockedUsersUiState(
                isLoading = false,
                people = listOf(
                    PubkyIdentity("ada1xqz9uvwxyz", "Ada Lovelace", null, null),
                    PubkyIdentity("byron7yt2abcdef", null, null, null),
                ),
            ),
            onBack = {},
            onRetry = {},
            onOpenProfile = {},
            onUnblock = {},
        )
    }
}
