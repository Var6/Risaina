package com.risaina.app.feature.lists

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drag to reorder in a LazyColumn, built here (no library): long-press a row's handle, drag, and
 * the row follows the finger while the others make room. Only items whose key passes [isRow] take
 * part. [onMove] moves the dragged row to the index (among rows) of the one it passed over. TalkBack
 * users get Move up / Move down actions instead (see the editor's row semantics).
 */
class ReorderState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val isRow: (Any) -> Boolean,
    private val onMove: (draggedKey: Any, targetKey: Any) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    /** How far the dragged row is drawn from where the list lays it out. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** The dragged row's index when it was last moved: until the list is laid out again, no second move. */
    private var movedFrom = -1

    fun start(key: Any) { draggingKey = key; offset = 0f; movedFrom = -1 }

    fun drag(dy: Float) {
        val key = draggingKey ?: return
        offset += dy
        val info = listState.layoutInfo
        val current = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val top = current.offset + offset
        val center = top + current.size / 2f
        val target = if (current.index == movedFrom) null
        else info.visibleItemsInfo.firstOrNull { it.key != key && isRow(it.key) && center > it.offset && center < it.offset + it.size }
        if (target != null) {
            movedFrom = current.index
            onMove(key, target.key)
            // The row is laid out at the target's place next frame: keep it under the finger.
            offset += current.offset - target.offset
        }
        // Near the top or bottom edge, the list scrolls along.
        val edge = current.size * 0.6f
        val delta = when {
            top < info.viewportStartOffset + edge -> -EDGE_SCROLL
            top + current.size > info.viewportEndOffset - edge -> EDGE_SCROLL
            else -> 0f
        }
        if (delta != 0f) scope.launch { listState.scrollBy(delta) }
    }

    fun end() { draggingKey = null; offset = 0f; movedFrom = -1 }

    fun isDragging(key: Any) = draggingKey == key

    private companion object { const val EDGE_SCROLL = 18f }
}

@Composable
fun rememberReorderState(listState: LazyListState, isRow: (Any) -> Boolean, onMove: (Any, Any) -> Unit): ReorderState {
    val scope = rememberCoroutineScope()
    return remember(listState) { ReorderState(listState, scope, isRow, onMove) }
}

/** The handle: a long press starts the drag (with a tick of haptics). */
@Composable
fun Modifier.reorderHandle(state: ReorderState, key: Any): Modifier {
    val haptics = LocalHapticFeedback.current
    return pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); state.start(key) },
            onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
            onDragEnd = { state.end() },
            onDragCancel = { state.end() },
        )
    }
}

/** The dragged row floats above the others and follows the finger. */
fun Modifier.draggedRow(state: ReorderState, key: Any): Modifier =
    if (!state.isDragging(key)) this else zIndex(1f).graphicsLayer { translationY = state.offset; shadowElevation = 8f }
