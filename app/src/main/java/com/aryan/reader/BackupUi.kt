package com.aryan.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** Create a full backup file or restore one. Restoring restarts the app. */
@Composable
internal fun BackupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeBooks by rememberSaveable { mutableStateOf(true) }
    var includeWebClips by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var restoreReady by remember { mutableStateOf(false) }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            message = null
            try {
                BackupManager.export(context.applicationContext, uri, includeBooks, includeWebClips)
                message = context.getString(R.string.backup_created)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            message = null
            try {
                BackupManager.stageRestore(context.applicationContext, uri)
                restoreReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    if (restoreReady) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.backup_restore_ready_title)) },
            text = { Text(stringResource(R.string.backup_restore_ready_text)) },
            confirmButton = {
                Button(onClick = { BackupManager.restartApp(context.applicationContext) }) {
                    Text(stringResource(R.string.backup_restart_now))
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.backup_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.backup_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clickable(enabled = !busy) { includeBooks = !includeBooks },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeBooks, onCheckedChange = { includeBooks = it }, enabled = !busy)
                    Text(stringResource(R.string.backup_include_books), style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !busy) { includeWebClips = !includeWebClips },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeWebClips, onCheckedChange = { includeWebClips = it }, enabled = !busy)
                    Text(stringResource(R.string.backup_include_web_clips), style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
                        createLauncher.launch("vellum-backup-$stamp.zip")
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(stringResource(R.string.backup_create), maxLines = 1) }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { openLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(stringResource(R.string.backup_restore), maxLines = 1) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.web_import_close)) }
        },
    )
}
