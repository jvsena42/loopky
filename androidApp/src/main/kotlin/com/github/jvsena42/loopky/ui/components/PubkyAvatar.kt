package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.avatarDisplayUrl
import com.github.jvsena42.loopky.domain.model.avatarInitial
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import org.koin.compose.koinInject

/**
 * Someone's avatar: their picture when they have set one, otherwise the initial [AuthorRow] and
 * the profile screen already fall back to.
 *
 * The initial is drawn *underneath* the image rather than instead of it, so it also covers the
 * moment while the image loads and the case where it fails — an avatar slot is never blank.
 */
@Composable
fun PubkyAvatar(
    identity: PubkyIdentity,
    modifier: Modifier = Modifier,
    size: Dp = DEFAULT_SIZE,
) {
    val colors = LoopkyTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .background(colors.accentSecondarySoft),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = identity.avatarInitial.toString(),
            // Keeps the initial proportional as callers scale the avatar up for a people strip.
            fontSize = (size.value * INITIAL_RATIO).sp,
            fontWeight = FontWeight.W800,
            color = colors.accentSecondary,
        )
        // Not identity.avatarUrl: that is a `pubky://` file URI Coil cannot fetch, so every
        // avatar silently failed to the initial below. See [avatarDisplayUrl]. Resolved against
        // the injected indexer rather than a constant so it follows the build type (#42) —
        // guarded so a @Preview, which has no Koin graph, still renders the initial.
        val pictureUrl = identity.avatarUrl?.takeIf { it.isNotBlank() }
            ?.let { identity.avatarDisplayUrl(koinInject<NexusClient>().baseUrl) }
        if (pictureUrl != null) {
            AsyncImage(
                model = pictureUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Preview
@Composable
private fun PubkyAvatarPreview() {
    LoopkyTheme {
        Row(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(16.dp),
        ) {
            PubkyAvatar(
                identity = PubkyIdentity("ada1xqz9uvwxyz", "Ada Lovelace", null, null),
            )
            PubkyAvatar(
                identity = PubkyIdentity("byron7yt2abcdef", null, null, null),
                size = 56.dp,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

private val DEFAULT_SIZE = 32.dp

/** 14sp on the 32dp avatar AuthorRow shipped with. */
private const val INITIAL_RATIO = 0.4375f
