package com.aryan.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aryan.reader.data.RecentFileItem
import com.aryan.reader.data.TagEntity

/**
 * Tags UI for Library Beta (Vellum Smart Tags, phases 1 and 3).
 *
 * The five built-in tags are seeded with stable `default_*` ids (see `MainViewModel.buildDefaultTags`),
 * which is how the drawer tells system tags from user-created ones.
 */

private const val SYSTEM_TAG_PREFIX = "default_"
private const val DRAWER_CUSTOM_TAG_LIMIT = 8

/** Order of the built-in tags in the drawer: Favorites, Finished, Reading, Reference, To Read. */
private val SYSTEM_TAG_ORDER = listOf(
    "default_favorites",
    "default_finished",
    "default_reading",
    "default_reference",
    "default_to_read",
)

internal fun TagEntity.isSystemTag(): Boolean = id.startsWith(SYSTEM_TAG_PREFIX)

/** Number of books per tag id, derived from the library so it updates with every change. */
internal fun tagBookCounts(books: List<RecentFileItem>): Map<String, Int> =
    books.flatMap { book -> book.tags.map { it.id } }.groupingBy { it }.eachCount()

/** Own tags in their manual order: sortOrder first, then name. */
private fun List<TagEntity>.inManualOrder(): List<TagEntity> =
    sortedWith(compareBy<TagEntity>({ it.sortOrder }, { it.name.lowercase() }))

private fun List<TagEntity>.inSystemOrder(): List<TagEntity> =
    sortedBy { tag -> SYSTEM_TAG_ORDER.indexOf(tag.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }

/** Built-in tags first, then (at most a few) own tags, each with its live book count. */
@Composable
internal fun UnifiedLibraryDrawerTagEntries(
    tags: List<TagEntity>,
    counts: Map<String, Int>,
    selectedTagId: String?,
    onTagSelected: (String) -> Unit,
    onShowAll: () -> Unit,
) {
    val system = remember(tags) {
        tags.filter { it.isSystemTag() && it.visibleInNavigation }.inSystemOrder()
    }
    val custom = remember(tags) { tags.filterNot { it.isSystemTag() }.inManualOrder() }

    system.forEach { tag ->
        DrawerTagItem(tag, counts[tag.id] ?: 0, tag.id == selectedTagId, onTagSelected)
    }
    if (custom.isNotEmpty()) {
        Text(
            stringResource(R.string.drawer_tags_custom),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 40.dp, top = 8.dp, bottom = 4.dp),
        )
        custom.take(DRAWER_CUSTOM_TAG_LIMIT).forEach { tag ->
            DrawerTagItem(tag, counts[tag.id] ?: 0, tag.id == selectedTagId, onTagSelected)
        }
        if (custom.size > DRAWER_CUSTOM_TAG_LIMIT) {
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.drawer_tags_show_all, custom.size)) },
                selected = false,
                onClick = onShowAll,
                modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun DrawerTagItem(
    tag: TagEntity,
    count: Int,
    selected: Boolean,
    onTagSelected: (String) -> Unit,
) {
    NavigationDrawerItem(
        icon = {
            Icon(
                tag.displayIcon(),
                contentDescription = null,
                tint = tag.color?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        badge = { Text(count.toString(), style = MaterialTheme.typography.labelMedium) },
        selected = selected,
        onClick = { onTagSelected(tag.id) },
        modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
    )
}

/** Header of the tag detail view: the tag's symbol and color, name, description, count and (own tags) edit. */
@Composable
internal fun TagDetailHeader(
    tag: TagEntity,
    count: Int,
    onEdit: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            tag.displayIcon(),
            contentDescription = null,
            tint = tag.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                pluralStringResource(R.plurals.book_count, count, count),
                style = MaterialTheme.typography.titleSmall,
            )
            val description = tag.description?.takeIf { it.isNotBlank() }
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
            }
        }
    }
}

private enum class TagSortMode { POSITION, NAME, COUNT }

private enum class TagFilterMode { ALL, WITH_BOOKS, EMPTY }

/**
 * Tags overview and management: header with title/subtitle and the actions New tag, Search, Sort and
 * Filter, then the system tags, own tags (editable, manually orderable) and a few simple statistics.
 * Tapping a tag opens its books.
 */
@Composable
internal fun UnifiedTagsSection(
    modifier: Modifier,
    tags: List<TagEntity>,
    counts: Map<String, Int>,
    booksWithTagCount: Int,
    onTagSelected: (String) -> Unit,
    onOpenTrackingSettings: () -> Unit,
    onCreateTag: () -> Unit,
    onEditTag: (String) -> Unit,
    onMoveTag: (String, Boolean) -> Unit,
) {
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortIndex by rememberSaveable { mutableStateOf(TagSortMode.POSITION.ordinal) }
    var filterIndex by rememberSaveable { mutableStateOf(TagFilterMode.ALL.ordinal) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    val sortMode = TagSortMode.entries[sortIndex.coerceIn(0, TagSortMode.entries.size - 1)]
    val filterMode = TagFilterMode.entries[filterIndex.coerceIn(0, TagFilterMode.entries.size - 1)]

    val systemAll = remember(tags) { tags.filter { it.isSystemTag() } }
    val customAll = remember(tags) { tags.filterNot { it.isSystemTag() } }
    val manualCustomOrder = remember(customAll) { customAll.inManualOrder() }

    fun visible(source: List<TagEntity>, manual: List<TagEntity>): List<TagEntity> {
        val trimmed = query.trim()
        val filtered = source.filter { tag ->
            val count = counts[tag.id] ?: 0
            val matchesQuery = trimmed.isEmpty() ||
                tag.name.contains(trimmed, ignoreCase = true) ||
                (tag.description ?: "").contains(trimmed, ignoreCase = true)
            val matchesFilter = when (filterMode) {
                TagFilterMode.ALL -> true
                TagFilterMode.WITH_BOOKS -> count > 0
                TagFilterMode.EMPTY -> count == 0
            }
            matchesQuery && matchesFilter
        }
        return when (sortMode) {
            TagSortMode.POSITION -> manual.filter { it in filtered }
            TagSortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
            TagSortMode.COUNT -> filtered.sortedWith(
                compareByDescending<TagEntity> { counts[it.id] ?: 0 }.thenBy { it.name.lowercase() },
            )
        }
    }

    val system = visible(systemAll, systemAll.inSystemOrder())
    val custom = visible(customAll, manualCustomOrder)
    // Moving only makes sense in the full manual order, not in a searched or filtered subset.
    val canMove = sortMode == TagSortMode.POSITION && query.isBlank() && filterMode == TagFilterMode.ALL

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "overview_header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.section_tags),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.tags_overview_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Button(onClick = onCreateTag) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.tags_action_new))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            searchVisible = !searchVisible
                            if (!searchVisible) query = ""
                        },
                    ) {
                        Icon(
                            if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = stringResource(R.string.action_search),
                        )
                    }
                    Column {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Default.Sort, contentDescription = stringResource(R.string.tags_action_sort))
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            TagSortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                when (mode) {
                                                    TagSortMode.POSITION -> R.string.tags_sort_position
                                                    TagSortMode.NAME -> R.string.tags_sort_name
                                                    TagSortMode.COUNT -> R.string.tags_sort_count
                                                },
                                            ),
                                        )
                                    },
                                    trailingIcon = {
                                        if (mode == sortMode) Icon(Icons.Default.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        sortIndex = mode.ordinal
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    Column {
                        IconButton(onClick = { filterMenuOpen = true }) {
                            Icon(
                                Icons.Default.FilterList,
                                contentDescription = stringResource(R.string.content_desc_filter),
                                tint = if (filterMode != TagFilterMode.ALL) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        DropdownMenu(expanded = filterMenuOpen, onDismissRequest = { filterMenuOpen = false }) {
                            TagFilterMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                when (mode) {
                                                    TagFilterMode.ALL -> R.string.tags_filter_all
                                                    TagFilterMode.WITH_BOOKS -> R.string.tags_filter_with_books
                                                    TagFilterMode.EMPTY -> R.string.tags_filter_empty
                                                },
                                            ),
                                        )
                                    },
                                    trailingIcon = {
                                        if (mode == filterMode) Icon(Icons.Default.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        filterIndex = mode.ordinal
                                        filterMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (searchVisible) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.tags_search_placeholder)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item(key = "tracking_settings_entry") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenTrackingSettings)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.tracking_settings_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.tracking_settings_entry_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "system_header") { TagSectionHeader(stringResource(R.string.tags_section_system)) }
        if (system.isEmpty()) {
            item(key = "system_empty") { TagSectionHint(stringResource(R.string.tags_no_matches)) }
        }
        items(system, key = { "tag_${it.id}" }) { tag ->
            TagOverviewRow(
                tag = tag,
                count = counts[tag.id] ?: 0,
                onClick = { onTagSelected(tag.id) },
                onEdit = null,
                showMove = false,
                canMoveUp = false,
                canMoveDown = false,
                onMove = {},
            )
        }

        item(key = "custom_header") { TagSectionHeader(stringResource(R.string.drawer_tags_custom)) }
        if (customAll.isEmpty()) {
            item(key = "custom_empty") { TagSectionHint(stringResource(R.string.tags_custom_empty)) }
        } else if (custom.isEmpty()) {
            item(key = "custom_no_match") { TagSectionHint(stringResource(R.string.tags_no_matches)) }
        }
        items(custom, key = { "tag_${it.id}" }) { tag ->
            val position = manualCustomOrder.indexOfFirst { it.id == tag.id }
            TagOverviewRow(
                tag = tag,
                count = counts[tag.id] ?: 0,
                onClick = { onTagSelected(tag.id) },
                onEdit = { onEditTag(tag.id) },
                showMove = canMove && manualCustomOrder.size > 1,
                canMoveUp = position > 0,
                canMoveDown = position in 0 until manualCustomOrder.size - 1,
                onMove = { up -> onMoveTag(tag.id, up) },
            )
        }

        item(key = "stats") {
            val mostUsed = tags.maxByOrNull { counts[it.id] ?: 0 }?.takeIf { (counts[it.id] ?: 0) > 0 }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.tags_stats_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.tags_stats_total, tags.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.tags_stats_books, booksWithTagCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (mostUsed != null) {
                        Text(
                            stringResource(R.string.tags_stats_top, mostUsed.name, counts[mostUsed.id] ?: 0),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TagSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun TagSectionHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun TagOverviewRow(
    tag: TagEntity,
    count: Int,
    onClick: () -> Unit,
    onEdit: (() -> Unit)?,
    showMove: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            tag.displayIcon(),
            contentDescription = null,
            tint = tag.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                tag.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pluralStringResource(R.plurals.book_count, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val description = tag.description?.takeIf { it.isNotBlank() }
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showMove) {
            IconButton(onClick = { onMove(true) }, enabled = canMoveUp) {
                Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.tags_move_up))
            }
            IconButton(onClick = { onMove(false) }, enabled = canMoveDown) {
                Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.tags_move_down))
            }
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
            }
        }
    }
}
