package com.aryan.reader

import com.aryan.reader.data.RecentFileItem

/** Date used for "date added" ordering: files from synced folders follow their own file date. */
private fun RecentFileItem.libraryDate(): Long =
    fileContentModifiedTimestamp.takeIf { !sourceFolderUri.isNullOrBlank() && it > 0L } ?: dateAddedTimestamp

private fun Shelf.averageProgress(): Double {
    if (books.isEmpty()) return 0.0
    return books.sumOf { (it.progressPercentage ?: 0f).toDouble() } / books.size
}

/**
 * Orders shelves and folders with the same sort the user picked for the book list, so the sort
 * menu also works inside shelves and folders. Shelves keep their type grouping (own shelves,
 * series, folders...), the order applies within each group.
 */
internal fun List<Shelf>.sortedForLibrary(sortOrder: SortOrder): List<Shelf> {
    val byType = compareBy<Shelf> { it.type.ordinal }
    val comparator: Comparator<Shelf> = when (sortOrder) {
        SortOrder.TITLE_ASC, SortOrder.AUTHOR_ASC ->
            byType.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortOrder.RECENT, SortOrder.MANUAL ->
            byType.thenByDescending { shelf -> shelf.books.maxOfOrNull { it.timestamp } ?: 0L }
        SortOrder.DATE_ADDED_NEWEST ->
            byType.thenByDescending { shelf -> shelf.books.maxOfOrNull { it.libraryDate() } ?: 0L }
        SortOrder.DATE_ADDED_OLDEST ->
            byType.thenBy { shelf -> shelf.books.minOfOrNull { it.libraryDate() } ?: Long.MAX_VALUE }
        SortOrder.PERCENT_ASC -> byType.thenBy { it.averageProgress() }
        SortOrder.PERCENT_DESC -> byType.thenByDescending { it.averageProgress() }
        SortOrder.SIZE_ASC -> byType.thenBy { shelf -> shelf.books.sumOf { it.fileSize } }
        SortOrder.SIZE_DESC -> byType.thenByDescending { shelf -> shelf.books.sumOf { it.fileSize } }
    }
    return sortedWith(comparator.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
