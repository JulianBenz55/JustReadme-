package com.aryan.reader

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aryan.reader.data.AppDatabase
import com.aryan.reader.data.ManualCoverStore
import com.aryan.reader.webimport.WebClipStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * Full local backup in one zip file: databases (library, shelves and their contents, reading
 * position, bookmarks, tags), all settings, annotations and other app files, and optionally the
 * book files themselves. Restoring is done in two steps: the zip is unpacked into a staging
 * folder, and the next app start swaps the files in before anything opens the database.
 *
 * What is deliberately NOT in the zip:
 *  - Automatically extracted covers (`cover_cache`). They are rebuilt from the books after a
 *    restore ([CoverRebuilder]), so they would only make the backup bigger.
 *  - Any book payload (EPUB, PDF, audio, ...) unless "include books" is on. This is decided by
 *    file extension as well as by folder, because books also live outside `books/`
 *    (`metadata_backups/`, the cloud-folder mirror, ...).
 *
 * What is always in it: covers the user picked by hand (`manual_covers`, small JPEGs) and,
 * unless switched off, the EPUBs made from the web (`web_clips`).
 */
object BackupManager {
    private const val FORMAT_VERSION = 1
    private const val MANIFEST = "backup.json"
    private const val STAGING_DIR = "pending_restore"

    private const val DB_PREFIX = "databases/"
    private const val PREFS_PREFIX = "shared_prefs/"
    private const val FILES_PREFIX = "files/"

    /**
     * Top-level folders inside `files/` that hold book files (or full copies of them, such as the
     * originals `metadata_backups/` keeps before an EPUB's metadata is edited).
     */
    private val BOOK_DIRS = setOf("books", "audiobooks", "metadata_backups")

    /**
     * Anything with one of these extensions is a book payload wherever it sits below `files/`
     * (the cloud-folder mirror keeps real EPUB/PDF files in its own tree, for example).
     */
    private val BOOK_EXTENSIONS = setOf(
        "epub", "kepub", "pdf", "mobi", "azw", "azw3", "fb2", "cbz", "cbr", "cb7", "cbt",
        "docx", "odt", "fodt", "pptx", "txt", "md", "html", "htm", "zip",
        "mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav",
    )

    /**
     * Caches that can be rebuilt; they would only make the backup larger. Automatically
     * extracted covers are rebuilt from the books after a restore, so they stay out too; covers
     * chosen by hand live in `manual_covers` and are always included.
     */
    private val SKIPPED_FILE_DIRS = setOf("TTS_Cache", "derived", "cover_cache")

    /** Databases that are generated caches or belong to the system's job scheduler, not to the user's data. */
    private fun isSkippedDatabase(name: String) =
        name.startsWith("androidx.work") ||
            name.startsWith("book_cache_database") ||
            name.startsWith("pdf_text_cache_db")

    private fun isBookPayload(relative: String): Boolean {
        if (relative.substringBefore('/') in BOOK_DIRS) return true
        return relative.substringAfterLast('/').substringAfterLast('.', "").lowercase() in BOOK_EXTENSIONS
    }

    suspend fun export(
        context: Context,
        target: Uri,
        includeBooks: Boolean,
        includeWebClips: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        val database = AppDatabase.getDatabase(context)
        // Write everything still waiting in the write-ahead log into the main database file.
        database.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }

        // Only clips of books that are still in the library go into the backup: a clip left
        // behind by a deleted book is dead weight.
        val liveBookIds = if (includeWebClips) {
            database.recentFileDao().getAllFiles().filterNot { it.isDeleted }.mapTo(HashSet()) { it.bookId }
        } else {
            emptySet<String>()
        }

        val output = context.contentResolver.openOutputStream(target)
            ?: throw IOException("Cannot write to the chosen file")
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            val manifest = JSONObject()
                .put("format", FORMAT_VERSION)
                .put("createdAt", System.currentTimeMillis())
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("includesBooks", includeBooks)
                .put("includesWebClips", includeWebClips)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            addTree(zip, File(context.dataDir, "databases"), DB_PREFIX) { relative ->
                !isSkippedDatabase(relative.substringBefore('/'))
            }
            addTree(zip, File(context.dataDir, "shared_prefs"), PREFS_PREFIX) { true }
            addTree(zip, context.filesDir, FILES_PREFIX) { relative ->
                when (relative.substringBefore('/')) {
                    in SKIPPED_FILE_DIRS -> false
                    // Hand-picked covers exist nowhere else, so they always go in.
                    ManualCoverStore.DIR -> true
                    WebClipStore.DIR -> {
                        val name = relative.substringAfter('/')
                        includeWebClips && name.endsWith(".epub") && name.removeSuffix(".epub") in liveBookIds
                    }
                    else -> includeBooks || !isBookPayload(relative)
                }
            }
        }
    }

    private fun addTree(zip: ZipOutputStream, root: File, prefix: String, include: (String) -> Boolean) {
        if (!root.isDirectory) return
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            if (!include(relative)) return@forEach
            zip.putNextEntry(ZipEntry(prefix + relative))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /** Unpacks a backup into the staging folder. Nothing in use is touched until the next start. */
    suspend fun stageRestore(context: Context, source: Uri) = withContext(Dispatchers.IO) {
        val staging = File(context.dataDir, STAGING_DIR)
        staging.deleteRecursively()
        staging.mkdirs()
        val stagingRoot = staging.canonicalPath + File.separator
        var sawManifest = false
        val input = context.contentResolver.openInputStream(source)
            ?: throw IOException("Cannot read the chosen file")
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val destination = File(staging, entry.name).canonicalFile
                    // Refuse entries that would escape the staging folder.
                    if (!destination.path.startsWith(stagingRoot)) throw IOException("Invalid entry in backup")
                    if (entry.isDirectory) {
                        destination.mkdirs()
                    } else {
                        destination.parentFile?.mkdirs()
                        destination.outputStream().use { zip.copyTo(it) }
                        if (entry.name == MANIFEST) sawManifest = true
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (!sawManifest) throw IOException("This file is not an Vellum backup")
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    /** Called first thing in Application.onCreate, before any database or settings file is opened. */
    fun applyPendingRestore(context: Context) {
        val staging = File(context.dataDir, STAGING_DIR)
        if (!File(staging, MANIFEST).exists()) return
        try {
            val stagedDatabases = File(staging, DB_PREFIX.trimEnd('/'))
            if (stagedDatabases.isDirectory) {
                val databases = File(context.dataDir, "databases")
                databases.listFiles()?.filter { !isSkippedDatabase(it.name) }?.forEach { it.delete() }
                stagedDatabases.copyRecursively(databases, overwrite = true)
            }
            val stagedPrefs = File(staging, PREFS_PREFIX.trimEnd('/'))
            if (stagedPrefs.isDirectory) {
                stagedPrefs.copyRecursively(File(context.dataDir, "shared_prefs"), overwrite = true)
            }
            val stagedFiles = File(staging, FILES_PREFIX.trimEnd('/'))
            if (stagedFiles.isDirectory) {
                stagedFiles.listFiles()?.forEach { top ->
                    val target = File(context.filesDir, top.name)
                    target.deleteRecursively()
                    top.copyRecursively(target, overwrite = true)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BackupManager", "Restore failed", e)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Restarts the app so the staged restore is applied before anything else runs. */
    fun restartApp(context: Context): Nothing {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pending = PendingIntent.getActivity(
                context,
                0,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            )
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarms.set(AlarmManager.RTC, System.currentTimeMillis() + 400, pending)
        }
        exitProcess(0)
    }
}
