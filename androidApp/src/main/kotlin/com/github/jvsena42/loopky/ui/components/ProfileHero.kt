package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.label
import com.github.jvsena42.loopky.ui.util.truncatedPubky

/**
 * The centered identity block at the top of a profile screen: glowing avatar, name, the pubky
 * itself, and the bio. Someone else's profile is a person, not a list row — this is the same hero
 * the signed-in user's own profile uses, so both screens read as one product.
 *
 * The pubky chip is the copy affordance rather than a separate pill, so the key is shown *and*
 * copyable from one control.
 */
@Composable
fun ProfileHero(
    identity: PubkyIdentity,
    modifier: Modifier = Modifier,
    /** Adds a "You" badge beside the name — ownership never replaces the identity. */
    isOwned: Boolean = false,
    onPubkyClick: (() -> Unit)? = null,
    /**
     * Puts a camera badge on the avatar. Null on someone else's profile: this is the only thing
     * in the hero that is an action rather than a fact.
     */
    onEditAvatar: (() -> Unit)? = null,
) {
    val colors = LoopkyTheme.colors

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The badge sits outside the shadowed box rather than inside it: `Modifier.shadow` clips
        // its content to the shape it draws, so a corner-aligned child is cut into a crescent by
        // the circle. The glow itself lives on that box because PubkyAvatar clips itself, and a
        // shadow set inside it would be clipped away with everything else.
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier.shadow(
                    elevation = AVATAR_GLOW_ELEVATION,
                    shape = CircleShape,
                    ambientColor = colors.shadowAccent,
                    spotColor = colors.shadowAccent,
                ),
            ) {
                PubkyAvatar(identity = identity, size = AVATAR_SIZE)
            }

            if (onEditAvatar != null) {
                // A real button rather than a tap target on the picture: an avatar that reacts to
                // a tap is a lightbox everywhere else in the app, and a bare clickable announces
                // nothing to a screen reader.
                FilledIconButton(
                    onClick = onEditAvatar,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(AVATAR_BADGE_SIZE)
                        .testTag("profile_edit_avatar"),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colors.accentPrimary,
                        contentColor = colors.foregroundOnAccent,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = stringResource(
                            R.string.profile_edit_avatar_content_description,
                        ),
                        modifier = Modifier.size(AVATAR_BADGE_ICON_SIZE),
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = identity.label(),
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (isOwned) {
                Spacer(modifier = Modifier.width(8.dp))
                YouBadge()
            }
        }

        AssistChip(
            onClick = { onPubkyClick?.invoke() },
            enabled = onPubkyClick != null,
            label = {
                Text(
                    text = truncatedPubky(identity.pubky),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.friend_profile_copy_pubky),
                    modifier = Modifier.size(12.dp),
                )
            },
            shape = RoundedCornerShape(50),
            colors = AssistChipDefaults.assistChipColors(
                containerColor = colors.surfaceSecondary,
                labelColor = colors.foregroundMuted,
                leadingIconContentColor = colors.foregroundMuted,
                disabledContainerColor = colors.surfaceSecondary,
                disabledLabelColor = colors.foregroundMuted,
                disabledLeadingIconContentColor = colors.foregroundMuted,
            ),
            border = null,
        )

        val bio = identity.bio
        if (!bio.isNullOrBlank()) {
            Text(
                text = bio,
                fontSize = 13.sp,
                color = colors.foregroundSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp,
                // Bounded in both directions: the width alone let a long bio wrap forever and
                // push the stats and the follow button off the screen.
                maxLines = BIO_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(BIO_WIDTH),
            )
        }
    }
}

@Preview
@Composable
private fun ProfileHeroPreview() {
    LoopkyTheme {
        Column(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ProfileHero(
                identity = PubkyIdentity(
                    pubky = "abcdef1234567890abcdef",
                    displayName = "Grace Hopper",
                    avatarUrl = null,
                    bio = "Compiler pioneer. Decks on debugging and naval history.",
                ),
                onPubkyClick = {},
            )
            ProfileHero(
                identity = PubkyIdentity("you9xqz1ghijklmnop", null, null, null),
                isOwned = true,
                onEditAvatar = {},
            )
        }
    }
}

private val AVATAR_SIZE = 96.dp
private val AVATAR_GLOW_ELEVATION = 24.dp
private val AVATAR_BADGE_SIZE = 32.dp
private val AVATAR_BADGE_ICON_SIZE = 16.dp
private val BIO_WIDTH = 280.dp

/** Enough for a sentence or two; past that the hero stops being a header. */
private const val BIO_MAX_LINES = 4
