package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The sentinel at the foot of a paged list: asks for the next page as it scrolls into view, and
 * shows the spinner while that page is on its way.
 *
 * A sentinel rather than a scroll-offset listener because a `LazyColumn` only composes what is near
 * the viewport, so *being composed* already means "the reader has reached the end". A listener would
 * have to recompute against the item count on every scroll frame, and would need its own guard for
 * a list whose rows are not a uniform height — the deck grid's are not.
 *
 * [onLoadMore] fires once per composition, not once per frame, and the ViewModel guards the rest —
 * see `SectionState.canLoadMore`.
 */
@Composable
fun LoadMoreFooter(
    isLoading: Boolean,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The lambda changes identity on every recomposition of the caller; keying the effect on it
    // would re-fire the request each time.
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(Unit) { currentOnLoadMore() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("load_more_footer")
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = LoopkyTheme.colors.accentPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
