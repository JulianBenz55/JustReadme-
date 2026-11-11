package com.aryan.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Edits author and series of several selected books at once.
 *
 * Only fields that are filled in are applied; empty fields leave each book as it is. With
 * "number automatically" the books get consecutive series numbers in the order they were selected.
 */
@Composable
internal fun BulkMetadataDialog(
    bookCount: Int,
    onDismiss: () -> Unit,
    /** author, series name, first series number (null = leave unchanged). */
    onApply: (author: String?, seriesName: String?, seriesStartIndex: Double?) -> Unit,
) {
    var author by rememberSaveable { mutableStateOf("") }
    var series by rememberSaveable { mutableStateOf("") }
    var autoNumber by rememberSaveable { mutableStateOf(false) }
    var startNumber by rememberSaveable { mutableStateOf("1") }

    val parsedStart = startNumber.trim().replace(',', '.').toDoubleOrNull()
    val startValid = !autoNumber || (parsedStart != null && parsedStart > 0.0)
    val hasInput = author.isNotBlank() || series.isNotBlank() || autoNumber
    val canApply = hasInput && startValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bulk_meta_title, bookCount)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.bulk_meta_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.bulk_meta_author)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = series,
                    onValueChange = { series = it },
                    label = { Text(stringResource(R.string.bulk_meta_series)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { autoNumber = !autoNumber },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = autoNumber, onCheckedChange = { autoNumber = it })
                    Text(
                        stringResource(R.string.bulk_meta_number_auto),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (autoNumber) {
                    OutlinedTextField(
                        value = startNumber,
                        onValueChange = { startNumber = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                        label = { Text(stringResource(R.string.bulk_meta_number_start)) },
                        isError = !startValid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(160.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canApply,
                onClick = {
                    onApply(
                        author.trim().ifBlank { null },
                        series.trim().ifBlank { null },
                        if (autoNumber) parsedStart else null,
                    )
                },
            ) { Text(stringResource(R.string.bulk_meta_apply, bookCount), maxLines = 1) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.web_import_cancel)) }
        },
    )
}
