package com.aryan.reader

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aryan.reader.data.AppDatabase
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
 */
object BackupManager {
    private const val FORMAT_VERSION = 1
    private const val MANIFEST = "backup.json"
    private const val STAGING_DIR = "pending_restore"

    private const val DB_PREFIX = "databases/"
    private const val PREFS_PREFIX = "shared_prefs/"
    private const val FILES_PREFIX = "files/"

    /** Top-level folders inside `files/` that hold the book files themselves. */
    private val BOOK_DIRS = setOf("books", "audiobooks")

    /**
     * Caches that can be rebuilt; they would only make the backup larger. Covers are
     * deliberately included: they are small, and restoring without them leaves the
     * library showing blank covers until every book has been opened again.
     */
    private val SKIPPED_FILE_DIRS = setOf("TTS_Cache", "derived")

    /** Databases that belong to the system's job scheduler, not to the user's data. */
    private fun isSkippedDatabase(name: String) = name.startsWith("androidx.work")

    suspend fun export(context: Context, target: Uri, includeBooks: Boolean) = withContext(Dispatchers.IO) {
        // Write everything still waiting in the write-ahead log into the main database file.
        AppDatabase.getDatabase(context).openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }

        val output = context.contentResolver.openOutputStream(target)
            ?: throw IOException("Cannot write to the chosen file")
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            val manifest = JSONObject()
                .put("format", FORMAT_VERSION)
                .put("createdAt", System.currentTimeMillis())
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("includesBooks", includeBooks)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            addTree(zip, File(context.dataDir, "databases"), DB_PREFIX) { relative ->
                !isSkippedDatabase(relative.substringBefore('/'))
            }
            addTree(zip, File(context.dataDir, "shared_prefs"), PREFS_PREFIX) { true }
            addTree(zip, context.filesDir, FILES_PREFIX) { relative ->
                val top = relative.substringBefore('/')
                top !in SKIPPED_FILE_DIRS && (includeBooks || top !in BOOK_DIRS)
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
