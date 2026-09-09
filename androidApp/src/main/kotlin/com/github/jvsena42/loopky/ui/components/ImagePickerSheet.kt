package com.github.jvsena42.loopky.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.data.unsplash.UNSPLASH_DEVELOPER_URL
import com.github.jvsena42.loopky.data.unsplash.UNSPLASH_HOME_URL
import com.github.jvsena42.loopky.data.unsplash.UnsplashError
import com.github.jvsena42.loopky.data.unsplash.UnsplashPhoto
import com.github.jvsena42.loopky.domain.model.ImageLink
import com.github.jvsena42.loopky.platform.MediaProcessor
import com.github.jvsena42.loopky.presentation.media.ImageSheetViewModel
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.ClipboardContent
import com.github.jvsena42.loopky.ui.util.openUrl
import com.github.jvsena42.loopky.ui.util.readClipboard
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Reusable bottom sheet for choosing an image — web search (Unsplash) + a 3-column grid, plus a
 * "From gallery" button using the system photo picker (no storage permission) and a Paste button
 * for an image address already on the clipboard. Backs both the card-image sheet (cEXuT) and the
 * cover sheet (OQ2QL).
 *
 * The search field doubles as the address field: text that parses as an [ImageLink] takes the
 * grid's place with a preview of itself. Which is why **Done is gated on that preview loading**
 * for a link, where a grid photo needs only to be highlighted. The load runs through the same
 * Coil stack that will later draw the card, so an address that cannot be shown here — a results
 * page copied instead of an image, a host that refuses to serve the app — is one that would have
 * been saved as a permanently blank card face.
 *
 * A picked image commits immediately, as the gallery pick always has; only an address waits for
 * Done, because only an address can be wrong in a way the user cannot see.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Suppress("CyclomaticComplexMethod", "LongMethod") // Single sheet; grid/gallery/states read top-to-bottom.
@Composable
fun ImagePickerSheet(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    onSelected: (ImageSelection) -> Unit,
    /** Opens Settings on the Unsplash key row — the way out of a key-related failure. */
    onOpenSettings: () -> Unit = {},
) {
    val colors = LoopkyTheme.colors
    val viewModel = koinViewModel<ImageSheetViewModel>()
    val mediaProcessor = koinInject<MediaProcessor>()

    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedPhoto = state.selectedPhoto
    val link = state.link

    // Keyed on the link: a new address is a new load, and the last one's verdict must not stand.
    var linkPreview by remember(link) { mutableStateOf(LinkPreview.Loading) }
    // The one line of feedback the sheet gives about a paste or a picked file, as a string res.
    var notice by remember { mutableStateOf<Int?>(null) }

    // Reading someone else's clipboard provider and compressing what comes back are both slow
    // enough to be seen. Without this the sheet looks frozen and gets tapped again.
    var isBusy by remember { mutableStateOf(false) }

    // Compression can fail on bytes that are not a decodable image — a clipboard blob wearing an
    // image mime type, a truncated download. It throws, and an uncaught throw here takes the app
    // down, so the failure is reported in the sheet instead.
    suspend fun commitBytes(raw: ByteArray) {
        val processed = runSuspendCatching { mediaProcessor.compressImage(raw) }.getOrNull()
        if (processed == null) {
            notice = R.string.image_sheet_image_unreadable
        } else {
            onSelected(ImageSelection.Gallery(processed.bytes, processed.mime))
        }
    }

    // The ViewModel is the screen's, not the sheet's: without this the next sheet opens holding
    // the last one's pick.
    LaunchedEffect(Unit) { viewModel.onSheetOpened() }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isBusy = true
                try {
                    val raw = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                            .getOrNull()
                    }
                    if (raw == null) notice = R.string.image_sheet_image_unreadable else commitBytes(raw)
                } finally {
                    isBusy = false
                }
            }
        }
    }

    fun onPaste() {
        if (isBusy) return
        notice = null
        scope.launch {
            isBusy = true
            try {
                when (val clip = context.readClipboard()) {
                    // Not necessarily a link: pasted words are a perfectly good search, and the
                    // field sorts out which it is.
                    is ClipboardContent.Text -> viewModel.onQueryChange(clip.text)
                    // An image copied as bytes is a whole path of its own and is not taken yet;
                    // say so, and point at the two routes that do work.
                    ClipboardContent.ImageClip -> notice = R.string.image_sheet_paste_image_clip
                    ClipboardContent.Empty -> notice = R.string.image_sheet_paste_empty
                }
            } finally {
                isBusy = false
            }
        }
    }

    fun onDone() {
        notice = null
        when (link) {
            is ImageLink.Remote -> onSelected(ImageSelection.Web(link.url))
            is ImageLink.Inline -> scope.launch { commitBytes(link.bytes) }
            null -> selectedPhoto?.let {
                // Unsplash counts this as a "download"; report it before we hand the
                // pick back and the sheet goes away.
                viewModel.onPhotoUsed()
                onSelected(ImageSelection.Web(it.fullUrl))
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceSecondary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { testTagsAsResourceId = true }
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.W800, color = colors.foregroundPrimary)
                Button(
                    onClick = ::onDone,
                    enabled = if (link != null) linkPreview == LinkPreview.Ready else selectedPhoto != null,
                    modifier = Modifier.testTag("image_sheet_done"),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accentPrimary,
                        contentColor = colors.foregroundOnAccent,
                        disabledContainerColor = colors.borderSubtle,
                        disabledContentColor = colors.foregroundMuted,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.image_sheet_done),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            subtitle?.let {
                Text(text = it, fontSize = 13.sp, color = colors.foregroundSecondary)
            }

            // Search bar
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("image_search_input"),
                placeholder = { Text(stringResource(R.string.image_sheet_search_placeholder), color = colors.foregroundMuted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = colors.foregroundMuted) },
                trailingIcon = if (link == null) {
                    null
                } else {
                    {
                        IconButton(
                            onClick = viewModel::onLinkCleared,
                            modifier = Modifier.testTag("image_link_clear"),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.image_sheet_link_clear),
                                tint = colors.foregroundMuted,
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accentPrimary,
                    unfocusedBorderColor = colors.borderSubtle,
                    cursorColor = colors.accentPrimary,
                ),
            )

            // From gallery / Paste. Paste is how an address copied off a web page — Chrome's
            // "Copy image address" — reaches the field, which then takes it as a link.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SourceButton(
                    icon = Icons.Default.Image,
                    label = stringResource(R.string.image_sheet_from_gallery),
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    busy = isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("image_pick_gallery"),
                )
                SourceButton(
                    icon = Icons.Default.ContentPaste,
                    label = stringResource(R.string.image_sheet_paste),
                    onClick = ::onPaste,
                    busy = isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("image_paste"),
                )
            }

            notice?.let {
                Text(
                    text = stringResource(it),
                    fontSize = 12.sp,
                    color = colors.foregroundSecondary,
                    modifier = Modifier.testTag("image_sheet_notice"),
                )
            }

            // Web image grid
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
                val error = state.error
                when {
                    // Ahead of everything else: with an address in the field, a grid of search
                    // results is answering a question the user has stopped asking.
                    link != null -> LinkPreviewPane(
                        link = link,
                        preview = linkPreview,
                        onPreviewChange = { linkPreview = it },
                    )

                    state.isLoading && state.photos.isEmpty() ->
                        CircularProgressIndicator(
                            color = colors.accentPrimary,
                            modifier = Modifier.align(Alignment.Center),
                        )

                    // Takes the grid's place rather than sitting under it: a failed search used to
                    // render "No images found" *and* the raw error text, two answers to one query.
                    error != null -> UnsplashErrorPanel(
                        error = error,
                        onGetKey = { context.openUrl(UNSPLASH_DEVELOPER_URL) },
                        onAddKey = {
                            onDismiss()
                            onOpenSettings()
                        },
                    )

                    state.photos.isEmpty() ->
                        CenteredHint(stringResource(R.string.image_sheet_no_results))

                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("image_grid"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(state.photos, key = { _, p -> p.id }) { index, photo ->
                            PhotoGridCell(
                                photo = photo,
                                selected = selectedPhoto?.id == photo.id,
                                onClick = { viewModel.onPhotoSelected(photo) },
                                modifier = Modifier
                                    .testTag(if (index == 0) "image_grid_cell" else "image_grid_cell_$index"),
                            )
                        }
                    }
                }
            }

            // Required by the Unsplash API guidelines whenever their photos are on screen — and
            // wrong when they are not: crediting a photographer under someone's pasted link
            // attributes an image that is not theirs.
            if (link == null && state.photos.isNotEmpty()) {
                UnsplashCredit(photo = selectedPhoto, onOpenUrl = context::openUrl)
            }
        }
    }
}

/** Whether the address in the field has proved itself an image yet. */
private enum class LinkPreview { Loading, Ready, Failed }

/**
 * The pasted address, drawn. This is the check as much as the preview: it loads through the same
 * Coil stack that will later draw the card, so whatever fails here would have failed there.
 */
@Composable
private fun LinkPreviewPane(
    link: ImageLink,
    preview: LinkPreview,
    onPreviewChange: (LinkPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceCard),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = when (link) {
                is ImageLink.Remote -> link.url
                is ImageLink.Inline -> link.bytes
            },
            contentDescription = stringResource(R.string.image_sheet_link_preview),
            // Fit, not Crop: this is the image being vouched for, so it is shown whole.
            contentScale = ContentScale.Fit,
            onState = { loadState ->
                onPreviewChange(
                    when (loadState) {
                        is AsyncImagePainter.State.Success -> LinkPreview.Ready
                        is AsyncImagePainter.State.Error -> LinkPreview.Failed
                        else -> LinkPreview.Loading
                    },
                )
            },
            modifier = Modifier
                .matchParentSize()
                .testTag("image_link_preview"),
        )
        when (preview) {
            LinkPreview.Loading -> CircularProgressIndicator(color = colors.accentPrimary)
            LinkPreview.Failed -> Text(
                text = stringResource(R.string.image_sheet_link_failed),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = colors.foregroundSecondary,
                modifier = Modifier
                    .padding(16.dp)
                    .testTag("image_link_error"),
            )

            LinkPreview.Ready -> Unit
        }
    }
}

/**
 * One of the two "bring your own image" buttons under the field. [busy] swaps the icon for a
 * spinner and stops taking taps — reading another app's clipboard provider and compressing what
 * comes back both take long enough that a dead-looking button gets pressed again.
 */
@Composable
private fun SourceButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(12.dp))
            .background(colors.surfaceCard)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                color = colors.accentPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Icon(icon, null, tint = colors.foregroundSecondary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.W600,
            color = if (busy) colors.foregroundMuted else colors.foregroundSecondary,
        )
    }
}

/**
 * One grid cell: the thumbnail with the photographer's name in a bottom scrim. The name is a
 * licensing requirement, not decoration — it used to live only in `contentDescription`, where no
 * sighted user could see it. It is deliberately not tappable; the links live in [UnsplashCredit]
 * below the grid so a tap on a ~110dp cell unambiguously means "select this photo".
 */
@Composable
private fun PhotoGridCell(
    photo: UnsplashPhoto,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) colors.accentPrimary else colors.borderSubtle,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = photo.thumbUrl,
            // The visible name labels the cell for screen readers; only fall back when it's blank.
            contentDescription = photo.authorName.ifBlank { stringResource(R.string.image_sheet_photo) },
            // Without this AsyncImage defaults to ContentScale.Fit, which
            // letterboxes inside the square and makes the grid look ragged.
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        if (photo.authorName.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .fillMaxHeight(0.45f)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))),
                    ),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = photo.authorName,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.W600,
                    color = colors.foregroundOnAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * The linked half of the attribution: "Photo by X on Unsplash" once something is picked,
 * "Photos from Unsplash" before that. Both links carry the referral params the guidelines require
 * (already baked into [UnsplashPhoto.authorProfileUrl] and [UNSPLASH_HOME_URL]).
 */
@Composable
private fun UnsplashCredit(
    photo: UnsplashPhoto?,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val unsplash = stringResource(R.string.image_sheet_credit_unsplash)
    val unknownAuthor = stringResource(R.string.image_sheet_credit_unknown_author)
    val author = photo?.authorName?.ifBlank { unknownAuthor }
    val text = if (author == null) {
        stringResource(R.string.image_sheet_credit_platform, unsplash)
    } else {
        stringResource(R.string.image_sheet_credit, author, unsplash)
    }
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = colors.accentPrimary, textDecoration = TextDecoration.Underline),
    )
    val annotated = buildAnnotatedString {
        append(text)
        val authorUrl = photo?.authorProfileUrl.orEmpty()
        val authorStart = if (author != null && authorUrl.isNotBlank()) text.indexOf(author) else -1
        if (authorStart >= 0 && author != null) {
            addLink(
                LinkAnnotation.Url(authorUrl, linkStyles) { onOpenUrl(authorUrl) },
                authorStart,
                authorStart + author.length,
            )
        }
        // Last occurrence: a photographer literally named "Unsplash" would otherwise steal it.
        val platformStart = text.lastIndexOf(unsplash)
        if (platformStart >= 0) {
            addLink(
                LinkAnnotation.Url(UNSPLASH_HOME_URL, linkStyles) { onOpenUrl(UNSPLASH_HOME_URL) },
                platformStart,
                platformStart + unsplash.length,
            )
        }
    }
    Text(
        text = annotated,
        fontSize = 11.sp,
        color = colors.foregroundMuted,
        modifier = modifier.testTag("image_credit"),
    )
}

/**
 * What replaces the grid when a search fails. Three of the four errors are the user's key, so they
 * get a way to fix it; [UnsplashError.Unavailable] is not, so it gets no misleading "Add key"
 * button — only the "From gallery" row above, which works regardless.
 */
@Composable
private fun UnsplashErrorPanel(
    error: UnsplashError,
    onGetKey: () -> Unit,
    onAddKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val isKeyProblem = error != UnsplashError.Unavailable
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceCard)
            .padding(16.dp)
            .testTag("image_sheet_key_panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(error.sheetMessage()),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = colors.foregroundSecondary,
        )
        if (isKeyProblem) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onGetKey,
                    modifier = Modifier.testTag("image_sheet_get_key"),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.surfaceSecondary,
                        contentColor = colors.foregroundPrimary,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.image_sheet_get_key),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Button(
                    onClick = onAddKey,
                    modifier = Modifier.testTag("image_sheet_add_key"),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accentPrimary,
                        contentColor = colors.foregroundOnAccent,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.image_sheet_add_key),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

private fun UnsplashError.sheetMessage(): Int = when (this) {
    UnsplashError.MissingKey -> R.string.image_sheet_error_missing_key
    UnsplashError.InvalidKey -> R.string.image_sheet_error_invalid_key
    UnsplashError.RateLimited -> R.string.image_sheet_error_rate_limited
    UnsplashError.Unavailable -> R.string.image_sheet_error_unavailable
}

@Composable
private fun CenteredHint(text: String) {
    val colors = LoopkyTheme.colors
    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = colors.foregroundMuted)
    }
}
