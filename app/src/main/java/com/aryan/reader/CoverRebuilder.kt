package com.aryan.reader

import android.content.Context
import android.net.Uri
import com.aryan.reader.data.AppDatabase
import com.aryan.reader.data.RecentFileEntity
import com.aryan.reader.data.RecentFilesRepository
import com.aryan.reader.data.toRecentFileItem
import com.aryan.reader.pdf.PdfCoverGenerator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.File

/**
 * Reads the first page / embedded cover of every book that has no usable cover file and stores
 * it. This is what replaces covers in backups: automatically extracted covers are not saved at
 * all, they are rebuilt from the books themselves after a restore (and for any other book whose
 * cover went missing). Safe to run repeatedly; books that already have a cover are skipped.
 */
internal object CoverRebuilder {
    private const val TAG = "CoverRebuilder"

    /** Returns how many covers were created. */
    suspend fun rebuildMissing(context: Context): Int {
        val appContext = context.applicationContext
        val dao = AppDatabase.getDatabase(appContext).recentFileDao()
        val pending = dao.getAllFiles().filter { entity ->
            !entity.isDeleted && entity.isAvailable && !entity.hasUsableCover()
        }
        if (pending.isEmpty()) return 0

        val repository = RecentFilesRepository(appContext)
        val pdfCovers = PdfCoverGenerator(appContext)
        val thumbnails = ContentThumbnailGenerator(appContext)
        var rebuilt = 0
        pending.forEach { entity ->
            currentCoroutineContext().ensureActive()
            val uri = entity.uriString?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return@forEach
            if (!isReadable(appContext, uri)) return@forEach
            val path = try {
                createCover(appContext, entity, uri, repository, pdfCovers, thumbnails)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "No cover for ${entity.displayName}")
                null
            }
            if (path != null) {
                dao.setCoverPath(entity.bookId, path)
                rebuilt++
            }
        }
        if (rebuilt > 0) Timber.tag(TAG).i("Rebuilt $rebuilt of ${pending.size} missing cover(s)")
        return rebuilt
    }

    private suspend fun createCover(
        context: Context,
        entity: RecentFileEntity,
        uri: Uri,
        repository: RecentFilesRepository,
        pdfCovers: PdfCoverGenerator,
        thumbnails: ContentThumbnailGenerator,
    ): String? {
        when (entity.type) {
            FileType.PDF -> {
                val bitmap = pdfCovers.generateCover(uri)
                if (bitmap != null) {
                    try {
                        repository.saveCoverToCache(bitmap, uri)?.let { return it }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            FileType.EPUB, FileType.MOBI, FileType.FB2 -> {
                val cover = EmbeddedEbookMetadataExtractor.extract(
                    type = entity.type,
                    displayName = entity.displayName,
                    openStream = { context.contentResolver.openInputStream(uri) },
                    extractCover = true,
                ).cover
                if (cover != null) {
                    repository.saveEmbeddedCoverToCache(cover.bytes, uri, cover.extension)?.let { return it }
                }
            }
            else -> Unit
        }
        // Everything else (and books without an embedded cover): the generic first-page thumbnail.
        val item = entity.toRecentFileItem()
        val thumbnail = thumbnails.generate(item) ?: return null
        return try {
            repository.saveCoverToCache(thumbnail, uri)
        } finally {
            thumbnail.recycle()
        }
    }

    private fun RecentFileEntity.hasUsableCover(): Boolean {
        val path = coverImagePath
        return !path.isNullOrBlank() && File(path).isFile
    }

    /** SAF providers can be unmounted or permission-gated: a book that cannot be opened is skipped, not failed. */
    private fun isReadable(context: Context, uri: Uri): Boolean = try {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).isFile } == true
        } else {
            context.contentResolver.openInputStream(uri)?.use { true } == true
        }
    } catch (_: Exception) {
        false
    }
}
