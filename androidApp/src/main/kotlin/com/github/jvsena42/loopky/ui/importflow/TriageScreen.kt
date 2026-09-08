package com.github.jvsena42.loopky.ui.importflow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.importflow.TriageCard
import com.github.jvsena42.loopky.presentation.importflow.TriageEffect
import com.github.jvsena42.loopky.presentation.importflow.TriageUiState
import com.github.jvsena42.loopky.presentation.importflow.TriageViewModel
import com.github.jvsena42.loopky.ui.components.rememberReduceMotion
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.abs

@Composable
fun TriageRoute(
    onBack: () -> Unit = {},
    onEditCard: (Int) -> Unit = {},
    onNext: () -> Unit = {},
) {
    val viewModel = koinViewModel<TriageViewModel>()

    // Re-read the draft each time this screen resumes (e.g. after editing a card).
    LaunchedEffect(viewModel) { viewModel.refresh() }

    val currentBack by rememberUpdatedState(onBack)
    val currentEdit by rememberUpdatedState(onEditCard)
    val currentNext by rememberUpdatedState(onNext)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                TriageEffect.NavigateBack -> currentBack()
                is TriageEffect.NavigateEditCard -> currentEdit(effect.rowIndex)
                TriageEffect.NavigatePublish -> currentNext()
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    TriageScreen(
        state = state,
        onBackClick = viewModel::onBackClick,
        onApproveAll = viewModel::onApproveAll,
        onUndo = viewModel::onUndo,
        onDiscard = viewModel::onDiscard,
        onEditClick = viewModel::onEditClick,
        onKeep = viewModel::onKeep,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriageScreen(
    state: TriageUiState,
    onBackClick: () -> Unit,
    onApproveAll: () -> Unit,
    onUndo: () -> Unit,
    onDiscard: () -> Unit,
    onEditClick: () -> Unit,
    onKeep: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()

    // Fling distance / commit threshold derive from screen width — close enough to the
    // card's own width (it fills the column minus horizontal padding) for the gesture feel.
    val screenWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }

    // Latest callbacks captured by the per-card controller below.
    val currentKeep by rememberUpdatedState(onKeep)
    val currentDiscard by rememberUpdatedState(onDiscard)

    // A fresh controller per card resets the drag offset to zero for the incoming card.
    val controller = remember(state.currentIndex, screenWidthPx, reduceMotion) {
        TriageSwipeController(
            scope = scope,
            flingDistance = screenWidthPx * 1.2f,
            threshold = screenWidthPx * 0.25f,
            reduceMotion = reduceMotion,
            onKeep = { currentKeep() },
            onDiscard = { currentDiscard() },
        )
    }

    Scaffold(
        containerColor = colors.surfaceSecondary,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.triage_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.publish_back),
                            tint = colors.foregroundPrimary,
                        )
                    }
                },
                actions = {
                    // Persistent, not just a snackbar: the point is recovering work you spent
                    // time on (an image attached in the triage editor), which outlives a toast.
                    IconButton(
                        onClick = onUndo,
                        enabled = state.canUndo,
                        modifier = Modifier.testTag("triage_undo"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = stringResource(R.string.triage_undo),
                            tint = if (state.canUndo) colors.foregroundPrimary else colors.borderSubtle,
                        )
                    }
                    TextButton(
                        onClick = onApproveAll,
                        modifier = Modifier.testTag("triage_approve_all"),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                    ) {
                        Text(
                            text = stringResource(R.string.triage_approve_all),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surfaceSecondary,
                    titleContentColor = colors.foregroundPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .contentPane(PaneWidth.Reading)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Progress + stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.triage_progress, state.currentIndex + 1, state.total),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.foregroundMuted,
                    modifier = Modifier.testTag("triage_progress"),
                )
                Text(
                    text = stringResource(R.string.triage_stats, state.keptCount, state.discardedCount),
                    fontSize = 12.sp,
                    color = colors.foregroundMuted,
                )
            }

            val card = state.currentCard
            if (card != null) {
                TriageCardStack(
                    controller = controller,
                    current = card,
                    next = state.cards.getOrNull(state.currentIndex + 1),
                    reduceMotion = reduceMotion,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }

            state.error?.let { Text(it, fontSize = 13.sp, color = colors.danger) }

            // Action buttons mirror the swipe gestures: discard / edit / keep.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleActionButton(
                    onClick = { controller.swipe(SwipeDirection.Discard) },
                    tag = "triage_discard",
                    background = colors.dangerSoft,
                    iconTint = colors.srsAgain,
                    size = 56,
                ) {
                    Icon(Icons.Default.Close, stringResource(R.string.triage_discard), modifier = Modifier.size(28.dp))
                }
                CircleActionButton(
                    onClick = onEditClick,
                    tag = "triage_edit",
                    background = colors.surfaceCard,
                    iconTint = colors.foregroundSecondary,
                    size = 48,
                ) {
                    Icon(Icons.Default.Edit, stringResource(R.string.triage_edit), modifier = Modifier.size(20.dp))
                }
                CircleActionButton(
                    onClick = { controller.swipe(SwipeDirection.Keep) },
                    tag = "triage_keep",
                    background = colors.srsGood,
                    iconTint = colors.foregroundOnAccent,
                    size = 56,
                ) {
                    Icon(Icons.Default.Check, stringResource(R.string.triage_keep), modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

private enum class SwipeDirection { Keep, Discard }

/**
 * Drives the horizontal drag of the top triage card and the commit/fly-off animation.
 * Recreated per card so [offsetX] (and therefore the gesture) starts clean each time.
 */
private class TriageSwipeController(
    private val scope: CoroutineScope,
    private val flingDistance: Float,
    private val threshold: Float,
    private val reduceMotion: Boolean,
    private val onKeep: () -> Unit,
    private val onDiscard: () -> Unit,
) {
    val offsetX = Animatable(0f)

    // Entrance scale for the incoming card so the hand-off from the peek card isn't jarring.
    val enter = Animatable(if (reduceMotion) 1f else 0.96f)

    /** Drag progress toward the commit threshold: +1 fully right (keep), -1 fully left (discard). */
    val progress: Float get() = (offsetX.value / threshold).coerceIn(-1f, 1f)

    fun onDrag(delta: Float) {
        scope.launch { offsetX.snapTo(offsetX.value + delta) }
    }

    // Past the threshold the drag commits; otherwise the card springs back to centre.
    fun onDragEnd() {
        when {
            offsetX.value > threshold -> swipe(SwipeDirection.Keep)
            offsetX.value < -threshold -> swipe(SwipeDirection.Discard)
            else -> scope.launch {
                offsetX.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            }
        }
    }

    fun swipe(direction: SwipeDirection) {
        scope.launch {
            if (!reduceMotion) {
                val target = if (direction == SwipeDirection.Keep) flingDistance else -flingDistance
                offsetX.animateTo(target, tween(durationMillis = 260, easing = FastOutLinearInEasing))
            }
            if (direction == SwipeDirection.Keep) onKeep() else onDiscard()
        }
    }
}

@Composable
private fun TriageCardStack(
    controller: TriageSwipeController,
    current: TriageCard,
    next: TriageCard?,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors

    LaunchedEffect(controller) {
        if (!reduceMotion) controller.enter.animateTo(1f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
    }

    // The cards size to their content (with a floor) and sit centred in the stack, so a
    // two-word card no longer renders as a mostly-empty full-height rectangle.
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Peek card behind the top one; grows toward full size as the top card is dragged away.
        if (next != null) {
            TriageCardView(
                card = next,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val reveal = abs(controller.progress)
                        val scale = if (reduceMotion) 0.94f else lerp(0.94f, 1f, reveal)
                        scaleX = scale
                        scaleY = scale
                        alpha = if (reduceMotion) 1f else lerp(0.55f, 1f, reveal)
                        translationY = if (reduceMotion) 0f else lerp(24f, 0f, reveal)
                    },
            )
        }

        // Top card: draggable, tilts and flies off on commit. The decision feedback
        // icons live inside this layer so they ride along with the card as it moves.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = controller.offsetX.value
                    rotationZ = controller.progress * 8f
                    val scale = controller.enter.value
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(controller) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount -> controller.onDrag(dragAmount) },
                        onDragEnd = { controller.onDragEnd() },
                        onDragCancel = { controller.onDragEnd() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            TriageCardView(card = current, modifier = Modifier.fillMaxWidth())

            // Approve (right) / disapprove (left) feedback grows in with drag progress.
            SwipeFeedback(
                icon = Icons.Default.Check,
                contentDescription = stringResource(R.string.triage_keep),
                background = colors.srsGood,
                iconTint = colors.foregroundOnAccent,
                progressProvider = { controller.progress.coerceAtLeast(0f) },
            )
            SwipeFeedback(
                icon = Icons.Default.Close,
                contentDescription = stringResource(R.string.triage_discard),
                background = colors.srsAgain,
                iconTint = colors.foregroundOnAccent,
                progressProvider = { (-controller.progress).coerceAtLeast(0f) },
            )
        }
    }
}

@Composable
private fun SwipeFeedback(
    icon: ImageVector,
    contentDescription: String,
    background: Color,
    iconTint: Color,
    progressProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .graphicsLayer {
                val progress = progressProvider()
                alpha = progress
                val scale = lerp(0.6f, 1f, progress)
                scaleX = scale
                scaleY = scale
            }
            .size(96.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = iconTint, modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun TriageCardView(card: TriageCard, modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            // Portrait floor: the card is ~320dp wide on a phone, so a smaller minimum reads
            // as a square rather than a flashcard.
            .heightIn(min = 540.dp)
            .shadow(16.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceCard)
            .testTag("triage_card")
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.triage_front_label),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = colors.foregroundMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = card.front.ifBlank { "—" },
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.foregroundPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("triage_front"),
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.width(40.dp).height(2.dp).background(colors.accentPrimary))
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.triage_back_label),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = colors.foregroundMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = card.back.ifBlank { "—" },
            fontSize = 20.sp,
            color = colors.foregroundSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("triage_back"),
        )
    }
}

@Composable
private fun CircleActionButton(
    onClick: () -> Unit,
    tag: String,
    background: Color,
    iconTint: Color,
    size: Int,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(background)
            .testTag(tag),
        colors = IconButtonDefaults.iconButtonColors(contentColor = iconTint),
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun TriageScreenPreview() {
    LoopkyTheme {
        TriageScreen(
            state = TriageUiState(
                cards = listOf(TriageCard(0, "por favor", "please")),
                currentIndex = 11,
                total = 42,
                keptCount = 11,
                discardedCount = 0,
            ),
            onBackClick = {},
            onApproveAll = {},
            onUndo = {},
            onDiscard = {},
            onEditClick = {},
            onKeep = {},
        )
    }
}
