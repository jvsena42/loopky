package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * One side of a card in the Edit-card screens: a white rounded card holding the
 * text input, with the side label and an "Add image" pill in the header. When an image is set the
 * pill is replaced by a thumbnail preview (tap to change) plus a circular remove button.
 *
 * Shared by the post-publish editor ([com.github.jvsena42.loopky.ui.decks.EditCardScreen]) and the
 * paste/triage editor so both match the design.
 *
 * Two ways in for the thumbnail, because the two callers hold an image at different stages.
 * [imageModel] is anything Coil can decode on its own — a web URL, or raw bytes just picked from
 * the gallery and not uploaded yet. [imageRef] is an image already *stored* on a homeserver, whose
 * bytes have to be fetched before anything can be drawn ([CardMediaImage] does that). Passing only
 * `imageRef?.url` used to lose exactly that case: a saved blob ref carries no URL, so the editor
 * fell back to "Add image" and the card's image looked discarded (#80).
 */
@Composable
fun CardSideEditor(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    imageModel: Any?,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    imageTag: String,
    fieldTag: String,
    modifier: Modifier = Modifier,
    imageRef: MediaRef.Image? = null,
    deckId: String = "",
    /** The *deck's* author, not the signed-in user — see [CardMediaImage]. */
    authorPubky: String = "",
    error: String? = null,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceCard)
            .border(1.5.dp, colors.borderSubtle, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 1.sp,
                color = colors.foregroundMuted,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    imageRef != null -> ImagePreview(onPickImage, onRemoveImage, imageTag) { thumb ->
                        CardMediaImage(
                            image = imageRef,
                            deckId = deckId,
                            authorPubky = authorPubky,
                            modifier = thumb,
                        )
                    }

                    imageModel != null -> ImagePreview(onPickImage, onRemoveImage, imageTag) { thumb ->
                        AsyncImage(
                            model = imageModel,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = thumb,
                        )
                    }

                    else -> AddImagePill(onClick = onPickImage, tag = "${imageTag}_add")
                }
            }
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(fieldTag),
            textStyle = textStyle.copy(color = colors.foregroundPrimary),
            cursorBrush = SolidColor(colors.accentPrimary),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(text = placeholder, style = textStyle, color = colors.foregroundMuted)
                    }
                    inner()
                }
            },
        )

        error?.let { Text(text = it, fontSize = 12.sp, color = colors.danger) }
    }
}

@Composable
private fun AddImagePill(onClick: () -> Unit, tag: String) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier
            .testTag(tag)
            .clip(RoundedCornerShape(50))
            .background(colors.accentPrimarySoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Image, null, tint = colors.accentPrimary, modifier = Modifier.size(12.dp))
        Text(
            text = stringResource(R.string.edit_card_add_image),
            fontSize = 11.sp,
            fontWeight = FontWeight.W700,
            color = colors.accentPrimary,
        )
    }
}

/** The thumbnail-plus-remove chip. [thumbnail] draws the image itself into the modifier it gets. */
@Composable
private fun ImagePreview(
    onChange: () -> Unit,
    onRemove: () -> Unit,
    tag: String,
    thumbnail: @Composable (Modifier) -> Unit,
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceSecondary)
            .padding(PaddingValues(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 8.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        thumbnail(
            Modifier
                .testTag("${tag}_chip")
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.5.dp, colors.accentPrimary, RoundedCornerShape(8.dp))
                .clickable(onClick = onChange),
        )
        Box(
            modifier = Modifier
                .testTag("${tag}_remove")
                .size(24.dp)
                .clip(CircleShape)
                .background(colors.surfaceCard)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, null, tint = colors.foregroundMuted, modifier = Modifier.size(14.dp))
        }
    }
}
