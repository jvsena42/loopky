package com.github.jvsena42.loopky.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.github.jvsena42.loopky.presentation.profile.FollowListUiState
import com.github.jvsena42.loopky.presentation.profile.FollowListViewModel
import com.github.jvsena42.loopky.presentation.profile.FollowSource
import com.github.jvsena42.loopky.ui.components.AuthorRow
import com.github.jvsena42.loopky.ui.components.LoopkyErrorBlock
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun FollowListRoute(
    pubky: String,
    source: FollowSource,
    onBack: () -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
) {
    val viewModel = koinViewModel<FollowListViewModel> { parametersOf(pubky, source) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    FollowListScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::onRetry,
        onOpenProfile = onOpenProfile,
    )
}

@Composable
private fun FollowListScreen(
    state: FollowListUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val colors = LoopkyTheme.colors
    val errorReason = state.errorReason

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.surfaceCard)
                .clickable(onClick = onBack)
                .testTag("follow_list_back"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.follow_list_back_content_description),
                tint = colors.foregroundPrimary,
                modifier = Modifier.size(20.dp),
            )
        }

        Text(
            text = stringResource(
                when (state.source) {
                    FollowSource.FOLLOWING -> R.string.follow_list_following_title
                    FollowSource.FOLLOWERS -> R.string.follow_list_followers_title
                },
            ),
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.foregroundPrimary,
        )

        when {
            state.isLoading -> Box(modifier = Modifier.fillMaxSize()) {
                LoopkyLoadingScreen(message = stringResource(R.string.follow_list_loading))
            }

            errorReason != null -> LoopkyErrorBlock(
                reason = errorReason,
                onRetry = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )

            state.people.isEmpty() -> EmptyFollowList(source = state.source, isSelf = state.isSelf)

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .contentPane(PaneWidth.Reading),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(state.people, key = { _, person -> person.pubky }) { index, person ->
                    // The whole row is the target, not just the name: a list of people is a list
                    // of links. The click also has to live here for the test tag to reach the
                    // accessibility tree — a tag on a node with no semantics of its own is merged
                    // away and surfaces no resource-id to a journey test.
                    AuthorRow(
                        identity = person,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenProfile(person.pubky) }
                            .testTag("follow_row_$index")
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    )
                }
                // The list is filtered to Loopky accounts, so it is routinely shorter than the
                // follow count another Pubky app shows for the same person. Said once, at the
                // bottom, rather than as a banner over a list that is usually unsurprising.
                item {
                    Text(
                        text = stringResource(R.string.follow_list_loopky_only),
                        fontSize = 12.sp,
                        color = colors.foregroundMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyFollowList(source: FollowSource, isSelf: Boolean) {
    val colors = LoopkyTheme.colors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            // Second person only when the graph is yours — this screen is now also reachable
            // from someone else's profile.
            text = stringResource(
                when {
                    source == FollowSource.FOLLOWING && isSelf -> R.string.follow_list_empty_following
                    source == FollowSource.FOLLOWING -> R.string.follow_list_empty_following_other
                    isSelf -> R.string.follow_list_empty_followers
                    else -> R.string.follow_list_empty_followers_other
                },
            ),
            fontSize = 14.sp,
            color = colors.foregroundMuted,
            textAlign = TextAlign.Center,
            // On the Text, not the Box around it: the tag has to share a node with real semantics
            // or it never reaches the accessibility tree.
            modifier = Modifier
                .testTag("follow_list_empty")
                .padding(horizontal = 24.dp),
        )
    }
}

@Preview
@Composable
private fun FollowListScreenPreview() {
    LoopkyTheme {
        FollowListScreen(
            state = FollowListUiState(
                source = FollowSource.FOLLOWING,
                isLoading = false,
                people = listOf(
                    PubkyIdentity("abcdef1234567890abcdef", "Grace Hopper", null, null),
                    PubkyIdentity("zyxwvu0987654321zyxwvu", null, null, null),
                ),
            ),
            onBack = {},
            onRetry = {},
            onOpenProfile = {},
        )
    }
}
