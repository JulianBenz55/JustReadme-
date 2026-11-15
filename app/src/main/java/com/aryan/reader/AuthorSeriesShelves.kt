package com.aryan.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aryan.reader.data.RecentFileItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.max

/*
 * Virtual shelves built from book metadata (author / series), shown under "Smart lists":
 *
 *   Autoren  ->  <Autor>  ->  <Reihe>  ->  Bücher (nach Band sortiert)
 *                         ->  Einzelbände (Bücher dieses Autors ohne Reihe)
 *   Reihen   ->  <Reihe>  ->  Bücher (nach Band sortiert)
 *
 * They are derived on the fly from the metadata (edited with the metadata editor), never stored,
 * and use ids that cannot collide with real shelves.
 */

internal const val SMART_AUTHORS_ID = "smart::authors"
internal const val SMART_SERIES_ID = "smart::series"
private const val AUTHOR_PREFIX = "author::"
private const val SERIES_PREFIX = "series::"
private const val AUTHOR_SERIES_MARK = "::series::"

/** The shelf of one author (its children are the author's series). */
internal fun isAuthorShelfId(id: String): Boolean = id.startsWith(AUTHOR_PREFIX) && !id.contains(AUTHOR_SERIES_MARK)

/** A series shelf, either below an author or in the global series list. */
internal fun isVirtualSeriesShelfId(id: String): Boolean = id.startsWith(SERIES_PREFIX) || id.contains(AUTHOR_SERIES_MARK)

internal fun isVirtualShelfId(id: String): Boolean =
    id.startsWith("smart::") || id.startsWith(AUTHOR_PREFIX) || id.startsWith(SERIES_PREFIX)

internal fun authorKeyOfShelfId(id: String): String = id.removePrefix(AUTHOR_PREFIX)

// ----------------------------------------------------------------------------- name handling

private val MULTI_AUTHOR_SPLIT = Regex("\\s*(?:;|&|\\s+and\\s+|\\s+und\\s+|\\s+/\\s+)\\s*", RegexOption.IGNORE_CASE)
private val WHITESPACE = Regex("\\s+")
private val DIACRITICS = Regex("\\p{Mn}+")
private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
private val NAME_SUFFIXES = setOf("jr", "sr", "ii", "iii", "iv", "phd", "md")

/**
 * Splits a metadata author string into display names: "A & B", "A; B" and "A, B, C" give several
 * authors; "Tolkien, J.R.R." is turned around to "J.R.R. Tolkien".
 */
internal fun splitAuthors(raw: String?): List<String> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    return text.split(MULTI_AUTHOR_SPLIT)
        .flatMap { part ->
            when (part.count { it == ',' }) {
                0 -> listOf(part)
                1 -> {
                    val pieces = part.split(',', limit = 2).map { it.trim() }
                    val last = pieces[0]
                    val first = pieces[1]
                    when {
                        last.isEmpty() || first.isEmpty() -> listOf(part.replace(",", " "))
                        first.trim('.').lowercase() in NAME_SUFFIXES -> listOf("$last $first")
                        else -> listOf("$first $last")
                    }
                }
                else -> part.split(',')
            }
        }
        .map { it.trim().replace(WHITESPACE, " ") }
        .filter { it.isNotEmpty() }
        .distinctBy { authorKey(it) }
}

/** Spelling-independent key: no case, accents, dots or spacing between initials ("J. R. R." = "J.R.R."). */
internal fun authorKey(name: String): String {
    val folded = Normalizer.normalize(name, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase()
    val tokens = folded.replace(NON_ALPHANUMERIC, " ").trim().split(' ').filter { it.isNotEmpty() }
    val merged = mutableListOf<String>()
    val initials = StringBuilder()
    for (token in tokens) {
        if (token.length == 1) {
            initials.append(token)
        } else {
            if (initials.isNotEmpty()) {
                merged.add(initials.toString())
                initials.clear()
            }
            merged.add(token)
        }
    }
    if (initials.isNotEmpty()) merged.add(initials.toString())
    return merged.joinToString(" ")
}

internal fun seriesKey(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase().trim().replace(WHITESPACE, " ")

private fun List<RecentFileItem>.sortedByVolume(): List<RecentFileItem> = sortedWith(
    compareBy<RecentFileItem>({ it.seriesIndex ?: Double.MAX_VALUE }, { (it.title ?: it.displayName).lowercase() }),
)

// --------------------------------------------------------------------------------- shelves

internal class AuthorSeriesShelves(
    /** Every virtual shelf, for lookups by id (selection, breadcrumbs, back navigation). */
    val all: List<Shelf>,
    /** The two top-level smart lists: Authors, Series (only those that are not empty). */
    val roots: List<Shelf>,
)

internal fun buildAuthorSeriesShelves(
    books: List<RecentFileItem>,
    authorsLabel: String,
    seriesLabel: String,
): AuthorSeriesShelves {
    val live = books.filter { !it.isDeleted }

    class AuthorAcc {
        val names = HashMap<String, Int>()
        val books = LinkedHashMap<String, RecentFileItem>()
    }

    val authors = LinkedHashMap<String, AuthorAcc>()
    for (book in live) {
        for (name in splitAuthors(book.author)) {
            val key = authorKey(name)
            if (key.isEmpty()) continue
            val acc = authors.getOrPut(key) { AuthorAcc() }
            acc.names.merge(name, 1, Int::plus)
            acc.books.putIfAbsent(book.bookId, book)
        }
    }
    val displayNames = authors.mapValues { (_, acc) ->
        acc.names.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
            .first().key
    }

    val everything = ArrayList<Shelf>()
    val authorShelves = ArrayList<Shelf>()
    for ((key, acc) in authors) {
        val authorId = AUTHOR_PREFIX + key
        val grouped = LinkedHashMap<String, MutableList<RecentFileItem>>()
        val seriesNames = HashMap<String, String>()
        val singles = ArrayList<RecentFileItem>()
        for (book in acc.books.values) {
            val series = book.seriesName?.trim().orEmpty()
            if (series.isEmpty()) {
                singles.add(book)
            } else {
                val sk = seriesKey(series)
                grouped.getOrPut(sk) { ArrayList() }.add(book)
                seriesNames.putIfAbsent(sk, series)
            }
        }
        // Within an author even a single book with a series name counts as a series.
        val seriesShelves = grouped.map { (sk, list) ->
            val sorted = list.sortedByVolume()
            Shelf(
                id = "$authorId$AUTHOR_SERIES_MARK$sk",
                name = seriesNames.getValue(sk),
                type = ShelfType.SERIES,
                books = sorted,
                parentShelfId = authorId,
                depth = 2,
            )
        }
        authorShelves += Shelf(
            id = authorId,
            name = displayNames.getValue(key),
            type = ShelfType.FOLDER,
            books = acc.books.values.toList(),
            directBooks = singles,
            parentShelfId = SMART_AUTHORS_ID,
            childShelfIds = seriesShelves.map { it.id },
            depth = 1,
        )
        everything += seriesShelves
    }
    val sortedAuthors = authorShelves.sortedBy { it.name.lowercase() }
    everything += sortedAuthors

    // Global series list: a series needs two books here, and equal names by different authors stay apart.
    val globalGroups = LinkedHashMap<Pair<String, String>, MutableList<RecentFileItem>>()
    val globalNames = HashMap<Pair<String, String>, String>()
    for (book in live) {
        val series = book.seriesName?.trim().orEmpty()
        if (series.isEmpty()) continue
        val primaryAuthor = splitAuthors(book.author).firstOrNull()?.let(::authorKey).orEmpty()
        val group = seriesKey(series) to primaryAuthor
        globalGroups.getOrPut(group) { ArrayList() }.add(book)
        globalNames.putIfAbsent(group, series)
    }
    val repeatedNames = globalGroups.keys.groupingBy { globalNames.getValue(it).lowercase() }.eachCount()
    val globalSeries = globalGroups
        .filter { it.value.size >= 2 }
        .map { (group, list) ->
            val baseName = globalNames.getValue(group)
            val author = displayNames[group.second]
            val name = if (author != null && (repeatedNames[baseName.lowercase()] ?: 0) > 1) "$baseName · $author" else baseName
            Shelf(
                id = "$SERIES_PREFIX${group.first}::${group.second}",
                name = name,
                type = ShelfType.SERIES,
                books = list.sortedByVolume(),
                parentShelfId = SMART_SERIES_ID,
                depth = 1,
            )
        }
        .sortedBy { it.name.lowercase() }
    everything += globalSeries

    val roots = ArrayList<Shelf>()
    if (sortedAuthors.isNotEmpty()) {
        val container = Shelf(
            id = SMART_AUTHORS_ID,
            name = authorsLabel,
            type = ShelfType.FOLDER,
            books = sortedAuthors.flatMap { it.books }.distinctBy { it.bookId },
            directBooks = emptyList(),
            childShelfIds = sortedAuthors.map { it.id },
        )
        roots += container
        everything += container
    }
    if (globalSeries.isNotEmpty()) {
        val container = Shelf(
            id = SMART_SERIES_ID,
            name = seriesLabel,
            type = ShelfType.FOLDER,
            books = globalSeries.flatMap { it.books }.distinctBy { it.bookId },
            directBooks = emptyList(),
            childShelfIds = globalSeries.map { it.id },
        )
        roots += container
        everything += container
    }
    return AuthorSeriesShelves(all = everything, roots = roots)
}

// ------------------------------------------------------------------------------ author photos

/**
 * Profile pictures of authors, kept in the app's own storage (a gallery URI can stop working
 * later). One small JPEG per author key; [version] changes whenever one is saved or removed.
 */
internal object AuthorAvatarStore {
    var version by mutableIntStateOf(0)
        private set

    private const val MAX_SIDE = 720
    private val cache = LruCache<String, ImageBitmap>(24)

    fun bump() {
        version++
    }

    private fun file(context: Context, authorKey: String): File {
        val dir = File(context.filesDir, "author_avatars").apply { if (!exists()) mkdirs() }
        val digest = MessageDigest.getInstance("SHA-1").digest(authorKey.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    fun has(context: Context, authorKey: String): Boolean = file(context, authorKey).exists()

    fun remove(context: Context, authorKey: String) {
        file(context, authorKey).delete()
        bump()
    }

    fun load(context: Context, authorKey: String): ImageBitmap? {
        val target = file(context, authorKey)
        if (!target.exists()) return null
        val cacheKey = "$authorKey@${target.lastModified()}"
        cache.get(cacheKey)?.let { return it }
        val bitmap = BitmapFactory.decodeFile(target.absolutePath)?.asImageBitmap() ?: return null
        cache.put(cacheKey, bitmap)
        return bitmap
    }

    /** Scales the picked image down to [MAX_SIDE] and stores it. Returns false when it cannot be read. */
    suspend fun save(context: Context, authorKey: String, source: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val decoded = resolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@withContext false
            val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
            } else {
                decoded
            }
            val target = file(context, authorKey)
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}

@Composable
internal fun rememberAuthorAvatar(authorKey: String): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val version = AuthorAvatarStore.version
    return produceState<ImageBitmap?>(initialValue = null, authorKey, version) {
        value = withContext(Dispatchers.IO) { AuthorAvatarStore.load(context, authorKey) }
    }.value
}

/** Tile / row preview of an author: the profile picture, or the usual cover collage without one. */
@Composable
internal fun AuthorShelfPreview(shelf: Shelf, modifier: Modifier = Modifier) {
    val avatar = rememberAuthorAvatar(authorKeyOfShelfId(shelf.id))
    if (avatar != null) {
        Image(
            bitmap = avatar,
            contentDescription = shelf.name,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
        )
    } else {
        FolderCoverCollage(shelf, modifier)
    }
}

/** Header of an opened author shelf: round picture (tap to change) and the name. */
@Composable
internal fun AuthorShelfHeader(shelf: Shelf, onPickPhoto: () -> Unit, modifier: Modifier = Modifier) {
    val avatar = rememberAuthorAvatar(authorKeyOfShelfId(shelf.id))
    Row(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { onPickPhoto() },
            contentAlignment = Alignment.Center,
        ) {
            if (avatar != null) {
                Image(avatar, shelf.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            shelf.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun AuthorShelfActionsMenu(hasPhoto: Boolean, onPickPhoto: () -> Unit, onRemovePhoto: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.content_desc_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.author_photo_choose)) },
                onClick = { expanded = false; onPickPhoto() },
            )
            if (hasPhoto) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.author_photo_remove)) },
                    onClick = { expanded = false; onRemovePhoto() },
                )
            }
        }
    }
}
