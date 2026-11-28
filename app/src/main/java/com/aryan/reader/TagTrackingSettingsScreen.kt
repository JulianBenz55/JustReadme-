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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
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
    counts: Map<String, Int>,
    onEnabledChanged: (String, Boolean) -> Unit,
    onDisplayOptionsChanged: (String, Boolean, Boolean, Boolean) -> Unit,
    onEditTag: (String) -> Unit,
) {
    val systemTags = remember(tags) { tags.filter { it.isSystemTag() }.sortedBy { it.sortOrder } }
    val customTags = remember(tags) {
        tags.filterNot { it.isSystemTag() }.sortedWith(compareBy<TagEntity>({ it.sortOrder }, { it.name.lowercase() }))
    }
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
        item(key = "auto_reading_global") {
            SettingsSwitchRow(
                title = stringResource(R.string.tracking_auto_reading),
                summary = null,
                checked = settings.autoAssignReading,
                onCheckedChange = { onSettingsChanged(settings.copy(autoAssignReading = it)) },
            )
        }
        item(key = "auto_finished_global") {
            SettingsSwitchRow(
                title = stringResource(R.string.tracking_auto_finished),
                summary = stringResource(R.string.tracking_auto_finished_summary),
                checked = settings.autoAssignFinished,
                onCheckedChange = { onSettingsChanged(settings.copy(autoAssignFinished = it)) },
            )
        }
        item(key = "dwell_header") { SettingsHint(stringResource(R.string.tracking_dwell_title)) }
        items(DWELL_CHOICES_MS.size, key = { "dwell_${DWELL_CHOICES_MS[it]}" }) { index ->
            val ms = DWELL_CHOICES_MS[index]
            SettingsRadioRow(
                title = stringResource(R.string.tracking_dwell_seconds, (ms / 1000L).toInt()),
                selected = settings.minPageDwellMs == ms,
                onClick = { onSettingsChanged(settings.copy(minPageDwellMs = ms)) },
            )
        }

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

        item(key = "manage_divider") { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item(key = "manage_system_header") { SettingsHeader(stringResource(R.string.tags_manage_system)) }
        items(systemTags.size, key = { "manage_${systemTags[it].id}" }) { index ->
            val tag = systemTags[index]
            TagManageCard(
                tag = tag,
                count = counts[tag.id] ?: 0,
                onEnabledChanged = onEnabledChanged,
                onVisibilityChanged = onVisibilityChanged,
                onDisplayOptionsChanged = onDisplayOptionsChanged,
                onEdit = null,
            )
        }
        item(key = "manage_custom_header") { SettingsHeader(stringResource(R.string.tags_manage_custom)) }
        if (customTags.isEmpty()) {
            item(key = "manage_custom_empty") { SettingsHint(stringResource(R.string.tags_custom_empty)) }
        }
        items(customTags.size, key = { "manage_${customTags[it].id}" }) { index ->
            val tag = customTags[index]
            TagManageCard(
                tag = tag,
                count = counts[tag.id] ?: 0,
                onEnabledChanged = onEnabledChanged,
                onVisibilityChanged = onVisibilityChanged,
                onDisplayOptionsChanged = onDisplayOptionsChanged,
                onEdit = { onEditTag(tag.id) },
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

private val DWELL_CHOICES_MS = listOf(2_000L, 4_000L, 8_000L)

/**
 * One tag in the central management view: symbol, name, count and the sidebar switch; tapping expands
 * the remaining options. Every switch only changes a setting. No assignment is ever removed here.
 */
@Composable
private fun TagManageCard(
    tag: TagEntity,
    count: Int,
    onEnabledChanged: (String, Boolean) -> Unit,
    onVisibilityChanged: (String, Boolean) -> Unit,
    onDisplayOptionsChanged: (String, Boolean, Boolean, Boolean) -> Unit,
    onEdit: (() -> Unit)?,
) {
    var expanded by rememberSaveable(tag.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                tag.displayIcon(),
                contentDescription = null,
                tint = tag.color?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(tag.name, style = MaterialTheme.typography.bodyLarge)
                val status = if (tag.isEnabled) {
                    pluralStringResource(R.plurals.book_count, count, count)
                } else {
                    stringResource(R.string.tag_manage_disabled)
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = tag.visibleInNavigation,
                enabled = tag.isEnabled,
                onCheckedChange = { onVisibilityChanged(tag.id, it) },
            )
        }
        if (expanded) {
            SettingsSwitchRow(
                title = stringResource(R.string.tag_opt_enabled),
                summary = stringResource(R.string.tag_opt_enabled_summary),
                checked = tag.isEnabled,
                onCheckedChange = { onEnabledChanged(tag.id, it) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.tag_opt_show_icon),
                summary = null,
                checked = tag.showIcon,
                onCheckedChange = { onDisplayOptionsChanged(tag.id, it, tag.showDescription, tag.showCount) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.tag_opt_show_description),
                summary = null,
                checked = tag.showDescription,
                onCheckedChange = { onDisplayOptionsChanged(tag.id, tag.showIcon, it, tag.showCount) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.tag_opt_show_count),
                summary = null,
                checked = tag.showCount,
                onCheckedChange = { onDisplayOptionsChanged(tag.id, tag.showIcon, tag.showDescription, it) },
            )
            if (onEdit != null) {
                TextButton(onClick = onEdit, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.action_edit))
                }
            }
        }
        HorizontalDivider()
    }
}
