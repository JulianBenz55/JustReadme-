package com.aryan.reader

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.input.KeyboardType
import coil.compose.AsyncImage
import com.aryan.reader.data.BookMetadataEdit
import com.aryan.reader.shared.ui.bounceClick
import com.aryan.reader.shared.ui.luneGlassBorder
import com.aryan.reader.shared.ui.luneGlassFill
import com.aryan.reader.shared.ui.luneIsDark
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
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
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
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
    onSaveMetadata: (BookMetadataEdit) -> Unit,
) {
    val context = LocalContext.current
    val reviews = BookReviewStore.reviews[book.bookId].orEmpty()
    var editingReview by remember { mutableStateOf<BookReview?>(null) }
    var writingReview by rememberSaveable { mutableStateOf(false) }
    var showFileDetails by rememberSaveable { mutableStateOf(false) }
    var showFullDescription by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val jumpOffsetPx = with(density) { -60.dp.roundToPx() }

    // Items before the quick-jump targets: cover, title, quick actions, sticky bar, progress, file.
    val description = book.description?.takeIf { it.isNotBlank() }?.let { stripHtml(it) }?.takeIf { it.isNotBlank() }
    val hasShelvesOrTags = shelfNames.isNotEmpty() || book.tags.isNotEmpty()
    val fileIndex = 5
    val reviewsIndex = 6 + (if (description != null) 1 else 0) + (if (hasShelvesOrTags) 1 else 0)
    val metadataIndex = reviewsIndex + 1 + reviews.size
    fun jumpTo(index: Int) {
        scope.launch { listState.animateScrollToItem(index, jumpOffsetPx) }
    }

    // "Load cover" goes straight to the picker and stores the image right away.
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onSaveMetadata(
                BookMetadataEdit(
                    title = book.title?.takeIf { it.isNotBlank() } ?: book.displayName.substringBeforeLast('.'),
                    author = book.author?.ifBlank { null },
                    seriesName = book.seriesName?.ifBlank { null },
                    seriesIndex = book.seriesIndex,
                    description = book.description,
                    coverImageUri = uri.toString(),
                ),
            )
        }
    }

    fun hasTag(name: String) = book.tags.any { it.name.equals(name, ignoreCase = true) }

    // Springy entrance like Lune's sheets.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enterProgress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "overviewEnter",
    )

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
          Box(
              Modifier.fillMaxSize().graphicsLayer {
                  alpha = enterProgress
                  scaleX = 0.94f + 0.06f * enterProgress
                  scaleY = 0.94f + 0.06f * enterProgress
              },
          ) {
            // Lune-style backdrop: the cover, heavily blurred and dimmed, behind the glass cards.
            val backdropDark = luneIsDark()
            ThemedBookCover(
                item = book,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(80.dp)
                    .alpha(if (backdropDark) 0.35f else 0.45f),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (backdropDark) Color.Black.copy(alpha = 0.50f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    ),
            )
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
                    Button(onClick = onRead, shape = CircleShape, modifier = Modifier.bounceClick(0.92f)) {
                        Text(stringResource(R.string.overview_read), fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
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
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                                luneGlassFill(),
                                            ),
                                        ),
                                    )
                                    .border(1.dp, luneGlassBorder(), RoundedCornerShape(24.dp))
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

                    // Quick jumps: no long scrolling to reach file, reviews, metadata or cover.
                    stickyHeader(key = "quickJumps") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            JumpChip(stringResource(R.string.overview_jump_file), null) {
                                showFileDetails = true
                                jumpTo(fileIndex)
                            }
                            JumpChip(stringResource(R.string.overview_jump_reviews), null) { jumpTo(reviewsIndex) }
                            JumpChip(stringResource(R.string.overview_jump_metadata), Icons.Default.Edit) { jumpTo(metadataIndex) }
                            JumpChip(stringResource(R.string.overview_load_cover), Icons.Default.Palette) {
                                coverPicker.launch(arrayOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp"))
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
                                color = luneGlassFill(),
                                border = BorderStroke(1.dp, luneGlassBorder()),
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
                                color = luneGlassFill(),
                                border = BorderStroke(1.dp, luneGlassBorder()),
                                modifier = Modifier.fillMaxWidth().bounceClick(0.98f).clickable { showFileDetails = !showFileDetails },
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
                    if (hasShelvesOrTags) {
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
                            colors = CardDefaults.elevatedCardColors(containerColor = luneGlassFill()),
                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, luneGlassBorder(), RoundedCornerShape(20.dp)),
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

                    // Metadata editor: current values, editable, saved back into the book.
                    item {
                        StaggerIn(8) {
                            MetadataEditorSection(book = book, onSave = onSaveMetadata)
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

/** Shows the current metadata and lets the user change title, author, series, series number and cover. */
@Composable
private fun MetadataEditorSection(book: RecentFileItem, onSave: (BookMetadataEdit) -> Unit) {
    val context = LocalContext.current
    fun formatIndex(value: Double?): String = when {
        value == null -> ""
        value % 1.0 == 0.0 -> value.toInt().toString()
        else -> value.toString()
    }
    val currentTitle = book.title?.takeIf { it.isNotBlank() } ?: book.displayName.substringBeforeLast('.')
    val currentAuthor = book.author.orEmpty()
    val currentSeries = book.seriesName.orEmpty()
    val currentIndex = formatIndex(book.seriesIndex)

    // Keyed on the stored values, so the fields refresh after a save.
    var title by rememberSaveable(book.bookId, currentTitle) { mutableStateOf(currentTitle) }
    var author by rememberSaveable(book.bookId, currentAuthor) { mutableStateOf(currentAuthor) }
    var series by rememberSaveable(book.bookId, currentSeries) { mutableStateOf(currentSeries) }
    var seriesNumber by rememberSaveable(book.bookId, currentIndex) { mutableStateOf(currentIndex) }
    var newCover by rememberSaveable(book.bookId, book.coverImagePath) { mutableStateOf<String?>(null) }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            newCover = uri.toString()
        }
    }

    val parsedIndex = seriesNumber.trim().replace(',', '.').toDoubleOrNull()
    val indexValid = seriesNumber.isBlank() || (parsedIndex != null && parsedIndex > 0.0)
    val changed = title.trim() != currentTitle.trim() ||
        author.trim() != currentAuthor.trim() ||
        series.trim() != currentSeries.trim() ||
        seriesNumber.trim() != currentIndex ||
        newCover != null
    val canSave = changed && indexValid && title.isNotBlank()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 4.dp))
        Text(stringResource(R.string.overview_edit_metadata), style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.overview_meta_title)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = author,
            onValueChange = { author = it },
            label = { Text(stringResource(R.string.overview_meta_author)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = series,
                onValueChange = { series = it },
                label = { Text(stringResource(R.string.overview_meta_series)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = seriesNumber,
                onValueChange = { seriesNumber = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                label = { Text("#") },
                supportingText = { Text(stringResource(R.string.overview_meta_series_number), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                isError = !indexValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(120.dp),
            )
        }

        Text(stringResource(R.string.overview_meta_cover), style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(width = 72.dp, height = 104.dp).clip(RoundedCornerShape(8.dp))) {
                ThemedBookCover(
                    item = book,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                newCover?.let { picked ->
                    AsyncImage(
                        model = picked,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (newCover != null) {
                    Text(
                        stringResource(R.string.overview_meta_cover_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = {
                    coverPicker.launch(arrayOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp"))
                }) { Text(stringResource(R.string.overview_meta_change_cover), maxLines = 1) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = canSave,
                modifier = Modifier.weight(1f),
                onClick = {
                    onSave(
                        BookMetadataEdit(
                            title = title.trim(),
                            author = author.trim().ifBlank { null },
                            seriesName = series.trim().ifBlank { null },
                            seriesIndex = if (series.isBlank()) null else parsedIndex,
                            description = book.description,
                            coverImageUri = newCover,
                        ),
                    )
                    newCover = null
                },
            ) { Text(stringResource(R.string.overview_meta_save), maxLines = 1) }
            TextButton(
                enabled = changed,
                onClick = {
                    title = currentTitle
                    author = currentAuthor
                    series = currentSeries
                    seriesNumber = currentIndex
                    newCover = null
                },
            ) { Text(stringResource(R.string.overview_meta_reset), maxLines = 1) }
        }
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
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp).bounceClick(0.85f)) {
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
private fun JumpChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
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
