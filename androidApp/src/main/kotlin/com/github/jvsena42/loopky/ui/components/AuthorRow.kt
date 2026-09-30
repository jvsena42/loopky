package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.label
import com.github.jvsena42.loopky.ui.util.truncatedPubky

@Composable
fun AuthorRow(
    identity: PubkyIdentity,
    modifier: Modifier = Modifier,
    /** Adds a "You" badge beside the name — ownership never replaces the identity. */
    isOwned: Boolean = false,
    isFollowing: Boolean = false,
    /** Disables + dims the pill while a follow/unfollow request is in flight. */
    isFollowPending: Boolean = false,
    /**
     * Follows/unfollows this person. Null means no pill at all: a screen that cannot act on a
     * follow must not draw a button that looks like it can, and every caller left this defaulted
     * to an empty lambda, so the pill was drawn everywhere and worked nowhere.
     */
    onFollowClick: (() -> Unit)? = null,
    /** Opens this person's profile. Left null where there is nowhere to go — the row stays inert. */
    onNameClick: (() -> Unit)? = null,
) {
    val colors = LoopkyTheme.colors
    val pillShape = RoundedCornerShape(50)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PubkyAvatar(identity = identity)

        Spacer(modifier = Modifier.width(10.dp))

        // Name column — the same name every other screen shows for this person, with the pubky
        // truncated underneath it and ownership added as a badge rather than swapped in.
        // Tapping it opens them, so the author of the deck you are reading is a way to reach them
        // rather than a dead label.
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onNameClick != null) Modifier.clickable(onClick = onNameClick) else Modifier,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = identity.label(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W700,
                    color = colors.foregroundPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isOwned) {
                    Spacer(modifier = Modifier.width(6.dp))
                    YouBadge()
                }
            }
            Text(
                text = truncatedPubky(identity.pubky),
                fontSize = 11.sp,
                color = colors.foregroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Follow button — hidden for your own deck, and wherever the screen cannot act on it.
        if (!isOwned && onFollowClick != null) {
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(pillShape)
                    .background(
                        if (isFollowing) colors.accentSecondarySoft else colors.accentSecondary,
                    )
                    .clickable(enabled = !isFollowPending, onClick = onFollowClick)
                    .alpha(if (isFollowPending) PENDING_ALPHA else 1f)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isFollowing) {
                        stringResource(R.string.component_author_row_following)
                    } else {
                        stringResource(R.string.component_author_row_follow)
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W700,
                    color = if (isFollowing) colors.accentSecondary else colors.foregroundOnAccent,
                )
            }
        }
    }
}

@Preview
@Composable
private fun AuthorRowPreview() {
    LoopkyTheme {
        Column(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(16.dp),
        ) {
            AuthorRow(
                identity = previewIdentity("ada1xqz9uvwxyz", "Ada Lovelace"),
                isFollowing = false,
                onFollowClick = {},
            )
            Spacer(modifier = Modifier.size(12.dp))
            AuthorRow(
                identity = previewIdentity("byron7yt2abcdef", name = null),
                isFollowing = true,
                onFollowClick = {},
            )
            Spacer(modifier = Modifier.size(12.dp))
            AuthorRow(
                identity = previewIdentity("you9xqz1ghijkl", "Cosmic-Crystal-Panda"),
                isOwned = true,
            )
            Spacer(modifier = Modifier.size(12.dp))
            AuthorRow(
                identity = previewIdentity("grace2ab7cdefgh", "Grace Hopper"),
                onNameClick = {},
            )
        }
    }
}

private fun previewIdentity(pubky: String, name: String?) =
    PubkyIdentity(pubky = pubky, displayName = name, avatarUrl = null, bio = null)

private const val PENDING_ALPHA = 0.5f
