package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * A deck's cover art, wherever a deck is shown: its image when it has one, [fallback] otherwise.
 *
 * The fallback (an emoji, or the title's initial) is drawn *underneath* the image rather than
 * instead of it, the way [PubkyAvatar] handles a missing picture — so the slot is never blank
 * while a homeserver blob downloads, and it degrades to the fallback if the fetch fails. Without
 * this, every list and grid rendered the emoji only, and a deck with a cover image looked
 * coverless everywhere but its detail screen.
 */
@Composable
fun DeckCover(
    coverImage: MediaRef.Image?,
    deckId: String,
    /** The *deck's* author — a followed deck's cover blob lives on their homeserver, not yours. */
    authorPubky: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    background: Color = LoopkyTheme.colors.accentPrimarySoft,
    fallback: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        fallback()
        if (coverImage != null) {
            CardMediaImage(
                image = coverImage,
                deckId = deckId,
                authorPubky = authorPubky,
                contentScale = ContentScale.Crop,
                showLoadingIndicator = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
