package com.aryan.reader.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/** Tags: definitions, visibility, manual/automatic assignments, series assignments and inheritance. */
class TagRepository(private val database: AppDatabase) {
    private val tagDao = database.tagDao()
    private val seriesDao = database.seriesDao()

    fun observeTags(): Flow<List<TagEntity>> = tagDao.getAllTags()

    /** Direct plus inherited tags of every book. */
    fun observeEffectiveBookTags(): Flow<List<BookTagCrossRef>> = seriesDao.observeEffectiveBookTags()

    fun observeSeriesAssignments(): Flow<List<SeriesTagAssignmentEntity>> = seriesDao.observeAssignments()

    suspend fun setEnabled(tagId: String, enabled: Boolean) = tagDao.setEnabled(tagId, enabled)

    suspend fun setVisibleInNavigation(tagId: String, visible: Boolean) = tagDao.setVisibleInNavigation(tagId, visible)

    suspend fun setAutoAssignment(tagId: String, enabled: Boolean) = tagDao.setTrackingEnabled(tagId, enabled)

    suspend fun setDisplayOptions(tagId: String, showIcon: Boolean, showDescription: Boolean, showCount: Boolean) =
        tagDao.setDisplayOptions(tagId, showIcon, showDescription, showCount)

    /** Manual assignment; never touched by the automatic tracker's on/off switch. */
    suspend fun assignToBooks(tagId: String, bookIds: Collection<String>) {
        database.withTransaction { bookIds.forEach { tagDao.insertBookTagCrossRef(BookTagCrossRef(it, tagId)) } }
    }

    suspend fun removeFromBooks(tagId: String, bookIds: Collection<String>) {
        database.withTransaction { bookIds.forEach { tagDao.removeTagFromBook(tagId, it) } }
    }

    /**
     * Assigns a tag to a whole series. [inheritMode] decides whether the volumes get it too:
     * only the series, the volumes that exist now, or those plus every future volume.
     * Direct tags on the volumes are never changed.
     */
    suspend fun assignToSeries(
        seriesName: String,
        tagId: String,
        inheritMode: String,
        isAutomatic: Boolean = false,
        now: Long = System.currentTimeMillis()
    ) {
        val key = seriesKeyOf(seriesName)
        if (key.isEmpty() || tagDao.getTagById(tagId) == null) return
        database.withTransaction {
            seriesDao.insertSeriesIfAbsent(SeriesEntity(key, seriesName.trim(), createdAt = now))
            seriesDao.upsertAssignment(SeriesTagAssignmentEntity(key, tagId, inheritMode, isAutomatic, now))
            seriesDao.deleteInheritedForAssignment(key, tagId)
            if (inheritMode != INHERIT_SERIES_ONLY) {
                val volumes = seriesDao.getSeriesBookRows().filter { seriesKeyOf(it.seriesName) == key }
                seriesDao.insertInherited(volumes.map { BookTagInheritedEntity(it.bookId, tagId, key) })
            }
        }
    }

    /** Removes the series assignment and only the tags inherited from it. */
    suspend fun removeFromSeries(seriesName: String, tagId: String) {
        val key = seriesKeyOf(seriesName)
        database.withTransaction {
            seriesDao.deleteInheritedForAssignment(key, tagId)
            seriesDao.deleteAssignment(key, tagId)
        }
    }

    /** Creates a tag from shared data without ever resetting an existing one. */
    suspend fun createOrUpdateNameAndColor(tag: TagEntity) {
        database.withTransaction {
            if (tagDao.insertTag(tag) == -1L) tagDao.updateTagNameAndColor(tag.id, tag.name, tag.color)
        }
    }
}

/** Series metadata, progress and the reconciliation of inherited tags. */
class SeriesRepository(private val database: AppDatabase) {
    private val seriesDao = database.seriesDao()
    private val started = AtomicBoolean(false)

    fun observeSeries(): Flow<List<SeriesEntity>> = seriesDao.observeAllSeries()

    suspend fun setSeriesInfo(seriesName: String, knownVolumeCount: Int?, isStoryComplete: Boolean, now: Long = System.currentTimeMillis()) {
        val key = seriesKeyOf(seriesName)
        if (key.isEmpty()) return
        database.withTransaction {
            seriesDao.insertSeriesIfAbsent(SeriesEntity(key, seriesName.trim(), createdAt = now))
            seriesDao.updateSeriesMeta(key, knownVolumeCount?.takeIf { it > 0 }, isStoryComplete)
        }
    }

    /** Progress per series key, computed from the volumes (never stored, so it cannot go stale). */
    suspend fun getProgress(): Map<String, SeriesProgress> {
        val volumes = seriesDao.getSeriesVolumeRows().groupBy { seriesKeyOf(it.seriesName) }
        return volumes.mapValues { (key, rows) ->
            val info = seriesDao.getSeries(key)
            SeriesProgressCalculator.calculate(
                rows.map { VolumeState(finished = it.isFinished, started = it.hasStarted) },
                info?.knownVolumeCount,
                info?.isStoryComplete ?: false
            )
        }
    }

    /**
     * Brings series rows and inherited tags in line with the books' current series names, in one
     * transaction. Idempotent: running it twice changes nothing the second time.
     */
    suspend fun reconcile(now: Long = System.currentTimeMillis()) {
        database.withTransaction {
            val rows = seriesDao.getSeriesBookRows()
            val volumesBySeries = rows.groupBy { seriesKeyOf(it.seriesName) }
                .mapValues { (_, v) -> v.map { it.bookId }.toSet() }
            rows.distinctBy { seriesKeyOf(it.seriesName) }.forEach {
                seriesDao.insertSeriesIfAbsent(SeriesEntity(seriesKeyOf(it.seriesName), it.seriesName.trim(), createdAt = now))
            }
            val assignments = seriesDao.getAllAssignments()
            val existing = seriesDao.getAllInherited().map { InheritedRef(it.bookId, it.tagId, it.seriesKey) }.toSet()
            val plan = TagInheritancePlanner.plan(
                volumesBySeries,
                assignments.map { InheritanceAssignment(it.seriesKey, it.tagId, it.inheritMode) },
                existing
            )
            plan.toRemove.forEach { seriesDao.deleteInherited(it.bookId, it.tagId, it.seriesKey) }
            seriesDao.insertInherited(plan.toAdd.map { BookTagInheritedEntity(it.bookId, it.tagId, it.seriesKey) })
        }
    }

    /** Starts watching the books' series names; new volumes get inherited tags without any UI. */
    @OptIn(FlowPreview::class)
    fun startReconciler(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            seriesDao.observeSeriesBookRows()
                .map { rows -> rows.map { it.bookId to seriesKeyOf(it.seriesName) }.toSet() }
                .distinctUntilChanged()
                .debounce(300)
                .conflate()
                .collect {
                    try {
                        reconcile()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Series reconcile failed")
                    }
                }
        }
    }
}

/**
 * Stores every confirmed read position at once, in its own transaction, so nothing relevant lives
 * only in memory. The page gate decides what counts as read; the primary key prevents double counts.
 */
class ReadingPositionRecorder(
    private val database: AppDatabase,
    private val settingsStore: TagTrackingSettingsStore
) {
    private val readDao = database.readPositionDao()
    private val trackingDao = database.readingTrackingDao()
    private val recentFileDao = database.recentFileDao()
    private val gate = ReadPageGate { settingsStore.read().minPageDwellMs }

    /** Reports the position now on screen. Returns how many positions were newly recorded. */
    suspend fun onPosition(bookId: String, positionKey: String, totalUnits: Int?, now: Long = System.currentTimeMillis()): Int =
        persist(bookId, gate.onPosition(bookId, positionKey, now), totalUnits, now)

    /** Call when the reader pauses or closes. */
    suspend fun flush(bookId: String, totalUnits: Int? = null, now: Long = System.currentTimeMillis()): Int =
        persist(bookId, gate.flush(bookId, now), totalUnits, now)

    suspend fun setCoversWholeWork(bookId: String, coversWholeWork: Boolean) {
        readDao.upsertWorkInfo(BookWorkInfoEntity(bookId, coversWholeWork))
    }

    private suspend fun persist(bookId: String, keys: List<String>, totalUnits: Int?, now: Long): Int {
        if (keys.isEmpty()) return 0
        return try {
            database.withTransaction {
                if (recentFileDao.getFileByBookId(bookId) == null) return@withTransaction 0
                val sessionId = trackingDao.getLatestSession(bookId)?.takeIf { it.finishedAt == null }?.id
                val added = keys.count { readDao.insertPosition(ReadPositionEntity(bookId, it, now, sessionId)) != -1L }
                val previous = readDao.getStats(bookId)
                readDao.upsertStats(
                    BookReadStatsEntity(
                        bookId = bookId,
                        totalUnits = totalUnits ?: previous?.totalUnits,
                        readUnits = readDao.countPositions(bookId),
                        lastPositionKey = keys.last(),
                        lastUpdatedAt = now
                    )
                )
                added
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Recording read position failed")
            0
        }
    }
}

/**
 * Series-level automatic status. A series gets "Reading" once a volume was read and "Finished" only
 * when every volume is read AND the story is marked complete. Only rows it created itself
 * (`isAutomatic`) are ever removed, and it acts only when the status changes, so a tag the user
 * removed by hand is not put back on the next page.
 */
class ReadingStatusTracker(
    private val database: AppDatabase,
    private val settingsStore: TagTrackingSettingsStore
) {
    private val seriesDao = database.seriesDao()
    private val tagDao = database.tagDao()
    private val recentFileDao = database.recentFileDao()

    suspend fun onBookChanged(bookId: String, now: Long = System.currentTimeMillis()) {
        try {
            database.withTransaction {
                val name = recentFileDao.getFileByBookId(bookId)?.seriesName?.takeIf { it.isNotBlank() }
                    ?: return@withTransaction
                evaluate(name, now)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Series status update failed")
        }
    }

    private suspend fun evaluate(seriesName: String, now: Long) {
        val key = seriesKeyOf(seriesName)
        seriesDao.insertSeriesIfAbsent(SeriesEntity(key, seriesName.trim(), createdAt = now))
        val series = seriesDao.getSeries(key) ?: return
        val volumes = seriesDao.getSeriesVolumeRows().filter { seriesKeyOf(it.seriesName) == key }
        val progress = SeriesProgressCalculator.calculate(
            volumes.map { VolumeState(it.isFinished, it.hasStarted) },
            series.knownVolumeCount,
            series.isStoryComplete
        )
        if (progress.status.name == series.autoStatus) return
        val settings = settingsStore.read()
        val reading = tagDao.getTagById(SYSTEM_TAG_READING)
            ?.takeIf { it.trackingEnabled && it.isEnabled && settings.autoAssignReading }
        val finished = tagDao.getTagById(SYSTEM_TAG_FINISHED)
            ?.takeIf { it.trackingEnabled && it.isEnabled && settings.autoAssignFinished }

        when (progress.status) {
            SeriesStatus.NOT_STARTED -> Unit
            SeriesStatus.PARTIALLY_READ, SeriesStatus.ALL_PUBLISHED_READ -> {
                if (reading != null) addAuto(key, reading.id, now, removeToRead = settings.removeToReadWhenReading)
                finished?.let { removeAuto(key, it.id) }
            }
            SeriesStatus.COMPLETED -> {
                if (finished != null) {
                    addAuto(key, finished.id, now, removeToRead = settings.removeToReadWhenReading)
                    reading?.let { removeAuto(key, it.id) }
                } else if (reading != null) {
                    addAuto(key, reading.id, now, removeToRead = false)
                }
            }
        }
        seriesDao.updateAutoStatus(key, progress.status.name)
    }

    private suspend fun addAuto(key: String, tagId: String, now: Long, removeToRead: Boolean) {
        // A manual assignment of the same tag is left exactly as it is.
        if (seriesDao.getAssignment(key, tagId) == null) {
            seriesDao.upsertAssignment(
                SeriesTagAssignmentEntity(key, tagId, INHERIT_SERIES_ONLY, isAutomatic = true, createdAt = now)
            )
        }
        if (removeToRead) removeAuto(key, SYSTEM_TAG_TO_READ, onlyAutomatic = false)
    }

    private suspend fun removeAuto(key: String, tagId: String, onlyAutomatic: Boolean = true) {
        val a = seriesDao.getAssignment(key, tagId) ?: return
        if (onlyAutomatic && !a.isAutomatic) return
        seriesDao.deleteInheritedForAssignment(key, tagId)
        seriesDao.deleteAssignment(key, tagId)
    }
}
