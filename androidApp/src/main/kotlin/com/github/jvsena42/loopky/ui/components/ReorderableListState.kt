package com.github.jvsena42.loopky.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Drag-to-reorder for a [androidx.compose.foundation.lazy.LazyColumn].
 *
 * Works in *key* space rather than index space so the list can mix reorderable rows with
 * headers and footers: only items whose key is in `reorderableKeys` can be dragged or
 * targeted, and [onMove] reports the two keys that should swap places.
 *
 * Create it with [rememberReorderableListState] — the constructor is internal because the
 * auto-scroll pump lives in that composable.
 */
@Stable
class ReorderableListState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val reorderableKeys: () -> Set<Any>,
    private val onMove: (from: Any, to: Any) -> Unit,
) {
    /** Key of the row currently under the finger, or null when nothing is being dragged. */
    var draggingKey: Any? by mutableStateOf(null)
        private set

    private var draggedDistance by mutableFloatStateOf(0f)
    private var initialOffset by mutableIntStateOf(0)
    private var settleJob: Job? = null

    /** Overscroll amounts handed to the auto-scroll pump in [rememberReorderableListState]. */
    internal val scrollChannel = Channel<Float>(Channel.CONFLATED)

    private val draggingItem: LazyListItemInfo?
        get() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingKey }

    /**
     * Vertical translation, in pixels, that keeps the dragged row under the finger. The row's
     * own layout position keeps moving as the list reorders beneath it, so this is the gap
     * between where the finger has taken it and where the list has since placed it.
     */
    val draggingOffset: Float
        get() = draggingItem?.let { initialOffset + draggedDistance - it.offset } ?: 0f

    fun onDragStart(key: Any) {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        settleJob?.cancel()
        draggingKey = key
        initialOffset = item.offset
        draggedDistance = 0f
    }

    /**
     * Lets go of the row. The finger is rarely exactly over the row's new slot, so the leftover
     * translation is animated away instead of snapping — the row keeps its lifted look until it
     * has landed.
     */
    fun onDragStop() {
        val landed = draggingItem
        if (landed == null) {
            reset()
            return
        }
        settleJob = scope.launch {
            animate(
                initialValue = draggedDistance,
                targetValue = (landed.offset - initialOffset).toFloat(),
            ) { value, _ -> draggedDistance = value }
            reset()
        }
    }

    private fun reset() {
        draggingKey = null
        draggedDistance = 0f
        initialOffset = 0
    }

    fun onDrag(deltaY: Float) {
        draggedDistance += deltaY
        val dragging = draggingItem ?: return

        val start = initialOffset + draggedDistance
        val end = start + dragging.size
        val middle = (start + dragging.size / 2f).toInt()

        val keys = reorderableKeys()
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != dragging.key && item.key in keys && middle in item.offset..(item.offset + item.size)
        }

        if (target != null) {
            // The list anchors itself on the first visible item, so swapping it with a
            // neighbour would otherwise make the whole list jump by one row.
            val firstVisible = listState.firstVisibleItemIndex
            if (dragging.index == firstVisible || target.index == firstVisible) {
                listState.requestScrollToItem(firstVisible, listState.firstVisibleItemScrollOffset)
            }
            onMove(dragging.key, target.key)
        } else {
            val overscroll = when {
                draggedDistance > 0 -> (end - listState.layoutInfo.viewportEndOffset).coerceAtLeast(0f)
                draggedDistance < 0 -> (start - listState.layoutInfo.viewportStartOffset).coerceAtMost(0f)
                else -> 0f
            }
            if (overscroll != 0f) scrollChannel.trySend(overscroll)
        }
    }
}

/**
 * Remembers a [ReorderableListState] for [listState] and runs the auto-scroll pump that keeps
 * the list moving while a row is dragged against either edge of the viewport.
 */
@Composable
fun rememberReorderableListState(
    listState: LazyListState,
    reorderableKeys: Set<Any>,
    onMove: (from: Any, to: Any) -> Unit,
): ReorderableListState {
    val currentKeys by rememberUpdatedState(reorderableKeys)
    val currentOnMove by rememberUpdatedState(onMove)
    val scope = rememberCoroutineScope()

    val state = remember(listState, scope) {
        ReorderableListState(
            listState = listState,
            scope = scope,
            reorderableKeys = { currentKeys },
            onMove = { from, to -> currentOnMove(from, to) },
        )
    }

    LaunchedEffect(state) {
        for (diff in state.scrollChannel) {
            listState.scrollBy(diff)
        }
    }

    return state
}

/**
 * Turns the element into the drag handle for the row keyed [key]: a long press picks the row
 * up, and dragging moves it. Long press rather than immediate drag so the gesture does not
 * fight the list's own vertical scroll.
 */
fun Modifier.reorderableHandle(
    state: ReorderableListState,
    key: Any,
    onDragStarted: () -> Unit = {},
): Modifier = pointerInput(state, key) {
    detectDragGesturesAfterLongPress(
        onDragStart = {
            state.onDragStart(key)
            onDragStarted()
        },
        onDragEnd = state::onDragStop,
        onDragCancel = state::onDragStop,
        onDrag = { change, dragAmount ->
            change.consume()
            state.onDrag(dragAmount.y)
        },
    )
}
