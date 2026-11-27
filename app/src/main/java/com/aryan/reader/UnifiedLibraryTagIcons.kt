package com.aryan.reader

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Verified
import androidx.compose.ui.graphics.vector.ImageVector
import com.aryan.reader.data.TagEntity
import com.aryan.reader.shared.LibraryMutationController

/**
 * Symbols selectable for own tags (Vellum Smart Tags, phase 3).
 *
 * Vellum ships its own small icon set (no material-icons-extended), so only icons that exist in
 * shared/.../androidx/compose/material/icons/filled/FilledIcons.kt can be offered. `TagEntity.icon`
 * stores the key (the map key below); unknown or missing keys fall back to [TAG_ICON_DEFAULT_KEY].
 */
internal const val TAG_ICON_DEFAULT_KEY = "Tag"

internal val TAG_ICON_CHOICES: List<Pair<String, ImageVector>> = listOf(
    "Tag" to Icons.Default.Tag,
    "Star" to Icons.Default.Star,
    "Favorite" to Icons.Default.Favorite,
    "Bookmark" to Icons.Default.Bookmark,
    "BookmarkBorder" to Icons.Default.BookmarkBorder,
    "Book" to Icons.Default.Book,
    "Folder" to Icons.Default.Folder,
    "FolderSpecial" to Icons.Default.FolderSpecial,
    "Description" to Icons.Default.Description,
    "Psychology" to Icons.Default.Psychology,
    "Code" to Icons.Default.Code,
    "Palette" to Icons.Default.Palette,
    "Brush" to Icons.Default.Brush,
    "Translate" to Icons.Default.Translate,
    "Verified" to Icons.Default.Verified,
    "PushPin" to Icons.Default.PushPin,
    "Home" to Icons.Default.Home,
    "Cloud" to Icons.Default.Cloud,
)

private val TAG_ICONS_BY_KEY: Map<String, ImageVector> = TAG_ICON_CHOICES.toMap()

internal fun tagIconForKey(key: String?): ImageVector =
    key?.let { TAG_ICONS_BY_KEY[it] } ?: Icons.Default.Tag

/** Fixed colors offered when creating or editing an own tag (same palette new tags were already drawn from). */
internal val TAG_COLOR_CHOICES: List<Int> = LibraryMutationController.TAG_COLORS

/**
 * Symbol of a tag everywhere in the UI. The five built-in tags keep their fixed symbols; own tags use
 * their chosen symbol and fall back to the plain tag symbol.
 */
internal fun TagEntity.displayIcon(): ImageVector = when (id) {
    SYSTEM_TAG_FAVORITES_ID -> Icons.Default.Favorite
    SYSTEM_TAG_FINISHED_ID -> Icons.Default.Check
    SYSTEM_TAG_READING_ID -> Icons.Default.Book
    SYSTEM_TAG_REFERENCE_ID -> Icons.Default.Bookmark
    SYSTEM_TAG_TO_READ_ID -> Icons.Default.Restore
    else -> tagIconForKey(icon)
}

private const val SYSTEM_TAG_FAVORITES_ID = "default_favorites"
private const val SYSTEM_TAG_FINISHED_ID = "default_finished"
private const val SYSTEM_TAG_READING_ID = "default_reading"
private const val SYSTEM_TAG_REFERENCE_ID = "default_reference"
private const val SYSTEM_TAG_TO_READ_ID = "default_to_read"
