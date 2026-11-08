package com.aryan.reader

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aryan.reader.data.RecentFileItem
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date

/** Names of the built-in tags the quick actions toggle. */
internal object QuickTags {
    const val FAVORITES = "Favorites"
    const val TO_READ = "To Read"
    const val FINISHED = "Finished"
    const val READING = "Reading"
}

private fun jsonArrayLength(json: String?): Int {
    if (json.isNullOrBlank()) return 0
    return try {
        JSONArray(json).length()
    } catch (e: Exception) {
        0
    }
}

private fun stripHtml(text: String): String =
    androidx.core.text.HtmlCompat.fromHtml(text, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

/** Fades and slides a section in a little later than the one before it. */
@Composable
private fun StaggerIn(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(60L * index)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 6 },
    ) { content() }
}

/**
 * Full-screen "About this book" page: cover, title, quick actions, reading progress, file
 * details, description, shelves, tags and the user's own reviews (stored on the device).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookOverviewScreen(
    book: RecentFileItem,
    shelfNames: List<String>,
    usePdfFileNameAsDisplayName: Boolean,
    onClose: () -> Unit,
    onRead: () -> Unit,
    onToggleQuickTag: (String) -> Unit,
    onAddToShelf: () -> Unit,
    onShare: (() -> Unit)?,
    onDelete: () -> Unit,
    onEditDetails: () -> Unit,
    onManageTags: () -> Unit,
    onExportAnnotations: (() -> Unit)?,
) {
    val context = LocalContext.current
    val reviews = BookReviewStore.reviews[book.bookId].orEmpty()
    var editingReview by remember { mutableStateOf<BookReview?>(null) }
    var writingReview by rememberSaveable { mutableStateOf(false) }
    var showFileDetails by rememberSaveable { mutableStateOf(false) }
    var showFullDescription by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    fun hasTag(name: String) = book.tags.any { it.name.equals(name, ignoreCase = true) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // Top bar: back, title and the main "read" action.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.web_import_close),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Text(
                        stringResource(R.string.overview_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Button(onClick = onRead, shape = CircleShape) {
                        Text(stringResource(R.string.overview_read), fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }

                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Cover on a soft gradient card.
                    item {
                        StaggerIn(0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primaryContainer,
                                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                            ),
                                        ),
                                    )
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                val coverShape = RoundedCornerShape(12.dp)
                                Box(
                                    Modifier
                                        .fillMaxWidth(0.62f)
                                        .aspectRatio(0.68f)
                                        .shadow(10.dp, coverShape)
                                        .clip(coverShape),
                                ) {
                                    ThemedBookCover(
                                        item = book,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }

                    // Title block.
                    item {
                        StaggerIn(1) {
                            Column {
                                Text(
                                    book.cardTitle(usePdfFileNameAsDisplayName),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                val author = book.author?.takeIf { it.isNotBlank() }
                                val volume = book.seriesIndex?.let { index ->
                                    val shown = if (index % 1.0 == 0.0) index.toInt().toString() else index.toString()
                                    stringResource(R.string.overview_volume, shown)
                                }
                                val line = listOfNotNull(author, volume).joinToString("  •  ")
                                if (line.isNotEmpty()) {
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                val meta = listOfNotNull(
                                    book.seriesName?.takeIf { it.isNotBlank() },
                                    stringResource(R.string.overview_format, book.type.name),
                                ).joinToString("  •  ")
                                Text(
                                    meta,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }

                    // Quick actions.
                    item {
                        StaggerIn(2) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                QuickAction(Icons.Default.Star, R.string.overview_action_favorite, hasTag(QuickTags.FAVORITES)) {
                                    onToggleQuickTag(QuickTags.FAVORITES)
                                }
                                QuickAction(Icons.Default.Restore, R.string.overview_action_to_read, hasTag(QuickTags.TO_READ)) {
                                    onToggleQuickTag(QuickTags.TO_READ)
                                }
                                QuickAction(Icons.Default.Check, R.string.overview_action_finished, hasTag(QuickTags.FINISHED)) {
                                    onToggleQuickTag(QuickTags.FINISHED)
                                }
                                val reading = hasTag(QuickTags.READING)
                                QuickAction(
                                    if (reading) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    R.string.overview_action_reading,
                                    reading,
                                ) { onToggleQuickTag(QuickTags.READING) }
                                QuickAction(Icons.AutoMirrored.Filled.LibraryBooks, R.string.overview_action_shelf, false, onAddToShelf)
                                if (onShare != null) {
                                    QuickAction(Icons.Default.Share, R.string.overview_action_share, false, onShare)
                                }
                                QuickAction(Icons.Default.Edit, R.string.overview_action_edit, false, onEditDetails)
                                Box {
                                    QuickAction(Icons.Default.MoreVert, R.string.overview_action_more, false) { menuOpen = true }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.overview_manage_tags)) },
                                            onClick = {
                                                menuOpen = false
                                                onManageTags()
                                            },
                                        )
                                        if (onExportAnnotations != null) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.overview_export_annotations)) },
                                                onClick = {
                                                    menuOpen = false
                                                    onExportAnnotations()
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                            text = { Text(stringResource(R.string.overview_delete_book), color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                menuOpen = false
                                                onDelete()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Reading progress.
                    item {
                        StaggerIn(3) {
                            val percent = book.progressPercentage?.coerceIn(0f, 100f) ?: 0f
                            val animated by animateFloatAsState(percent / 100f, tween(700), label = "overviewProgress")
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (percent > 0f) "${percent.toInt()} %" else stringResource(R.string.overview_not_started),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        if (book.timestamp > 0L && percent > 0f) {
                                            Text(
                                                "  •  " + DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(book.timestamp)),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    LinearProgressIndicator(
                                        progress = { animated },
                                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(8.dp).clip(CircleShape),
                                    )
                                    Text(
                                        stringResource(R.string.overview_last_position),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                    val bookmarks = jsonArrayLength(book.bookmarksJson)
                                    val highlights = jsonArrayLength(book.highlightsJson)
                                    if (bookmarks > 0 || highlights > 0) {
                                        Text(
                                            stringResource(R.string.overview_counts, bookmarks, highlights),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // File details (expandable).
                    item {
                        StaggerIn(4) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth().clickable { showFileDetails = !showFileDetails },
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(stringResource(R.string.overview_file), style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                book.type.name + "  •  " + Formatter.formatShortFileSize(context, book.fileSize),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Icon(
                                            if (showFileDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                        )
                                    }
                                    AnimatedVisibility(visible = showFileDetails) {
                                        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
                                            DetailLine(stringResource(R.string.overview_name), book.displayName)
                                            if (book.dateAddedTimestamp > 0L) {
                                                DetailLine(stringResource(R.string.overview_added), dateFormat.format(Date(book.dateAddedTimestamp)))
                                            }
                                            if (book.lastModifiedTimestamp > 0L) {
                                                DetailLine(stringResource(R.string.overview_modified), dateFormat.format(Date(book.lastModifiedTimestamp)))
                                            }
                                            DetailLine(
                                                stringResource(R.string.overview_location),
                                                when {
                                                    book.uriString?.startsWith("opds-pse://") == true -> stringResource(R.string.source_opds)
                                                    book.sourceFolderUri != null -> book.sourceFolderUri
                                                    else -> stringResource(R.string.source_in_app)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Description.
                    val description = book.description?.takeIf { it.isNotBlank() }?.let { stripHtml(it) }?.takeIf { it.isNotBlank() }
                    if (description != null) {
                        item {
                            StaggerIn(5) {
                                Column {
                                    Text(stringResource(R.string.overview_description), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = if (showFullDescription) Int.MAX_VALUE else 6,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                    if (description.length > 260) {
                                        TextButton(onClick = { showFullDescription = !showFullDescription }) {
                                            Text(stringResource(if (showFullDescription) R.string.overview_less else R.string.overview_more))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Shelves and tags.
                    if (shelfNames.isNotEmpty() || book.tags.isNotEmpty()) {
                        item {
                            StaggerIn(6) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (shelfNames.isNotEmpty()) {
                                        Text(stringResource(R.string.overview_shelves), style = MaterialTheme.typography.titleSmall)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            shelfNames.forEach { name -> InfoChip(name) }
                                        }
                                    }
                                    if (book.tags.isNotEmpty()) {
                                        Text(stringResource(R.string.overview_tags), style = MaterialTheme.typography.titleSmall)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            book.tags.forEach { tag -> InfoChip(tag.name) }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Reviews.
                    item {
                        StaggerIn(7) {
                            Column {
                                HorizontalDivider(Modifier.padding(bottom = 12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.overview_reviews), style = MaterialTheme.typography.titleLarge)
                                        if (reviews.isNotEmpty()) {
                                            val average = reviews.map { it.rating }.average()
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RatingStars(rating = average.toFloat().let { Math.round(it) }, size = 18)
                                                Text(
                                                    "  " + String.format(java.util.Locale.getDefault(), "%.1f", average) + "  (" + reviews.size + ")",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                    FilledTonalButton(onClick = {
                                        editingReview = null
                                        writingReview = true
                                    }) {
                                        Text(stringResource(R.string.overview_write_review), maxLines = 1)
                                    }
                                }
                                if (reviews.isEmpty()) {
                                    Text(
                                        stringResource(R.string.overview_no_reviews),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                    items(reviews.size) { index ->
                        val review = reviews[index]
                        ElevatedCard(
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RatingStars(rating = review.rating, size = 20)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(review.updatedAt)),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (review.text.isNotBlank()) {
                                    Text(review.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    IconButton(onClick = {
                                        editingReview = review
                                        writingReview = true
                                    }) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.overview_edit_review)) }
                                    IconButton(onClick = { BookReviewStore.delete(context, book.bookId, review.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.overview_delete_review))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (writingReview) {
        ReviewEditorDialog(
            initial = editingReview,
            onDismiss = {
                writingReview = false
                editingReview = null
            },
            onSave = { rating, text ->
                BookReviewStore.save(context, book.bookId, editingReview?.id, rating, text)
                writingReview = false
                editingReview = null
            },
        )
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: Int,
    active: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (active) 1.15f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "quickActionScale",
    )
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            icon,
            contentDescription = stringResource(description),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(92.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Five stars, the first [rating] of them filled. */
@Composable
private fun RatingStars(rating: Int, size: Int, onRate: ((Int) -> Unit)? = null) {
    Row {
        for (value in 1..5) {
            val filled = value <= rating
            val scale by animateFloatAsState(
                targetValue = if (filled) 1f else 0.85f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "star$value",
            )
            val tint = if (filled) Color(0xFFFFC107) else MaterialTheme.colorScheme.outlineVariant
            val iconModifier = Modifier.size(size.dp).graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            if (onRate != null) {
                IconButton(onClick = { onRate(if (rating == value) 0 else value) }, modifier = Modifier.size((size + 12).dp)) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = tint, modifier = iconModifier)
                }
            } else {
                Icon(Icons.Default.Star, contentDescription = null, tint = tint, modifier = iconModifier)
            }
        }
    }
}

@Composable
private fun ReviewEditorDialog(
    initial: BookReview?,
    onDismiss: () -> Unit,
    onSave: (rating: Int, text: String) -> Unit,
) {
    var rating by remember { mutableIntStateOf(initial?.rating ?: 0) }
    var text by rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.overview_write_review else R.string.overview_edit_review)) },
        text = {
            Column {
                Text(stringResource(R.string.overview_your_rating), style = MaterialTheme.typography.labelLarge)
                RatingStars(rating = rating, size = 30, onRate = { rating = it })
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.overview_review_hint)) },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = rating > 0 || text.isNotBlank(), onClick = { onSave(rating, text) }) {
                Text(stringResource(R.string.reorder_shelf_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.web_import_cancel)) }
        },
    )
}
