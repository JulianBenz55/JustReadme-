package com.aryan.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.aryan.reader.data.RecentFileItem
import kotlinx.coroutines.delay

/** One row of the reorder editor: a book (with cover) or a folder (cover = null shows a folder icon). */
internal class ReorderEntry(val id: String, val title: String, val cover: RecentFileItem?)

/**
 * Full-screen order editor, like a playlist: drag the handle (long press, then move; the list
 * scrolls along near the edges) or use the arrows. "Save" stores the order for good.
 */
@Composable
internal fun ShelfReorderDialog(
    title: String,
    entries: List<ReorderEntry>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val items = remember { mutableStateListOf<ReorderEntry>().apply { addAll(entries) } }
    val listState = rememberLazyListState()
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    fun move(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
    }

    fun dragBy(delta: Float) {
        val id = draggingId ?: return
        dragOffset += delta
        val current = items.indexOfFirst { it.id == id }
        if (current < 0) return
        val info = listState.layoutInfo.visibleItemsInfo
        val currentInfo = info.firstOrNull { it.index == current } ?: return
        val center = currentInfo.offset + currentInfo.size / 2f + dragOffset
        val target = info.firstOrNull { it.index != current && center >= it.offset && center <= it.offset + it.size }
        if (target != null) {
            move(current, target.index)
            // Keep the dragged row under the finger after it changed place.
            dragOffset -= (target.offset - currentInfo.offset)
        }
    }

    // While a row is held near the top or bottom edge, the list keeps scrolling so the row can
    // travel the whole list in one drag.
    LaunchedEffect(draggingId) {
        if (draggingId == null) return@LaunchedEffect
        while (true) {
            val id = draggingId ?: break
            val layout = listState.layoutInfo
            val index = items.indexOfFirst { it.id == id }
            val row = layout.visibleItemsInfo.firstOrNull { it.index == index }
            if (row != null) {
                val top = row.offset + dragOffset
                val bottom = top + row.size
                val edge = 140f
                val speed = when {
                    top < layout.viewportStartOffset + edge ->
                        -(((layout.viewportStartOffset + edge - top) / edge) * 28f).coerceIn(6f, 28f)
                    bottom > layout.viewportEndOffset - edge ->
                        (((bottom - (layout.viewportEndOffset - edge)) / edge) * 28f).coerceIn(6f, 28f)
                    else -> 0f
                }
                if (speed != 0f) {
                    val consumed = listState.scrollBy(speed)
                    if (consumed != 0f) dragBy(consumed)
                }
            }
            delay(16)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.web_import_close))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    TextButton(onClick = { onSave(items.map { it.id }) }) {
                        Text(stringResource(R.string.reorder_shelf_save))
                    }
                }
                Text(
                    stringResource(R.string.reorder_shelf_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    itemsIndexed(items, key = { _, entry -> entry.id }) { index, entry ->
                        val dragging = draggingId == entry.id
                        val shape = RoundedCornerShape(14.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                // Rows that are not held glide into their new place; the held row follows the finger.
                                .then(if (dragging) Modifier else Modifier.animateItem())
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (dragging) dragOffset else 0f
                                    scaleX = if (dragging) 1.03f else 1f
                                    scaleY = if (dragging) 1.03f else 1f
                                }
                                .shadow(if (dragging) 10.dp else 0.dp, shape)
                                .clip(shape)
                                .background(
                                    if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                )
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(32.dp),
                            )
                            Box(
                                Modifier.size(width = 44.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (entry.cover != null) {
                                    ThemedBookCover(
                                        item = entry.cover,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                entry.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Column {
                                IconButton(
                                    onClick = { move(index, index - 1) },
                                    enabled = index > 0,
                                    modifier = Modifier.size(32.dp),
                                ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null) }
                                IconButton(
                                    onClick = { move(index, index + 1) },
                                    enabled = index < items.lastIndex,
                                    modifier = Modifier.size(32.dp),
                                ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) }
                            }
                            Icon(
                                Icons.Default.Sort,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(36.dp)
                                    .padding(6.dp)
                                    .pointerInput(entry.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggingId = entry.id
                                                dragOffset = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragBy(amount.y)
                                            },
                                            onDragEnd = {
                                                draggingId = null
                                                dragOffset = 0f
                                            },
                                            onDragCancel = {
                                                draggingId = null
                                                dragOffset = 0f
                                            },
                                        )
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}