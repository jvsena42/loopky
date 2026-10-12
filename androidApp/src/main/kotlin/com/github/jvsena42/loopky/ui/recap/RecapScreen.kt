package com.github.jvsena42.loopky.ui.recap

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.RecapImageFormat
import com.github.jvsena42.loopky.domain.model.RecapShareTarget
import com.github.jvsena42.loopky.domain.model.YearRecap
import com.github.jvsena42.loopky.presentation.recap.RecapEffect
import com.github.jvsena42.loopky.presentation.recap.RecapPostPrompt
import com.github.jvsena42.loopky.presentation.recap.RecapUiState
import com.github.jvsena42.loopky.presentation.recap.RecapViewModel
import com.github.jvsena42.loopky.ui.components.Confetti
import com.github.jvsena42.loopky.ui.components.FoxPlate
import com.github.jvsena42.loopky.ui.components.LoopkyErrorBlock
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.components.rememberReduceMotion
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.layout.screenEdges
import com.github.jvsena42.loopky.ui.layout.windowWidthClass
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun RecapRoute(year: Int, onBack: () -> Unit = {}) {
    val viewModel = koinViewModel<RecapViewModel> { parametersOf(year) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cardText = (state as? RecapUiState.Content)?.recap?.let { recapCardText(it) }
    val chooserTitle = stringResource(R.string.recap_share_title)

    LaunchedEffect(viewModel, cardText) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is RecapEffect.Share -> {
                    val text = cardText ?: return@collectLatest
                    // Off the main thread: a story is two million pixels to draw and compress.
                    val image = withContext(Dispatchers.Default) {
                        val format = effect.target.imageFormat
                        context.recapShareUri(renderRecapImage(context, text, format), format)
                    }
                    context.shareRecap(effect.target, effect.text, image, chooserTitle)
                }
                RecapEffect.Posted -> context.toast(R.string.share_prompt_posted)
                is RecapEffect.PostFailed -> context.toast(R.string.recap_post_failed)
            }
        }
    }

    val captions = (state as? RecapUiState.Content)?.recap?.let { recapCaptions(it) }
    RecapScreen(
        state = state,
        cardText = cardText,
        onBack = onBack,
        onRetry = viewModel::onRetry,
        onShare = { target -> captions?.get(target)?.let { viewModel.onShare(target, it) } },
        onOpenWeb = { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        },
        onPostConfirm = viewModel::onPostConfirm,
        onPostDismiss = viewModel::onPostDismiss,
    )
}

@Suppress("LongParameterList")
@Composable
private fun RecapScreen(
    state: RecapUiState,
    cardText: RecapCardText?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onShare: (RecapShareTarget) -> Unit,
    onOpenWeb: (String) -> Unit,
    onPostConfirm: () -> Unit,
    onPostDismiss: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    // The recap itself sits on the accent ground and takes the on-accent tokens throughout. The
    // loading and error blocks are the shared ones, drawn in the surface family, so they keep the
    // ordinary screen ground rather than being repainted for one screen.
    val onAccent = state is RecapUiState.Content || state is RecapUiState.Empty
    val ground = if (onAccent) {
        Brush.linearGradient(listOf(colors.accentPrimary, colors.accentSecondary))
    } else {
        SolidColor(colors.surfacePrimary)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ground)
            .windowInsetsPadding(WindowInsets.screenEdges),
    ) {
        when (state) {
            RecapUiState.Loading -> LoopkyLoadingScreen(message = stringResource(R.string.recap_loading))
            is RecapUiState.Error -> LoopkyErrorBlock(
                reason = state.reason,
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.Center),
            )
            is RecapUiState.Empty -> RecapMessagePage(
                emoji = "🌱",
                title = stringResource(R.string.recap_empty_title),
                body = stringResource(R.string.recap_empty_body, state.year),
                modifier = Modifier.testTag("recap_empty"),
            )
            is RecapUiState.Content -> if (cardText != null) {
                RecapPager(state = state, cardText = cardText, onShare = onShare, onOpenWeb = onOpenWeb)
            }
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .testTag("recap_close"),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.recap_close),
                tint = if (onAccent) colors.foregroundOnAccent else colors.foregroundPrimary,
            )
        }
    }
    (state as? RecapUiState.Content)?.pubkyPrompt?.let { prompt ->
        RecapPostDialog(prompt = prompt, onConfirm = onPostConfirm, onDismiss = onPostDismiss)
    }
}

@Composable
private fun RecapPager(
    state: RecapUiState.Content,
    cardText: RecapCardText,
    onShare: (RecapShareTarget) -> Unit,
    onOpenWeb: (String) -> Unit,
) {
    val recap = state.recap
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val colors = LoopkyTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        PageDots(current = pagerState.currentPage, modifier = Modifier.padding(top = 20.dp))
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .testTag("recap_pager"),
        ) { page ->
            when (page) {
                PAGE_INTRO -> RecapMessagePage(
                    title = stringResource(R.string.recap_title, recap.year),
                    body = stringResource(R.string.recap_intro_body),
                    fox = true,
                )
                PAGE_CARDS -> RecapMessagePage(
                    emoji = "📚",
                    title = pluralStringResource(R.plurals.recap_stat_cards_studied, recap.cardsStudied, recap.cardsStudied),
                    body = pluralStringResource(
                        R.plurals.recap_stat_cards_mastered,
                        recap.cardsMastered,
                        recap.cardsMastered,
                    ).takeIf { recap.cardsMastered > 0 },
                )
                PAGE_DECKS -> RecapMessagePage(
                    emoji = recap.topDeck?.coverEmoji ?: "🗂️",
                    title = pluralStringResource(R.plurals.recap_stat_decks_studied, recap.decksStudied, recap.decksStudied),
                    body = listOfNotNull(
                        recap.topDeck?.let { stringResource(R.string.recap_top_deck, it.title) },
                        createdLine(recap),
                    ).joinToString("\n").ifEmpty { null },
                )
                PAGE_PERSONA -> PersonaPage(recap)
                else -> SummaryPage(state = state, cardText = cardText, onShare = onShare, onOpenWeb = onOpenWeb)
            }
        }
        if (pagerState.currentPage < PAGE_COUNT - 1) {
            Button(
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.foregroundOnAccent,
                    contentColor = colors.accentPrimary,
                ),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 24.dp)
                    .widthIn(min = 200.dp)
                    .height(52.dp)
                    .testTag("recap_next"),
            ) {
                Text(stringResource(R.string.recap_next), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
    if (pagerState.currentPage == PAGE_COUNT - 1) {
        Confetti(
            colors = listOf(colors.foregroundOnAccent, colors.srsGood, colors.srsEasy, colors.srsHard),
            reduceMotion = reduceMotion,
        )
    }
}

@Composable
private fun PageDots(current: Int, modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(PAGE_COUNT) { index ->
            Box(
                modifier = Modifier
                    .size(width = if (index == current) 22.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (index == current) colors.foregroundOnAccent else colors.foregroundOnAccentMuted.copy(alpha = 0.5f)),
            )
        }
    }
}

@Composable
private fun RecapMessagePage(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    emoji: String? = null,
    fox: Boolean = false,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .contentPane(PaneWidth.Focused)
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (fox) {
            FoxPlate(
                size = 96.dp,
                shape = RoundedCornerShape(28.dp),
                glyphSize = 52.sp,
                containerColor = colors.foregroundOnAccent,
            )
        }
        emoji?.let { Text(text = it, fontSize = 64.sp) }
        Text(
            text = title,
            color = colors.foregroundOnAccent,
            fontSize = 38.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        body?.let {
            Text(
                text = it,
                color = colors.foregroundOnAccentMuted,
                fontSize = 18.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.W600,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonaPage(recap: YearRecap) {
    val colors = LoopkyTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .contentPane(PaneWidth.Focused)
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = recap.persona.emoji(), fontSize = 64.sp)
        Text(
            text = stringResource(recap.persona.titleRes()),
            color = colors.foregroundOnAccent,
            fontSize = 38.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(recap.persona.bodyRes()),
            color = colors.foregroundOnAccentMuted,
            fontSize = 18.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.W600,
            textAlign = TextAlign.Center,
        )
        val languages = recap.languages.map { languageName(it, locale) }
        if (languages.isNotEmpty()) PillGroup(stringResource(R.string.recap_languages_title), languages)
        if (recap.topics.isNotEmpty()) PillGroup(stringResource(R.string.recap_topics_title), recap.topics)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PillGroup(title: String, labels: List<String>) {
    val colors = LoopkyTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, color = colors.foregroundOnAccentMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            labels.forEach { label ->
                Text(
                    text = label,
                    color = colors.accentPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.foregroundOnAccent)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * The picture that will be shared, and where it can go. At expanded width the two sit side by
 * side: stacked on a landscape tablet the image alone takes the whole height and the share row
 * falls below the fold.
 */
@Composable
private fun SummaryPage(
    state: RecapUiState.Content,
    cardText: RecapCardText,
    onShare: (RecapShareTarget) -> Unit,
    onOpenWeb: (String) -> Unit,
) {
    val context = LocalContext.current
    val preview by produceState<Bitmap?>(initialValue = null, cardText) {
        value = withContext(Dispatchers.Default) { renderRecapImage(context, cardText, RecapImageFormat.Square) }
    }
    val image: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier = modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(26.dp))
                .testTag("recap_card"),
        ) {
            preview?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = cardText.headline, modifier = Modifier.fillMaxSize())
            }
        }
    }
    if (windowWidthClass().isExpanded) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .contentPane(PaneWidth.Wide)
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            image(Modifier.weight(1f, fill = false).widthIn(max = 460.dp))
            ShareColumn(state.webUrl, onShare, onOpenWeb, Modifier.weight(1f).widthIn(max = 420.dp))
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .contentPane(PaneWidth.Focused)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            image(Modifier.fillMaxWidth().widthIn(max = 360.dp))
            ShareColumn(state.webUrl, onShare, onOpenWeb)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareColumn(
    webUrl: String,
    onShare: (RecapShareTarget) -> Unit,
    onOpenWeb: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.recap_share_title),
            color = colors.foregroundOnAccent,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RecapShareTarget.entries.forEach { target ->
                Button(
                    onClick = { onShare(target) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.foregroundOnAccent,
                        contentColor = colors.accentPrimary,
                    ),
                    modifier = Modifier.testTag("recap_share_${target.name.lowercase()}"),
                ) {
                    // Decorative: the label beside it already names the network.
                    ShareTargetMark(target)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(target.labelRes()), fontWeight = FontWeight.Bold)
                }
            }
        }
        TextButton(onClick = { onOpenWeb(webUrl) }, modifier = Modifier.testTag("recap_web_link")) {
            Text(
                text = stringResource(R.string.recap_web_link),
                color = colors.foregroundOnAccent,
                fontWeight = FontWeight.W600,
                textDecoration = TextDecoration.Underline,
            )
        }
    }
}

/**
 * The network's mark in the network's own colour. Decorative: the label beside it names it.
 *
 * Pubky is the one drawn on a plate. Its brand is lime on black, and the lime as bare ink on this
 * white pill is 1.3:1 — so it gets the black disc pubky.app itself sets it on.
 */
@Composable
private fun ShareTargetMark(target: RecapShareTarget) {
    if (target == RecapShareTarget.Pubky) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = target.icon(),
                contentDescription = null,
                tint = Color(PUBKY_LIME),
                modifier = Modifier.size(13.dp),
            )
        }
    } else {
        Icon(
            painter = target.icon(),
            contentDescription = null,
            tint = target.brandColor() ?: LocalContentColor.current,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RecapPostDialog(prompt: RecapPostPrompt, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LoopkyTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceCard,
        // Its own window, so the root's testTagsAsResourceId does not reach it.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = {
            Text(
                text = stringResource(R.string.recap_post_title),
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            // The post verbatim: RecapPostPrompt.preview is the string postRecap writes.
            Text(
                text = prompt.preview,
                color = colors.foregroundPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .testTag("recap_post_preview")
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceSecondary)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !prompt.isPosting, modifier = Modifier.testTag("recap_post_confirm")) {
                Text(stringResource(R.string.share_prompt_confirm), color = colors.accentPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !prompt.isPosting, modifier = Modifier.testTag("recap_post_dismiss")) {
                Text(stringResource(R.string.share_prompt_dismiss), color = colors.foregroundMuted)
            }
        },
    )
}

private const val PAGE_COUNT = 5
private const val PAGE_INTRO = 0
private const val PAGE_CARDS = 1
private const val PAGE_DECKS = 2
private const val PAGE_PERSONA = 3
