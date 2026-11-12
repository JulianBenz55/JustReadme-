package com.aryan.reader.webimport

import android.content.Context
import com.aryan.reader.FileHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Durable copies of EPUBs created from the web (page clips, novels, feeds, downloaded EPUBs).
 *
 * The import into the library works from a temporary file in the cache, and a clipped page often
 * cannot be fetched again (login, paywall, page changed). Each clip is therefore also kept as
 * `web_clips/<sha256>.epub` — the library uses the same SHA-256 as the book id, so a restored
 * library row finds its clip again by id alone.
 */
internal object WebClipStore {
    const val DIR = "web_clips"

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun fileFor(context: Context, bookId: String): File = File(dir(context), "${safeId(bookId)}.epub")

    /** Only hex hashes are valid names; anything else (cloud ids, reflow ids) is never a clip. */
    private fun safeId(bookId: String): String = bookId.filter { it.isLetterOrDigit() }

    /** Stores a copy of [file] when it is an EPUB. Failures are logged and never block the import. */
    suspend fun keep(context: Context, file: File) {
        if (!file.isFile || !file.extension.equals("epub", ignoreCase = true)) return
        val hash = FileHasher.calculateSha256 { file.inputStream() } ?: return
        withContext(Dispatchers.IO) { copyIfNeeded(context, file, hash) }
    }

    private fun copyIfNeeded(context: Context, file: File, hash: String) {
        try {
            val target = fileFor(context, hash)
            if (target.isFile && target.length() == file.length()) return
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
            try {
                file.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                if (target.exists()) target.delete()
                if (!temporary.renameTo(target)) error("Could not move web clip into place")
            } finally {
                temporary.delete()
            }
        } catch (e: Exception) {
            Timber.tag("WebClipStore").e(e, "Could not keep a copy of ${file.name}")
        }
    }

    fun existing(context: Context, bookId: String): File? =
        fileFor(context, bookId).takeIf { it.isFile && it.length() > 0L }

    /**
     * Copies the clip of [bookId] back into the app's book folder under a fresh name and returns
     * that file, or null when there is no clip for this id.
     */
    fun restoreIntoBooks(context: Context, bookId: String, createBookFile: (String) -> File): File? {
        val clip = existing(context, bookId) ?: return null
        return try {
            val destination = createBookFile("${UUID.randomUUID()}.epub")
            clip.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
            destination
        } catch (e: Exception) {
            Timber.tag("WebClipStore").e(e, "Could not restore web clip $bookId")
            null
        }
    }
}
