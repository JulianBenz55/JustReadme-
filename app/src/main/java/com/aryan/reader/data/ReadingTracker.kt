package com.aryan.reader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber

/** What happens to an already finished book when it is read again. */
enum class RereadMode {
    /** Keep the Finished tag; the new pass is only recorded as a reading session. */
    KEEP_FINISHED,

    /** Remove Finished and tag the book as Reading again once the new pass is under way. */
    RESTART
}

/** Global tracking options. Per-tag on/off lives in `TagEntity.trackingEnabled`. */
data class TagTrackingSettings(
    val removeToReadWhenReading: Boolean = true,
    val rereadMode: RereadMode = RereadMode.KEEP_FINISHED
)

class TagTrackingSettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): TagTrackingSettings {
        val mode = runCatching {
            RereadMode.valueOf(prefs.getString(KEY_REREAD_MODE, RereadMode.KEEP_FINISHED.name).orEmpty())
        }.getOrDefault(RereadMode.KEEP_FINISHED)
        return TagTrackingSettings(
            removeToReadWhenReading = prefs.getBoolean(KEY_REMOVE_TO_READ, true),
            rereadMode = mode
        )
    }

    fun write(settings: TagTrackingSettings) {
        prefs.edit()
            .putBoolean(KEY_REMOVE_TO_READ, settings.removeToReadWhenReading)
            .putString(KEY_REREAD_MODE, settings.rereadMode.name)
            .apply()
    }

    fun observe(): Flow<TagTrackingSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val PREFS_NAME = "vellum_tag_tracking"
        const val KEY_REMOVE_TO_READ = "remove_to_read_when_reading"
        const val KEY_REREAD_MODE = "reread_mode"
    }
}

/** Pure decision rules (no database), kept separate so they can be unit tested. */
object ReadingTrackingRules {
    /** Reading is only confirmed after this many percentage points of real progress in a pass. */
    const val READING_DELTA_PERCENT = 3f

    /** Progress from which the end of the book counts as reached. */
    const val FINISH_PERCENT = 99.5f

    /** Finished is accepted at once if the pass has previously been at least this far. */
    const val FINISH_APPROACH_PERCENT = 90f

    /** Otherwise the end must have been reached this long ago (jump to the last page protection). */
    const val FINISH_DWELL_MS = 30_000L

    /** A finished book whose progress falls below this starts a new reading pass. */
    const val REREAD_RESTART_BELOW_PERCENT = 90f

    fun isReadingConfirmed(startProgress: Float, progress: Float): Boolean =
        progress - startProgress >= READING_DELTA_PERCENT

    fun isFinishConfirmed(previousMaxProgress: Float, progress: Float, endReachedAt: Long?, now: Long): Boolean {
        if (progress < FINISH_PERCENT) return false
        if (previousMaxProgress >= FINISH_APPROACH_PERCENT) return true
        return endReachedAt != null && now - endReachedAt >= FINISH_DWELL_MS
    }

    fun startsNewPass(latestPassFinished: Boolean, progress: Float): Boolean =
        latestPassFinished && progress < REREAD_RESTART_BELOW_PERCENT
}

/**
 * Derives the Reading / Finished tags (and the To Read cleanup) from reading progress.
 *
 * It only ever touches the ids default_reading, default_finished and default_to_read, never
 * Favorites or Reference. Every automatic change is written to `tag_tracking_events`, and
 * everything happens in one Room transaction. Tag changes are applied once per reading pass
 * (edge-triggered), so a tag the user removes by hand is not put back during the same pass.
 */
class ReadingTracker(
    private val database: AppDatabase,
    private val settingsStore: TagTrackingSettingsStore
) {
    private val tagDao = database.tagDao()
    private val trackingDao = database.readingTrackingDao()
    private val recentFileDao = database.recentFileDao()

    /** [progress] is a percentage in 0..100; values <= 0 mean "unknown" and are ignored. */
    suspend fun onProgress(bookId: String, progress: Float, now: Long = System.currentTimeMillis()) {
        if (progress.isNaN() || progress <= 0f) return
        val p = progress.coerceIn(0f, 100f)
        try {
            database.withTransaction { process(bookId, p, now) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tracking must never break saving the reading position.
            Timber.w(e, "Reading tracker failed for book")
        }
    }

    private suspend fun process(bookId: String, p: Float, now: Long) {
        if (recentFileDao.getFileByBookId(bookId) == null) return
        val settings = settingsStore.read()

        val latest = trackingDao.getLatestSession(bookId)
        if (latest != null && latest.finishedAt != null &&
            !ReadingTrackingRules.startsNewPass(latestPassFinished = true, progress = p)
        ) {
            // Still lingering at the end of a finished pass: nothing to track.
            return
        }
        val openSession = latest?.takeIf { it.finishedAt == null }
        val finishedBefore = trackingDao.countFinishedSessions(bookId)
        val session = openSession ?: run {
            val draft = ReadingSessionEntity(
                bookId = bookId,
                startedAt = now,
                startProgress = p,
                endProgress = p,
                maxProgress = p,
                lastUpdatedAt = now
            )
            draft.copy(id = trackingDao.insertSession(draft))
        }

        val previousMax = session.maxProgress
        val previousEndReachedAt = session.endReachedAt
        var updated = session.copy(
            endProgress = p,
            maxProgress = maxOf(previousMax, p),
            lastUpdatedAt = now
        )

        val readingTag = tagDao.getTagById(SYSTEM_TAG_READING)?.takeIf { it.trackingEnabled }
        val finishedTag = tagDao.getTagById(SYSTEM_TAG_FINISHED)?.takeIf { it.trackingEnabled }

        if (updated.readingAwardedAt == null &&
            ReadingTrackingRules.isReadingConfirmed(updated.startProgress, p) &&
            readingTag != null
        ) {
            val isReread = finishedBefore > 0
            if (!isReread || settings.rereadMode == RereadMode.RESTART) {
                val reason = if (isReread) REASON_REREAD_STARTED else REASON_READING_STARTED
                if (isReread && finishedTag != null) {
                    removeTag(bookId, finishedTag.id, REASON_REREAD_STARTED, p, updated.id, now)
                }
                addTag(bookId, readingTag.id, reason, p, updated.id, now)
            }
            if (settings.removeToReadWhenReading) {
                removeTag(bookId, SYSTEM_TAG_TO_READ, REASON_READING_STARTED, p, updated.id, now)
            }
            updated = updated.copy(readingAwardedAt = now)
        }

        if (updated.finishedAt == null) {
            if (ReadingTrackingRules.isFinishConfirmed(previousMax, p, previousEndReachedAt, now)) {
                updated = updated.copy(finishedAt = now)
                if (finishedTag != null) {
                    addTag(bookId, finishedTag.id, REASON_FINISHED, p, updated.id, now)
                    if (readingTag != null) {
                        removeTag(bookId, readingTag.id, REASON_FINISHED, p, updated.id, now)
                    }
                    if (settings.removeToReadWhenReading) {
                        removeTag(bookId, SYSTEM_TAG_TO_READ, REASON_FINISHED, p, updated.id, now)
                    }
                }
            } else if (p >= ReadingTrackingRules.FINISH_PERCENT && updated.endReachedAt == null) {
                updated = updated.copy(endReachedAt = now)
            }
        }

        trackingDao.updateSession(updated)
    }

    private suspend fun addTag(bookId: String, tagId: String, reason: String, progress: Float, sessionId: Long, now: Long) {
        if (tagDao.getTagById(tagId) == null) return
        if (tagDao.countBookTag(bookId, tagId) > 0) return
        tagDao.insertBookTagCrossRef(BookTagCrossRef(bookId, tagId))
        trackingDao.insertEvent(
            TagTrackingEventEntity(
                bookId = bookId,
                tagId = tagId,
                action = TRACKING_ACTION_ADDED,
                reason = reason,
                progress = progress,
                sessionId = sessionId,
                createdAt = now
            )
        )
    }

    private suspend fun removeTag(bookId: String, tagId: String, reason: String, progress: Float, sessionId: Long, now: Long) {
        if (tagDao.countBookTag(bookId, tagId) == 0) return
        tagDao.removeTagFromBook(tagId, bookId)
        trackingDao.insertEvent(
            TagTrackingEventEntity(
                bookId = bookId,
                tagId = tagId,
                action = TRACKING_ACTION_REMOVED,
                reason = reason,
                progress = progress,
                sessionId = sessionId,
                createdAt = now
            )
        )
    }

    private companion object {
        const val REASON_READING_STARTED = "reading_started"
        const val REASON_REREAD_STARTED = "reread_started"
        const val REASON_FINISHED = "finished"
    }
}
