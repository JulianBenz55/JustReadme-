package com.aryan.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aryan.reader.data.RecentFileItem

/**
 * Folder preview for the library: a 2x2 collage of the first four book covers inside the
 * folder (including nested folders), instead of a generic folder icon. Empty folders fall
 * back to the folder icon.
 */
@Composable
internal fun FolderCoverCollage(shelf: Shelf, modifier: Modifier = Modifier) {
    val previews = remember(shelf.id, shelf.books) { shelf.folderPreviewBooks() }
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val slotColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(3.dp),
    ) {
        if (previews.isEmpty()) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center).fillMaxSize(0.5f),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (row in 0..1) {
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        for (col in 0..1) {
                            val item = previews.getOrNull(row * 2 + col)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(slotColor),
                            ) {
                                if (item != null) {
                                    ThemedBookCover(
                                        item = item,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Up to four books for the collage; books that have a real cover image come first. */
private fun Shelf.folderPreviewBooks(): List<RecentFileItem> {
    val withCover = books.asSequence().filter { !it.coverImagePath.isNullOrBlank() }.take(4).toList()
    if (withCover.size == 4) return withCover
    val used = withCover.mapTo(HashSet()) { it.bookId }
    return withCover + books.asSequence().filter { it.bookId !in used }.take(4 - withCover.size).toList()
}
