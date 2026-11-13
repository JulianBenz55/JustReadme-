package com.aryan.reader.webimport

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aryan.reader.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

private enum class NovelPhase { URL, PREVIEW, DOWNLOADING }

/**
 * "From web novel", in Readest's three phases: enter the chapter-list URL, confirm the detected
 * chapter list (heuristics can misfire, so a download of hundreds of requests never starts
 * unconfirmed), then a cancellable download with progress.
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
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var showBrowser by remember { mutableStateOf(false) }
    // WebView User-Agent after a login/challenge in the in-app browser; clearance cookies are bound to it.
    var sessionUa by remember { mutableStateOf<String?>(null) }

    val placeholderText = stringResource(R.string.novel_chapter_failed)

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
                val parsed = NovelImporter.fetchNovelToc(url, preloaded = page, sessionUserAgent = sessionUa)
                toc = parsed
                sourceUrl = url
                bookTitle = parsed.title
                phase = NovelPhase.PREVIEW
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
        if (busy || title.isEmpty()) return
        job = scope.launch {
            busy = true
            error = null
            done = 0
            total = current.chapters.size
            phase = NovelPhase.DOWNLOADING
            try {
                val appContext = context.applicationContext
                val book = NovelImporter.downloadNovel(
                    toc = current.copy(title = title),
                    sourceUrl = sourceUrl,
                    outputDir = WebImporters.outputDir(appContext),
                    placeholderText = placeholderText,
                    sessionUserAgent = sessionUa,
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

                    NovelPhase.PREVIEW -> {
                        val current = toc
                        if (current != null) {
                            Text(
                                current.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (current.author.isNotBlank()) {
                                Text(
                                    current.author,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                stringResource(R.string.novel_chapter_count, current.chapters.size),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            // First and last chapter make a misfiring heuristic obvious at a glance.
                            current.chapters.firstOrNull()?.let {
                                Text(
                                    stringResource(R.string.novel_first_chapter, it.title),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            current.chapters.lastOrNull()?.takeIf { current.chapters.size > 1 }?.let {
                                Text(
                                    stringResource(R.string.novel_last_chapter, it.title),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = bookTitle,
                            onValueChange = { bookTitle = it },
                            label = { Text(stringResource(R.string.novel_book_title)) },
                            singleLine = true,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }

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
                NovelPhase.PREVIEW -> TextButton(
                    onClick = { startDownload() },
                    enabled = !busy && bookTitle.isNotBlank(),
                ) {
                    Text(stringResource(R.string.novel_download))
                }
                NovelPhase.DOWNLOADING -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = {
                when {
                    // Cancelling a running download or fetch returns to the previous step.
                    busy -> job?.cancel()
                    phase == NovelPhase.PREVIEW -> {
                        phase = NovelPhase.URL
                        error = null
                    }
                    else -> onDismiss()
                }
            }) {
                Text(
                    stringResource(
                        when {
                            busy -> R.string.web_import_cancel
                            phase == NovelPhase.PREVIEW -> R.string.novel_back
                            else -> R.string.web_import_close
                        },
                    ),
                )
            }
        },
    )
}
