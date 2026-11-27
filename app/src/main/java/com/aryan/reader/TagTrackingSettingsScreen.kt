package com.aryan.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aryan.reader.data.RereadMode
import com.aryan.reader.data.SYSTEM_TAG_FINISHED
import com.aryan.reader.data.SYSTEM_TAG_READING
import com.aryan.reader.data.TagEntity
import com.aryan.reader.data.TagTrackingSettings

/**
 * Tracking settings of the Vellum smart tags: which system tags are maintained automatically,
 * what happens to To Read, how re-reading is handled and which system tags show up in the drawer.
 * Turning a tracker off never removes existing tag assignments.
 */
@Composable
internal fun TagTrackingSettingsScreen(
    modifier: Modifier,
    tags: List<TagEntity>,
    settings: TagTrackingSettings,
    onBack: () -> Unit,
    onTrackingChanged: (String, Boolean) -> Unit,
    onVisibilityChanged: (String, Boolean) -> Unit,
    onSettingsChanged: (TagTrackingSettings) -> Unit,
) {
    val systemTags = remember(tags) { tags.filter { it.isSystemTag() }.sortedBy { it.sortOrder } }
    val trackedTags = remember(systemTags) {
        systemTags.filter { it.id == SYSTEM_TAG_READING || it.id == SYSTEM_TAG_FINISHED }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Text(
                    stringResource(R.string.tracking_settings_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        item(key = "auto_header") { SettingsHeader(stringResource(R.string.tracking_section_automatic)) }
        item(key = "auto_hint") { SettingsHint(stringResource(R.string.tracking_automatic_hint)) }
        items(trackedTags.size, key = { "auto_${trackedTags[it].id}" }) { index ->
            val tag = trackedTags[index]
            val summary = if (tag.id == SYSTEM_TAG_READING) {
                stringResource(R.string.tracking_reading_summary)
            } else {
                stringResource(R.string.tracking_finished_summary)
            }
            SettingsSwitchRow(
                title = tag.name,
                summary = summary,
                checked = tag.trackingEnabled,
                onCheckedChange = { onTrackingChanged(tag.id, it) },
            )
        }
        item(key = "manual_hint") { SettingsHint(stringResource(R.string.tracking_manual_hint)) }

        item(key = "flow_divider") { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item(key = "flow_header") { SettingsHeader(stringResource(R.string.tracking_section_flow)) }
        item(key = "flow_to_read") {
            SettingsSwitchRow(
                title = stringResource(R.string.tracking_remove_to_read),
                summary = stringResource(R.string.tracking_remove_to_read_summary),
                checked = settings.removeToReadWhenReading,
                onCheckedChange = { onSettingsChanged(settings.copy(removeToReadWhenReading = it)) },
            )
        }

        item(key = "reread_divider") { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item(key = "reread_header") { SettingsHeader(stringResource(R.string.tracking_section_reread)) }
        item(key = "reread_keep") {
            SettingsRadioRow(
                title = stringResource(R.string.tracking_reread_keep),
                selected = settings.rereadMode == RereadMode.KEEP_FINISHED,
                onClick = { onSettingsChanged(settings.copy(rereadMode = RereadMode.KEEP_FINISHED)) },
            )
        }
        item(key = "reread_restart") {
            SettingsRadioRow(
                title = stringResource(R.string.tracking_reread_restart),
                selected = settings.rereadMode == RereadMode.RESTART,
                onClick = { onSettingsChanged(settings.copy(rereadMode = RereadMode.RESTART)) },
            )
        }
        item(key = "reread_hint") { SettingsHint(stringResource(R.string.tracking_reread_hint)) }

        item(key = "visibility_divider") { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item(key = "visibility_header") { SettingsHeader(stringResource(R.string.tracking_section_visibility)) }
        items(systemTags.size, key = { "visible_${systemTags[it].id}" }) { index ->
            val tag = systemTags[index]
            SettingsSwitchRow(
                title = tag.name,
                summary = null,
                checked = tag.visibleInNavigation,
                onCheckedChange = { onVisibilityChanged(tag.id, it) },
            )
        }
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingsHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
