package com.aryan.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * Covers the user picked by hand. Unlike automatically extracted covers (which can always be
 * rebuilt from the book itself and therefore stay out of backups), a manual cover exists nowhere
 * else, so it is kept in its own folder as a small JPEG that the backup always includes.
 *
 * The file name is derived from the book id, so after a restore the cover can be matched to the
 * right book again without any extra bookkeeping in the database.
 */
internal object ManualCoverStore {
    const val DIR = "manual_covers"
    private const val MAX_DIMENSION = 1000
    private const val JPEG_QUALITY = 85
    private const val MAX_SOURCE_BYTES = 24 * 1024 * 1024

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun fileFor(context: Context, bookId: String): File = File(
        dir(context),
        AndroidBookArtifactPaths.sidecarName(
            prefix = "manual_",
            sanitizedId = AndroidBookArtifactPaths.safeBookId(bookId),
            stableKey = bookId,
            extension = ".jpg",
        ),
    )

    /** The stored manual cover of [bookId], or null when the user never set one. */
    fun existing(context: Context, bookId: String): File? =
        fileFor(context, bookId).takeIf { it.isFile && it.length() > 0L }

    /**
     * Scales [bytes] down (longest side [MAX_DIMENSION]) and stores it as JPEG, replacing any
     * earlier manual cover of the book. Returns the absolute path, or null if the bytes are not
     * a decodable image.
     */
    suspend fun save(context: Context, bookId: String, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) { saveBlocking(context, bookId, bytes) }

    private fun saveBlocking(context: Context, bookId: String, bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MAX_SOURCE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (
            bounds.outWidth / (sampleSize * 2) >= MAX_DIMENSION ||
            bounds.outHeight / (sampleSize * 2) >= MAX_DIMENSION
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null

        var scaled: Bitmap? = null
        var flattened: Bitmap? = null
        val target = fileFor(context, bookId)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        return try {
            val longest = maxOf(decoded.width, decoded.height)
            val source = if (longest > MAX_DIMENSION) {
                val ratio = MAX_DIMENSION.toFloat() / longest
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true,
                ).also { scaled = it }
            } else {
                decoded
            }
            // JPEG has no alpha channel: paint onto white so transparent PNG covers do not turn black.
            flattened = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { out ->
                val canvas = Canvas(out)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            target.parentFile?.mkdirs()
            FileOutputStream(temporary).use { output ->
                if (!flattened!!.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    error("JPEG compression failed")
                }
            }
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) error("Could not move manual cover into place")
            target.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "Failed to store manual cover for $bookId")
            temporary.delete()
            null
        } finally {
            flattened?.recycle()
            scaled?.recycle()
            decoded.recycle()
        }
    }

    fun delete(context: Context, bookId: String) {
        runCatching { fileFor(context, bookId).delete() }
    }

    /**
     * After a restore the library rows still carry the cover path of the old install. Rows whose
     * cover file is gone but that have a stored manual cover get it back; nothing else is touched.
     * Returns how many books were re-linked.
     */
    suspend fun relinkMissingCovers(context: Context): Int {
        if (!dir(context).isDirectory) return 0
        val dao = AppDatabase.getDatabase(context).recentFileDao()
        var relinked = 0
        dao.getAllFiles().filterNot { it.isDeleted }.forEach { entity ->
            val manual = existing(context, entity.bookId) ?: return@forEach
            val current = entity.coverImagePath
            if (current.isNullOrBlank() || !File(current).isFile) {
                dao.setCoverPath(entity.bookId, manual.absolutePath)
                relinked++
            }
        }
        if (relinked > 0) Timber.tag("ManualCover").i("Re-linked $relinked manual cover(s)")
        return relinked
    }
}
