package com.aryan.reader.webimport

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aryan.reader.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

private enum class NovelPhase { URL, PREVIEW, DOWNLOADING }

/**
 * "From web novel", in three phases: enter the chapter-list URL, pick the chapters to download in a
 * full-screen list (everything is pre-selected, so the detected list can still be checked at a
 * glance), then a cancellable download with progress. Only the selected chapters are requested.
 */
@Composable
internal fun NovelImportDialog(
    onDismiss: () -> Unit,
    onImported: (File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var phase by remember { mutableStateOf(NovelPhase.URL) }
    var toc by remember { mutableStateOf<NovelToc?>(null) }
    var sourceUrl by remember { mutableStateOf("") }
    var bookTitle by remember { mutableStateOf("") }
    // Positions (in toc.chapters) of the chapters to download. Kept here so a failed download
    // returns to the same selection.
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var showBrowser by remember { mutableStateOf(false) }
    // WebView User-Agent after a login/challenge in the in-app browser; clearance cookies are bound to it.
    var sessionUa by remember { mutableStateOf<String?>(null) }

    val placeholderText = stringResource(R.string.novel_chapter_failed)
    // Fallback for sites that answer a plain request with HTTP 403: render the page in a hidden WebView.
    val renderer: PageRenderer = { target, readySelector -> WebViewRenderer.render(context, target, readySelector) }

    fun describe(e: Exception): String {
        if (e is NovelImportException) {
            return when (e.kind) {
                NovelImportException.Kind.BLOCKED_URL -> context.getString(R.string.novel_error_blocked_url)
                NovelImportException.Kind.PARSE_FAILED -> context.getString(R.string.novel_error_no_chapters)
                NovelImportException.Kind.TRANSIENT -> context.getString(R.string.novel_error_transient)
                NovelImportException.Kind.BOT_BLOCK ->
                    context.getString(R.string.novel_error_blocked_site, e.httpStatus ?: 0)
                NovelImportException.Kind.FETCH_FAILED -> e.message ?: e.javaClass.simpleName
            }
        }
        return e.message ?: e.javaClass.simpleName
    }

    fun fetchToc(page: FetchedPage? = null) {
        if (busy || (page == null && input.isBlank())) return
        job = scope.launch {
            busy = true
            error = null
            try {
                val url = page?.finalUrl ?: WebImporters.normalizeUrl(input)
                val parsed = NovelImporter.fetchNovelToc(
                    url,
                    preloaded = page,
                    sessionUserAgent = sessionUa,
                    renderer = renderer,
                    backfill = false,
                )
                // Clearance cookies from the render are bound to the WebView's User-Agent.
                if (parsed.rendered) sessionUa = WebViewRenderer.lastUserAgent ?: sessionUa
                toc = parsed
                sourceUrl = url
                bookTitle = parsed.title
                selected = parsed.chapters.indices.toSet()
                phase = NovelPhase.PREVIEW
                // The list is already on screen; refine a guessed title/author in the background.
                if (parsed.weakTitle || parsed.weakAuthor) {
                    scope.launch {
                        try {
                            val filled = NovelImporter.completeMetadata(parsed, sessionUa, renderer = renderer)
                            if (toc === parsed) {
                                if (bookTitle == parsed.title) bookTitle = filled.title
                                toc = filled
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // best-effort only
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                busy = false
            }
        }
    }

    fun startDownload() {
        val current = toc ?: return
        val title = bookTitle.trim()
        val chosen = current.chapters.filterIndexed { index, _ -> index in selected }
        if (busy || title.isEmpty() || chosen.isEmpty()) return
        // A subset is a different book than the full novel: keep its EPUB identifier distinct.
        val identityKey = if (chosen.size == current.chapters.size) {
            sourceUrl
        } else {
            sourceUrl + "#" + chosen.joinToString(",") { it.url }
        }
        job = scope.launch {
            busy = true
            error = null
            done = 0
            total = chosen.size
            phase = NovelPhase.DOWNLOADING
            try {
                val appContext = context.applicationContext
                val book = NovelImporter.downloadNovel(
                    toc = current.copy(title = title, chapters = chosen),
                    sourceUrl = sourceUrl,
                    identityKey = identityKey,
                    outputDir = WebImporters.outputDir(appContext),
                    placeholderText = placeholderText,
                    sessionUserAgent = sessionUa,
                    renderer = renderer,
                ) { finished, count ->
                    done = finished
                    total = count
                }
                if (book.failures >= book.chapterCount) {
                    book.file.delete()
                    phase = NovelPhase.PREVIEW
                    error = context.getString(R.string.novel_error_none_downloaded)
                } else {
                    onImported(book.file)
                    if (book.failures > 0) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.novel_partial_failures, book.failures),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    onDismiss()
                }
            } catch (e: CancellationException) {
                phase = NovelPhase.PREVIEW
                throw e
            } catch (e: Exception) {
                phase = NovelPhase.PREVIEW
                error = describe(e)
            } finally {
                busy = false
            }
        }
    }

    if (showBrowser) {
        val startUrl = try {
            WebImporters.normalizeUrl(input)
        } catch (e: Exception) {
            null
        }
        if (startUrl != null) {
            WebBrowserScreen(
                startUrl = startUrl,
                onClose = { showBrowser = false },
                onImported = {},
                captureLabel = stringResource(R.string.novel_use_page),
                onCapture = { html, url, userAgent ->
                    sessionUa = userAgent
                    showBrowser = false
                    input = url
                    fetchToc(FetchedPage(html, url))
                },
            )
        } else {
            showBrowser = false
        }
    }

    val currentToc = toc
    if (phase == NovelPhase.PREVIEW && currentToc != null) {
        NovelChapterPicker(
            toc = currentToc,
            title = bookTitle,
            onTitleChange = { bookTitle = it },
            selected = selected,
            onSelectedChange = {
                selected = it
                error = null
            },
            error = error,
            onBack = {
                phase = NovelPhase.URL
                error = null
            },
            onDownload = { startDownload() },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.add_from_novel)) },
        text = {
            Column {
                when (phase) {
                    NovelPhase.URL -> {
                        Text(
                            stringResource(R.string.web_import_hint_novel),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = input,
                            onValueChange = {
                                input = it
                                error = null
                            },
                            label = { Text(stringResource(R.string.web_import_url_label)) },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { fetchToc() }),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        TextButton(
                            onClick = { showBrowser = true },
                            enabled = !busy && input.isNotBlank(),
                        ) { Text(stringResource(R.string.novel_open_browser)) }
                        if (busy) LinearProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                    }

                    NovelPhase.PREVIEW -> Unit

                    NovelPhase.DOWNLOADING -> {
                        if (total > 0) {
                            LinearProgressIndicator(
                                progress = { done.toFloat() / total.toFloat() },
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                "$done / $total",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            when (phase) {
                NovelPhase.URL -> TextButton(onClick = { fetchToc() }, enabled = !busy && input.isNotBlank()) {
                    Text(stringResource(R.string.novel_fetch))
                }
                NovelPhase.PREVIEW, NovelPhase.DOWNLOADING -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = {
                when {
                    // Cancelling a running download or fetch returns to the previous step.
                    busy -> job?.cancel()
                    else -> onDismiss()
                }
            }) {
                Text(
                    stringResource(if (busy) R.string.web_import_cancel else R.string.web_import_close),
                )
            }
        },
    )
}


// ------------------------------------------------------------------------ chapter picker

/** Full-screen chapter list: tap rows to toggle, quick actions on top, download button at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelChapterPicker(
    toc: NovelToc,
    title: String,
    onTitleChange: (String) -> Unit,
    selected: Set<Int>,
    onSelectedChange: (Set<Int>) -> Unit,
    error: String?,
    onBack: () -> Unit,
    onDownload: () -> Unit,
) {
    val total = toc.chapters.size
    var showRange by remember { mutableStateOf(false) }

    if (showRange) {
        ChapterRangeDialog(
            total = total,
            onDismiss = { showRange = false },
            onApply = { from, to ->
                onSelectedChange((from - 1 until to).toSet())
                showRange = false
            },
        )
    }

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.novel_select_title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.novel_back))
                            }
                        },
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 3.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.novel_selected_count, selected.size, total),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                error?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Button(
                                onClick = onDownload,
                                enabled = selected.isNotEmpty() && title.isNotBlank(),
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.novel_download_count, selected.size))
                            }
                        }
                    }
                },
            ) { padding ->
                Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistChip(
                            onClick = { onSelectedChange(toc.chapters.indices.toSet()) },
                            label = { Text(stringResource(R.string.novel_select_all)) },
                        )
                        AssistChip(
                            onClick = { onSelectedChange(emptySet()) },
                            label = { Text(stringResource(R.string.novel_select_none)) },
                        )
                        AssistChip(
                            onClick = { onSelectedChange(toc.chapters.indices.toSet() - selected) },
                            label = { Text(stringResource(R.string.novel_select_invert)) },
                        )
                        AssistChip(
                            onClick = { showRange = true },
                            label = { Text(stringResource(R.string.novel_select_range)) },
                        )
                    }
                    HorizontalDivider()
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        item(key = "header") {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                OutlinedTextField(
                                    value = title,
                                    onValueChange = onTitleChange,
                                    label = { Text(stringResource(R.string.novel_book_title)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (toc.author.isNotBlank()) {
                                    Text(
                                        toc.author,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                        items(total, key = { it }) { index ->
                            ChapterRow(
                                number = index + 1,
                                title = toc.chapters[index].title,
                                checked = index in selected,
                                onToggle = {
                                    onSelectedChange(if (index in selected) selected - index else selected + index)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(number: Int, title: String, checked: Boolean, onToggle: () -> Unit) {
    Surface(
        color = if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(8.dp))
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(44.dp),
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
        }
    }
}

/** "From … to …" by list position; replaces the current selection with that range. */
@Composable
private fun ChapterRangeDialog(total: Int, onDismiss: () -> Unit, onApply: (from: Int, to: Int) -> Unit) {
    var from by rememberSaveable { mutableStateOf("1") }
    var to by rememberSaveable { mutableStateOf(total.toString()) }
    val fromValue = from.toIntOrNull()
    val toValue = to.toIntOrNull()
    val valid = fromValue != null && toValue != null && fromValue in 1..total && toValue in fromValue..total

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.novel_range_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.novel_range_hint, total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = from,
                        onValueChange = { from = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.novel_range_from)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = to,
                        onValueChange = { to = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.novel_range_to)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (valid) onApply(fromValue!!, toValue!!) }),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(fromValue!!, toValue!!) }, enabled = valid) {
                Text(stringResource(R.string.novel_range_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.web_import_cancel)) }
        },
    )
}
