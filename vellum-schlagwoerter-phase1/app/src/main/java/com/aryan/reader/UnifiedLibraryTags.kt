package com.aryan.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aryan.reader.data.RecentFileItem
import com.aryan.reader.data.TagEntity

/**
 * Tags UI for Library Beta (Vellum Smart Tags, phase 1).
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

private fun TagEntity.drawerIcon(): ImageVector = when (id) {
    "default_favorites" -> Icons.Default.Favorite
    "default_finished" -> Icons.Default.Check
    "default_reading" -> Icons.Default.Book
    "default_reference" -> Icons.Default.Bookmark
    "default_to_read" -> Icons.Default.Restore
    else -> Icons.Default.Tag
}

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
        tags.filter { it.isSystemTag() }
            .sortedBy { tag -> SYSTEM_TAG_ORDER.indexOf(tag.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    }
    val custom = remember(tags) { tags.filterNot { it.isSystemTag() } }

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
                tag.drawerIcon(),
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

/** Tags overview: one card per tag with its book count; tapping opens the tag's books. */
@Composable
internal fun UnifiedTagsSection(
    modifier: Modifier,
    tags: List<TagEntity>,
    counts: Map<String, Int>,
    onTagSelected: (String) -> Unit,
) {
    if (tags.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.desktop_no_tags_yet),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val system = remember(tags) {
        tags.filter { it.isSystemTag() }
            .sortedBy { tag -> SYSTEM_TAG_ORDER.indexOf(tag.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    }
    val ordered = remember(tags, system) { system + tags.filterNot { it.isSystemTag() } }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(ordered, key = { it.id }) { tag ->
            val count = counts[tag.id] ?: 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onTagSelected(tag.id) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    tag.drawerIcon(),
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
                }
            }
        }
    }
}
