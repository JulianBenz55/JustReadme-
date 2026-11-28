package com.aryan.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aryan.reader.data.RecentFileItem
import com.aryan.reader.data.TagEntity

private const val TAG_NAME_MAX_LENGTH = 60
private const val TAG_DESCRIPTION_MAX_LENGTH = 300
private const val ICONS_PER_ROW = 6
private const val COLORS_PER_ROW = 7

/** What the editor hands back: the new field values plus which books to add to / remove from the tag. */
internal data class TagEditorResult(
    val name: String,
    val description: String,
    val icon: String,
    val color: Int,
    val addBookIds: Set<String>,
    val removeBookIds: Set<String>,
    val visibleInNavigation: Boolean,
    val isEnabled: Boolean,
    val showCount: Boolean,
)

/**
 * Full-screen dialog to create ([existing] == null) or edit an own tag: name, description, symbol,
 * color and the books it is assigned to. [onDelete] is only offered while editing; deleting removes
 * the tag from its books, never the books themselves.
 */
@Composable
internal fun TagEditorDialog(
    existing: TagEntity?,
    allTags: List<TagEntity>,
    books: List<RecentFileItem>,
    onDismiss: () -> Unit,
    onSave: (TagEditorResult) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var iconKey by remember { mutableStateOf(existing?.icon ?: TAG_ICON_DEFAULT_KEY) }
    var color by remember { mutableStateOf(existing?.color ?: TAG_COLOR_CHOICES.random()) }
    // New tags start hidden from the sidebar; the choice is part of creating and editing.
    var visibleInNavigation by remember { mutableStateOf(existing?.visibleInNavigation ?: false) }
    var isEnabled by remember { mutableStateOf(existing?.isEnabled ?: true) }
    var showCount by remember { mutableStateOf(existing?.showCount ?: true) }
    var bookQuery by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    val initialIds: Set<String> = remember(existing?.id) {
        if (existing == null) {
            emptySet()
        } else {
            books.filter { book -> book.tags.any { it.id == existing.id } }.map { it.bookId }.toSet()
        }
    }
    var selectedIds by remember { mutableStateOf(initialIds) }

    val trimmedName = name.trim()
    val nameTaken = trimmedName.isNotEmpty() && allTags.any { tag ->
        tag.id != existing?.id && tag.name.trim().equals(trimmedName, ignoreCase = true)
    }
    val canSave = trimmedName.isNotEmpty() && !nameTaken

    val visibleBooks = remember(books, bookQuery) {
        val query = bookQuery.trim()
        if (query.isEmpty()) {
            books
        } else {
            books.filter { book ->
                (book.title ?: "").contains(query, ignoreCase = true) ||
                    book.displayName.contains(query, ignoreCase = true) ||
                    (book.author ?: "").contains(query, ignoreCase = true)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                    }
                    Text(
                        text = stringResource(
                            if (existing == null) R.string.tag_editor_title_new else R.string.tag_editor_title_edit,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = canSave,
                        onClick = {
                            onSave(
                                TagEditorResult(
                                    name = trimmedName,
                                    description = description.trim(),
                                    icon = iconKey,
                                    color = color,
                                    addBookIds = selectedIds - initialIds,
                                    removeBookIds = initialIds - selectedIds,
                                    visibleInNavigation = visibleInNavigation,
                                    isEnabled = isEnabled,
                                    showCount = showCount,
                                ),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "name") {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(TAG_NAME_MAX_LENGTH) },
                            label = { Text(stringResource(R.string.tag_editor_name)) },
                            singleLine = true,
                            isError = nameTaken,
                            supportingText = if (nameTaken) ({ Text(stringResource(R.string.tag_editor_name_exists)) }) else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(key = "description") {
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it.take(TAG_DESCRIPTION_MAX_LENGTH) },
                            label = { Text(stringResource(R.string.tag_editor_description)) },
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(key = "icon_header") {
                        Text(
                            stringResource(R.string.tag_editor_icon),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    item(key = "icon_grid") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TAG_ICON_CHOICES.chunked(ICONS_PER_ROW).forEach { rowItems ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowItems.forEach { (key, vector) ->
                                        val selected = key == iconKey
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (selected) Color(color).copy(alpha = 0.25f) else Color.Transparent,
                                                )
                                                .border(
                                                    width = 1.dp,
                                                    color = if (selected) Color(color) else MaterialTheme.colorScheme.outlineVariant,
                                                    shape = CircleShape,
                                                )
                                                .clickable { iconKey = key },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                vector,
                                                contentDescription = null,
                                                tint = if (selected) Color(color) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "color_header") {
                        Text(
                            stringResource(R.string.tag_editor_color),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    item(key = "color_grid") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TAG_COLOR_CHOICES.chunked(COLORS_PER_ROW).forEach { rowItems ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowItems.forEach { swatch ->
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Color(swatch))
                                                .clickable { color = swatch },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (swatch == color) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "options") {
                        Column {
                            TagEditorSwitchRow(stringResource(R.string.tag_opt_enabled), isEnabled) { isEnabled = it }
                            TagEditorSwitchRow(stringResource(R.string.tag_opt_sidebar), visibleInNavigation) { visibleInNavigation = it }
                            TagEditorSwitchRow(stringResource(R.string.tag_opt_show_count), showCount) { showCount = it }
                        }
                    }
                    item(key = "books_header") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.tag_editor_books),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(R.string.tag_editor_books_selected, selectedIds.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = bookQuery,
                                onValueChange = { bookQuery = it },
                                placeholder = { Text(stringResource(R.string.unified_library_search_books)) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (visibleBooks.isEmpty()) {
                        item(key = "books_empty") {
                            Text(
                                stringResource(R.string.tag_editor_no_books),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(visibleBooks, key = { "book_${it.bookId}" }) { book ->
                        val checked = book.bookId in selectedIds
                        val toggle = {
                            selectedIds = if (checked) selectedIds - book.bookId else selectedIds + book.bookId
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { toggle() },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggle() })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    book.title?.takeIf { it.isNotBlank() } ?: book.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val author = book.author?.takeIf { it.isNotBlank() }
                                if (author != null) {
                                    Text(
                                        author,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    if (onDelete != null) {
                        item(key = "delete") {
                            TextButton(
                                onClick = { confirmDelete = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(R.string.tag_editor_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.tag_editor_delete)) },
            text = { Text(stringResource(R.string.tag_editor_delete_message, existing?.name.orEmpty())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun TagEditorSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
