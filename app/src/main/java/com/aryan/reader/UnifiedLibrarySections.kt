package com.aryan.reader

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.aryan.reader.data.RecentFileItem
import com.aryan.reader.shared.CloudFolderSyncSelection
import com.aryan.reader.shared.SortOrder
import com.aryan.reader.shared.ui.AppIcon

/**
 * Section-level composables for Library Beta.
 *
 * Split out of [UnifiedLibraryScreen] so the screen owns destination wiring while these
 * own their own layout. Every shared collaborator is injected, so the Android
 * implementation stays the benchmark for iOS parity.
 */

@Composable
internal fun unifiedShelfCountLabel(shelf: Shelf): String {
    val isFolder = shelf.type == ShelfType.FOLDER
    val folderCount = pluralStringResource(R.plurals.folder_count, shelf.childShelfCount, shelf.childShelfCount)
    val directCount = pluralStringResource(R.plurals.book_count, shelf.directBookCount, shelf.directBookCount)
    return when {
        isFolder && shelf.childShelfCount > 0 && shelf.directBookCount > 0 -> "$folderCount · $directCount"
        isFolder && shelf.childShelfCount > 0 -> folderCount
        isFolder -> directCount
        else -> pluralStringResource(R.plurals.book_count, shelf.bookCount, shelf.bookCount)
    }
}

@Composable
internal fun UnifiedFoldersSection(
    modifier: Modifier,
    folders: List<SyncedFolder>,
    allRecentFiles: List<RecentFileItem>,
    isLoading: Boolean,
    onAddFolder: () -> Unit,
    onScan: () -> Unit,
    onSyncMetadata: () -> Unit,
    onToggleLocalSync: (SyncedFolder, Boolean, Boolean) -> Unit,
    onEditFolderFilters: (SyncedFolder, Set<FileType>) -> Unit,
    onRemove: (SyncedFolder) -> Unit,
    cloudFolderSelection: CloudFolderSyncSelection? = null,
    cloudSyncEnabled: Boolean = false,
    isProUser: Boolean = false,
    onCloudFolderSettings: (() -> Unit)? = null,
    onIncomingCloudFolder: ((String) -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        FolderSyncScreen(
            syncedFolders = folders,
            allRecentFiles = allRecentFiles,
            onAddFolderClick = onAddFolder,
            onRemoveFolderClick = onRemove,
            onFolderLocalSyncChange = onToggleLocalSync,
            onEditFolderFiltersClick = onEditFolderFilters,
            onScanNowClick = onScan,
            onSyncMetadataClick = onSyncMetadata,
            isLoading = isLoading,
            cloudFolderSelection = cloudFolderSelection,
            cloudSyncEnabled = cloudSyncEnabled,
            isProUser = isProUser,
            onCloudFolderSettingsClick = onCloudFolderSettings,
            onIncomingCloudFolderClick = onIncomingCloudFolder,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnifiedLibraryControlsSheet(currentFilter: UnifiedLibraryFilter, currentSortOrder: SortOrder, onFilterChanged: (UnifiedLibraryFilter) -> Unit, onSortChanged: (SortOrder) -> Unit, onAdvancedFiltersClick: () -> Unit, onDismiss: () -> Unit) {
    com.aryan.reader.shared.ui.SharedAndroidUnifiedLibraryControlsSheet(
        currentFilter = currentFilter,
        currentSortOrder = currentSortOrder,
        strings = com.aryan.reader.shared.ui.SharedAndroidUnifiedControlsStrings(
            title = stringResource(R.string.unified_library_sort_filter),
            readStatus = stringResource(R.string.filter_read_status),
            sort = stringResource(R.string.content_desc_sort),
            advancedFilters = stringResource(R.string.filter_library),
            filterLabels = UnifiedLibraryFilter.entries.associateWith { stringResource(it.labelRes) },
            sortLabels = SortOrder.entries.associateWith { stringResource(it.labelRes) },
        ),
        onFilterChanged = onFilterChanged,
        onSortChanged = onSortChanged,
        onAdvancedFilters = onAdvancedFiltersClick,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun UnifiedCreateShelfDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    com.aryan.reader.shared.ui.SharedAndroidUnifiedCreateShelfDialog(
        title = stringResource(R.string.create_new_shelf),
        nameLabel = stringResource(R.string.shelf_name_hint),
        createLabel = stringResource(R.string.action_create),
        cancelLabel = stringResource(R.string.action_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

// Hoisted: shape allocation + clip without the offscreen shadow pass the
// scrolling continue card used to pay per recomposition.
internal val ContinueReadingCoverShape = RoundedCornerShape(18.dp)

@Composable
internal fun UnifiedContinueReadingCard(item: RecentFileItem, onClick: () -> Unit, modifier: Modifier = Modifier) {    val progress = (item.progressPercentage ?: 0f).coerceIn(0f, 100f)
    val appLayoutDirection = if (LocalConfiguration.current.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    com.aryan.reader.shared.ui.SharedAndroidUnifiedContinueCard(
        sectionLabel = stringResource(R.string.unified_library_continue_reading),
        title = item.cardTitle(),
        author = item.cardAuthor(),
        progressPercent = progress,
        progressLabel = stringResource(R.string.progress_complete, progress.toInt()),
        sourceLabel = if (item.sourceFolderUri != null) "· Local folder" else null,
        coverTone = generatedBookCoverColor(item),
        cardLayoutDirection = appLayoutDirection,
        onClick = onClick,
        modifier = modifier,
        cover = { coverModifier ->
                    ThemedBookCover(
                        item = item,
                modifier = coverModifier
                            .size(94.dp, 146.dp)
                            .clip(ContinueReadingCoverShape),
                        contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                    )
        },
        fileTypeBadge = {
                        FileTypeBadge(type = item.type, overlay = true, compact = true)
        },
    )
}

@Composable
internal fun UnifiedLibraryTopBar(
    section: UnifiedLibrarySection,
    selectedShelf: Shelf?,
    onMenuClick: () -> Unit,
    onBackFromShelf: () -> Unit,
    uiState: ReaderScreenState,
    onAccountClick: () -> Unit,
    searchQuery: String?,
    onSearchQueryChange: (String) -> Unit,
) {
    val title = selectedShelf?.name ?: when (section) {
        UnifiedLibrarySection.HOME -> null
        UnifiedLibrarySection.AUDIOBOOKS -> stringResource(R.string.listen_title)
        UnifiedLibrarySection.SHELVES -> stringResource(R.string.tab_shelves)
        UnifiedLibrarySection.FOLDERS -> stringResource(R.string.tab_folders)
        UnifiedLibrarySection.CATALOGS -> stringResource(R.string.tab_catalogs)
    }
    com.aryan.reader.shared.ui.SharedAndroidUnifiedTopBar(
        title = title,
        showingShelf = selectedShelf != null,
        drawerDescription = stringResource(R.string.unified_library_drawer_title),
        backToShelvesDescription = if (selectedShelf?.parentShelfId != null) {
            stringResource(R.string.action_back)
        } else {
            stringResource(R.string.unified_library_back_to_shelves)
        },
        onMenu = onMenuClick,
        onBackFromShelf = onBackFromShelf,
        onAccount = onAccountClick,
        accountAvatar = { UnifiedProfileAvatar(uiState) },
        searchQuery = searchQuery,
        searchPlaceholder = stringResource(R.string.unified_library_search_books),
        clearSearchDescription = stringResource(R.string.content_desc_clear_query),
        onSearchQueryChange = onSearchQueryChange,
    )
}

@Composable
internal fun UnifiedProfileAvatar(uiState: ReaderScreenState) {
    val user = uiState.currentUser
    when {
        BuildConfig.FLAVOR != "pro" -> AppIcon(
            contentDescription = stringResource(R.string.content_desc_app_icon),
            size = 32.dp,
            shape = CircleShape,
        )
        user != null -> AndroidAccountAvatar(
            user = user,
            modifier = Modifier.size(32.dp),
            contentDescription = stringResource(R.string.content_desc_profile_picture),
        )
        else -> Icon(
            Icons.Outlined.AccountCircle,
            contentDescription = stringResource(R.string.content_desc_profile),
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun UnifiedLibraryHome(
    modifier: Modifier,
    books: List<RecentFileItem>,
    continueReading: RecentFileItem?,
    filter: UnifiedLibraryFilter,
    query: String,
    sortOrder: SortOrder,
    advancedFilterCount: Int,
    useListView: Boolean,
    selectedBookIds: Set<String>,
    pinnedBookIds: Set<String>,
    downloadingBookIds: Set<String>,
    usePdfFileNameAsDisplayName: Boolean,
    onFilterChange: (UnifiedLibraryFilter) -> Unit,
    onControlsClick: () -> Unit,
    onAdvancedFiltersClick: () -> Unit,
    onListViewChange: (Boolean) -> Unit,
    onBookClick: (RecentFileItem) -> Unit,
    onBookLongClick: (RecentFileItem) -> Unit,
    openTabs: List<RecentFileItem>,
    tabsEnabled: Boolean,
    onCloseTab: (RecentFileItem) -> Unit,
    onCloseAllTabs: () -> Unit,
    widthSizeClass: WindowWidthSizeClass,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    com.aryan.reader.shared.ui.SharedAndroidUnifiedLibraryHome(
        books = books,
        continueReading = continueReading.takeIf { filter == UnifiedLibraryFilter.ALL && query.isBlank() },
        filter = filter,
        sortLabel = stringResource(sortOrder.labelRes),
        advancedFilterCount = advancedFilterCount,
        useListView = useListView,
        strings = com.aryan.reader.shared.ui.SharedAndroidUnifiedHomeStrings(
            noBooks = stringResource(R.string.unified_library_no_books),
            filterBooks = stringResource(R.string.content_desc_filter),
            gridView = stringResource(R.string.unified_library_grid_view),
            listView = stringResource(R.string.unified_library_list_view),
            filterLabels = UnifiedLibraryFilter.entries.associateWith { stringResource(it.labelRes) },
            booksPerRow = { count -> context.getString(R.string.library_books_per_row, count) },
            viewOptions = stringResource(R.string.library_view_options),
        ),
        columns = LibraryColumnsStore.columns,
        onColumnsChange = { LibraryColumnsStore.set(context, it) },
        itemKey = { it.bookId },
        onFilterChange = onFilterChange,
        onControls = onControlsClick,
        onAdvancedFilters = onAdvancedFiltersClick,
        onListViewChange = onListViewChange,
        openTabsContent = {
            com.aryan.reader.shared.ui.SharedAndroidActiveTabsRow(
                openTabs = openTabs,
                tabsEnabled = tabsEnabled,
                activeTabsLabel = stringResource(R.string.active_tabs),
                closeAllTabsDescription = stringResource(R.string.close_all_tabs),
                closeTabDescription = stringResource(R.string.close_tab),
                itemKey = { it.bookId },
                itemTitle = { it.title?.takeIf(String::isNotBlank) ?: it.displayName },
                onItemClick = onBookClick,
                onCloseTab = onCloseTab,
                onCloseAllTabs = onCloseAllTabs,
            )
        },
        continueCard = { item, cardModifier -> UnifiedContinueReadingCard(item, { onBookClick(item) }, cardModifier) },
        bookCard = { item ->
            RecentFileCard(
                item = item,
                isSelected = item.bookId in selectedBookIds,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onBookClick(item) },
                onLongClick = { onBookLongClick(item) },
                isDownloading = item.bookId in downloadingBookIds,
                usePdfFileNameAsDisplayName = usePdfFileNameAsDisplayName,
            )
        },
        bookListItem = { item ->
            LibraryListItem(
                item = item,
                isSelected = item.bookId in selectedBookIds,
                isPinned = item.bookId in pinnedBookIds,
                onItemClick = { onBookClick(item) },
                onItemLongClick = { onBookLongClick(item) },
                isDownloading = item.bookId in downloadingBookIds,
                usePdfFileNameAsDisplayName = usePdfFileNameAsDisplayName,
            )
        },
        widthClass = when (widthSizeClass) {
            WindowWidthSizeClass.Compact -> com.aryan.reader.shared.ui.SharedAndroidHomeWidthClass.COMPACT
            WindowWidthSizeClass.Medium -> com.aryan.reader.shared.ui.SharedAndroidHomeWidthClass.MEDIUM
            else -> com.aryan.reader.shared.ui.SharedAndroidHomeWidthClass.EXPANDED
        },
        modifier = modifier,
    )
}

@Composable
internal fun UnifiedShelvesSection(
    modifier: Modifier,
    shelves: List<Shelf>,
    selectedShelfId: String?,
    selectedBookIds: Set<String>,
    downloadingBookIds: Set<String>,
    usePdfFileNameAsDisplayName: Boolean,
    widthSizeClass: WindowWidthSizeClass,
    onShelfSelected: (Shelf) -> Unit,
    onBreadcrumbNavigate: (String?) -> Unit,
    onRenameShelf: (String) -> Unit,
    onDeleteShelf: (String) -> Unit,
    onAddBooks: (String) -> Unit,
    onBookClick: (RecentFileItem) -> Unit,
    onBookLongClick: (RecentFileItem) -> Unit,
    sortOrder: SortOrder = SortOrder.RECENT,
    onReorderShelf: (shelfId: String, orderedBookIds: List<String>) -> Unit = { _, _ -> },
) {
    val selectedShelf = shelves.find { it.id == selectedShelfId }
    val context = androidx.compose.ui.platform.LocalContext.current
    val storedOrders = ShelfOrderStore.orders
    // The sort chosen in the library menu also orders shelves, folders and the books inside them.
    val visibleShelves = remember(shelves, sortOrder) {
        shelves.filter { it.type != ShelfType.TAG && it.parentShelfId == null }.sortedForLibrary(sortOrder)
    }
    val childShelves = remember(shelves, selectedShelf, sortOrder, storedOrders) {
        val children = (selectedShelf?.childShelfIds?.mapNotNull { childId -> shelves.find { it.id == childId } } ?: emptyList())
            .sortedForLibrary(sortOrder)
        if (sortOrder == SortOrder.MANUAL && selectedShelf != null) {
            children.applyStoredOrder(storedOrders[ShelfOrderStore.foldersKey(selectedShelf.id)]) { it.id }
        } else {
            children
        }
    }
    val orderedBooks = remember(selectedShelf, sortOrder, storedOrders) {
        val direct = selectedShelf?.directBooks.orEmpty()
        when {
            // Own shelves keep their stored database order; shelves built from files use the saved order.
            sortOrder != SortOrder.MANUAL -> sortFiles(direct, sortOrder)
            selectedShelf == null || selectedShelf.type == ShelfType.MANUAL -> direct
            else -> direct.applyStoredOrder(storedOrders[ShelfOrderStore.booksKey(selectedShelf.id)]) { it.bookId }
        }
    }
    // Which list is being reordered: the books of a shelf, or its sub-folders.
    var reorderBooksShelfId by remember { mutableStateOf<String?>(null) }
    var reorderFoldersShelfId by remember { mutableStateOf<String?>(null) }
    reorderBooksShelfId?.let { shelfId ->
        val shelfToReorder = shelves.find { it.id == shelfId }
        if (shelfToReorder != null) {
            val shown = if (shelfToReorder.type == ShelfType.MANUAL) {
                shelfToReorder.directBooks
            } else {
                shelfToReorder.directBooks.applyStoredOrder(storedOrders[ShelfOrderStore.booksKey(shelfId)]) { it.bookId }
            }
            ShelfReorderDialog(
                title = stringResource(R.string.reorder_shelf_title),
                entries = shown.map { ReorderEntry(it.bookId, it.title?.takeIf { t -> t.isNotBlank() } ?: it.displayName, it) },
                onSave = { ordered ->
                    if (shelfToReorder.type == ShelfType.MANUAL) {
                        onReorderShelf(shelfId, ordered)
                    } else {
                        ShelfOrderStore.set(context, ShelfOrderStore.booksKey(shelfId), ordered)
                        onReorderShelf("", emptyList())
                    }
                    reorderBooksShelfId = null
                },
                onDismiss = { reorderBooksShelfId = null },
            )
        } else {
            reorderBooksShelfId = null
        }
    }
    reorderFoldersShelfId?.let { shelfId ->
        val parent = shelves.find { it.id == shelfId }
        if (parent != null) {
            val shown = parent.childShelfIds.mapNotNull { id -> shelves.find { it.id == id } }
                .applyStoredOrder(storedOrders[ShelfOrderStore.foldersKey(shelfId)]) { it.id }
            ShelfReorderDialog(
                title = stringResource(R.string.reorder_folders_title),
                entries = shown.map { ReorderEntry(it.id, it.name, null) },
                onSave = { ordered ->
                    ShelfOrderStore.set(context, ShelfOrderStore.foldersKey(shelfId), ordered)
                    onReorderShelf("", emptyList())
                    reorderFoldersShelfId = null
                },
                onDismiss = { reorderFoldersShelfId = null },
            )
        } else {
            reorderFoldersShelfId = null
        }
    }
    val breadcrumbEntries = remember(shelves, selectedShelfId) {
        com.aryan.reader.shared.ui.genericShelfBreadcrumbPath(
            currentShelfId = selectedShelfId,
            lookup = { id -> shelves.find { it.id == id } },
            idOf = { it.id },
            nameOf = { it.name },
            parentIdOf = { it.parentShelfId },
        )
    }
    com.aryan.reader.shared.ui.SharedAndroidUnifiedShelves(
        visibleShelves = visibleShelves,
        selectedShelf = selectedShelf,
        selectedBooks = orderedBooks,
        noShelvesLabel = stringResource(R.string.unified_library_no_shelves),
        shelfKey = { it.id },
        shelfName = { it.name },
        shelfBookCountLabel = { unifiedShelfCountLabel(it) },
        bookKey = { it.bookId },
        onShelfSelected = onShelfSelected,
        widthClass = when (widthSizeClass) {
            WindowWidthSizeClass.Compact -> com.aryan.reader.shared.ui.SharedAndroidHomeWidthClass.COMPACT
            WindowWidthSizeClass.Medium -> com.aryan.reader.shared.ui.SharedAndroidHomeWidthClass.MEDIUM
            else -> com.aryan.reader.shared.ui.SharedAndroidHomeWidthClass.EXPANDED
        },
        modifier = modifier,
        childShelves = childShelves,
        onChildShelfSelected = onShelfSelected,
        selectedShelfActions = { shelf ->
            UnifiedShelfActionsMenu(
                canMutate = shelf.type == ShelfType.MANUAL &&
                    com.aryan.reader.shared.SharedLibraryEditor.canMutateShelf(shelf.id),
                onAddBooks = { onAddBooks(shelf.id) },
                onReorder = if (shelf.type != ShelfType.TAG && shelf.type != ShelfType.SMART && shelf.directBooks.size > 1) ({ reorderBooksShelfId = shelf.id }) else null,
                onReorderFolders = if (shelf.childShelfIds.size > 1) ({ reorderFoldersShelfId = shelf.id }) else null,
                onRename = { onRenameShelf(shelf.id) },
                onDelete = { onDeleteShelf(shelf.id) },
            )
        },
        shelfPreview = { shelf -> FolderCoverCollage(shelf) },
        foldersSectionLabel = stringResource(R.string.section_folders),
        filesSectionLabel = stringResource(R.string.section_files),
        emptyShelfLabel = stringResource(R.string.shelf_empty),
        breadcrumbContent = {
            if (selectedShelf != null) {
                com.aryan.reader.shared.ui.SharedMobileShelfBreadcrumb(
                    entries = breadcrumbEntries,
                    onNavigate = { entry -> onBreadcrumbNavigate(entry.id) },
                    homeContentDescription = stringResource(R.string.tab_shelves),
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 12.dp),
                )
            }
        },
        bookCard = { item ->
            RecentFileCard(
                item = item,
                isSelected = item.bookId in selectedBookIds,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onBookClick(item) },
                onLongClick = { onBookLongClick(item) },
                isDownloading = item.bookId in downloadingBookIds,
                usePdfFileNameAsDisplayName = usePdfFileNameAsDisplayName,
            )
        },
    )
}

/**
 * Shelf-scoped actions for the shelf currently open in Library Beta.
 *
 * Add books is always offered; rename and delete apply only to manual shelves, since
 * folder-derived shelves are owned by their synced folder.
 */
@Composable
internal fun UnifiedShelfActionsMenu(
    canMutate: Boolean,
    onAddBooks: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onReorder: (() -> Unit)? = null,
    onReorderFolders: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("UnifiedShelfActions"),
        ) { Icon(Icons.Default.MoreVert, stringResource(R.string.content_desc_more_options)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.fab_add_books)) },
                onClick = { expanded = false; onAddBooks() },
            )
            if (onReorder != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reorder_shelf_title)) },
                    onClick = { expanded = false; onReorder() },
                )
            }
            if (onReorderFolders != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reorder_folders_title)) },
                    onClick = { expanded = false; onReorderFolders() },
                )
            }
            if (canMutate) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_rename_shelf)) },
                    onClick = { expanded = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_delete_shelf)) },
                    onClick = { expanded = false; onDelete() },
                )
            }
        }
    }
}
