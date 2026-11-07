
package com.aryan.reader.webimport

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aryan.reader.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

internal enum class WebImportMode { BROWSER, NOVEL, FEED }

/** The "+" menu of the library: every way to bring a book into the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddBookSheet(
    onDismiss: () -> Unit,
    onLocalFile: () -> Unit,
    onDirectory: () -> Unit,
    onBrowser: () -> Unit,
    onNovel: () -> Unit,
    onFeed: () -> Unit,
    onOnlineLibrary: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text(
                stringResource(R.string.add_menu_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            AddMenuRow(
                Icons.Outlined.FileOpen,
                R.string.add_from_file,
                R.string.add_from_file_hint,
                onLocalFile
            )

            AddMenuRow(
                Icons.Default.Folder,
                R.string.add_from_directory,
                R.string.add_from_directory_hint,
                onDirectory
            )

            AddMenuRow(
                Icons.Outlined.FileOpen,
                R.string.add_from_browser,
                R.string.add_from_browser_hint,
                onBrowser
            )

            AddMenuRow(
                Icons.Default.Folder,
                R.string.add_from_novel,
                R.string.add_from_novel_hint,
                onNovel
            )

            AddMenuRow(
                Icons.Default.Folder,
                R.string.add_from_feed,
                R.string.add_from_feed_hint,
                onFeed
            )

            AddMenuRow(
                Icons.Default.Folder,
                R.string.add_online_library,
                R.string.add_online_library_hint,
                onOnlineLibrary
            )
        }
    }
}

@Composable
private fun AddMenuRow(
    icon: ImageVector,
    title: Int,
    hint: Int,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(stringResource(title))
        },
        supportingContent = {
            Text(stringResource(hint))
        },
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Asks for a web address, runs the matching importer and hands the resulting book file back. */
@Composable
internal fun WebImportDialog(
    mode: WebImportMode,
    onDismiss: () -> Unit,
    onImported: (File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        if (busy || input.isBlank()) return

        job = scope.launch {
            busy = true
            error = null
            done = 0
            total = 0

            try {
                val url = WebImporters.normalizeUrl(input)
                val appContext = context.applicationContext

                val file = when (mode) {
                    WebImportMode.BROWSER ->
                        WebImporters.importWebPage(appContext, url)

                    WebImportMode.NOVEL ->
                        WebImporters.importNovel(appContext, url) { finished, count ->
                            done = finished
                            total = count
                        }

                    WebImportMode.FEED ->
                        WebImporters.importFeed(appContext, url)
                }

                onImported(file)
                onDismiss()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    val titleRes = when (mode) {
        WebImportMode.BROWSER -> R.string.add_from_browser
        WebImportMode.NOVEL -> R.string.add_from_novel
        WebImportMode.FEED -> R.string.add_from_feed
    }

    val hintRes = when (mode) {
        WebImportMode.BROWSER -> R.string.web_import_hint_browser
        WebImportMode.NOVEL -> R.string.web_import_hint_novel
        WebImportMode.FEED -> R.string.web_import_hint_feed
    }

    AlertDialog(
        onDismissRequest = {
            if (!busy) onDismiss()
        },

        title = {
            Text(stringResource(titleRes))
        },

        text = {
            Column {
                Text(
                    stringResource(hintRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    label = {
                        Text(stringResource(R.string.web_import_url_label))
                    },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { start() }
                    ),
                    modifier = Modifier.padding(top = 12.dp),
                )

                if (busy) {
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = {
                                done.toFloat() / total.toFloat()
                            },
                            modifier = Modifier.padding(top = 16.dp),
                        )

                        Text(
                            "$done / $total",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.padding(top = 16.dp)
                        )
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
            TextButton(
                onClick = { start() },
                enabled = !busy && input.isNotBlank()
            ) {
                Text(stringResource(R.string.web_import_start))
            }
        },

        dismissButton = {
            TextButton(
                onClick = {
                    job?.cancel()
                    onDismiss()
                }
            ) {
                Text(
                    stringResource(
                        if (busy) {
                            R.string.web_import_cancel
                        } else {
                            R.string.web_import_close
                        }
                    )
                )
            }
        },
    )
}