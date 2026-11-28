package com.aryan.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A book that belongs to a series (as far as its metadata says). */
data class SeriesBookRow(val bookId: String, val seriesName: String, val seriesIndex: Double?)

/** Per-volume state for the series progress; computed in SQL from sessions, stats and the tag. */
data class SeriesVolumeRow(
    val bookId: String,
    val seriesName: String,
    val isFinished: Boolean,
    val hasStarted: Boolean
)

@Dao
interface SeriesDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeriesIfAbsent(series: SeriesEntity): Long

    @Query("SELECT * FROM series WHERE seriesKey = :key")
    suspend fun getSeries(key: String): SeriesEntity?

    @Query("SELECT * FROM series ORDER BY name COLLATE NOCASE")
    fun observeAllSeries(): Flow<List<SeriesEntity>>

    @Query("UPDATE series SET knownVolumeCount = :count, isStoryComplete = :complete WHERE seriesKey = :key")
    suspend fun updateSeriesMeta(key: String, count: Int?, complete: Boolean)

    @Query("UPDATE series SET autoStatus = :status WHERE seriesKey = :key")
    suspend fun updateAutoStatus(key: String, status: String)

    @Query(
        "SELECT bookId, seriesName, seriesIndex FROM recent_files " +
            "WHERE isDeleted = 0 AND seriesName IS NOT NULL AND TRIM(seriesName) <> ''"
    )
    suspend fun getSeriesBookRows(): List<SeriesBookRow>

    @Query(
        "SELECT bookId, seriesName, seriesIndex FROM recent_files " +
            "WHERE isDeleted = 0 AND seriesName IS NOT NULL AND TRIM(seriesName) <> ''"
    )
    fun observeSeriesBookRows(): Flow<List<SeriesBookRow>>

    @Query(
        """
        SELECT r.bookId AS bookId, r.seriesName AS seriesName,
          (EXISTS(SELECT 1 FROM reading_sessions s WHERE s.bookId = r.bookId AND s.finishedAt IS NOT NULL)
            OR EXISTS(SELECT 1 FROM book_tag_cross_ref x WHERE x.bookId = r.bookId AND x.tagId = 'default_finished')) AS isFinished,
          (EXISTS(SELECT 1 FROM book_read_stats b WHERE b.bookId = r.bookId AND b.readUnits > 0)
            OR COALESCE(r.progressPercentage, 0) > 0) AS hasStarted
        FROM recent_files r
        WHERE r.isDeleted = 0 AND r.seriesName IS NOT NULL AND TRIM(r.seriesName) <> ''
        """
    )
    suspend fun getSeriesVolumeRows(): List<SeriesVolumeRow>

    // ---- assignments ----
    @Upsert
    suspend fun upsertAssignment(assignment: SeriesTagAssignmentEntity)

    @Query("SELECT * FROM series_tag_assignments")
    suspend fun getAllAssignments(): List<SeriesTagAssignmentEntity>

    @Query("SELECT * FROM series_tag_assignments")
    fun observeAssignments(): Flow<List<SeriesTagAssignmentEntity>>

    @Query("SELECT * FROM series_tag_assignments WHERE seriesKey = :key")
    suspend fun getAssignmentsForSeries(key: String): List<SeriesTagAssignmentEntity>

    @Query("SELECT * FROM series_tag_assignments WHERE seriesKey = :key AND tagId = :tagId")
    suspend fun getAssignment(key: String, tagId: String): SeriesTagAssignmentEntity?

    @Query("DELETE FROM series_tag_assignments WHERE seriesKey = :key AND tagId = :tagId")
    suspend fun deleteAssignment(key: String, tagId: String): Int

    // ---- inherited tags ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInherited(rows: List<BookTagInheritedEntity>)

    @Query("SELECT * FROM book_tag_inherited")
    suspend fun getAllInherited(): List<BookTagInheritedEntity>

    @Query("DELETE FROM book_tag_inherited WHERE bookId = :bookId AND tagId = :tagId AND seriesKey = :seriesKey")
    suspend fun deleteInherited(bookId: String, tagId: String, seriesKey: String)

    @Query("DELETE FROM book_tag_inherited WHERE seriesKey = :seriesKey AND tagId = :tagId")
    suspend fun deleteInheritedForAssignment(seriesKey: String, tagId: String)

    /** Direct plus inherited tags of every book; the one source the UI should use for "tags of a book". */
    @Query(
        "SELECT bookId, tagId FROM book_tag_cross_ref " +
            "UNION SELECT bookId, tagId FROM book_tag_inherited"
    )
    fun observeEffectiveBookTags(): Flow<List<BookTagCrossRef>>
}

@Dao
interface ReadPositionDao {
    /** Returns -1 if the position was already recorded (primary key conflict is ignored). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPosition(position: ReadPositionEntity): Long

    @Query("SELECT COUNT(*) FROM read_positions WHERE bookId = :bookId")
    suspend fun countPositions(bookId: String): Int

    @Query("SELECT positionKey FROM read_positions WHERE bookId = :bookId")
    suspend fun getPositionKeys(bookId: String): List<String>

    @Upsert
    suspend fun upsertStats(stats: BookReadStatsEntity)

    @Query("SELECT * FROM book_read_stats WHERE bookId = :bookId")
    suspend fun getStats(bookId: String): BookReadStatsEntity?

    @Query("SELECT * FROM book_read_stats")
    fun observeAllStats(): Flow<List<BookReadStatsEntity>>

    @Upsert
    suspend fun upsertWorkInfo(info: BookWorkInfoEntity)

    @Query("SELECT * FROM book_work_info WHERE bookId = :bookId")
    suspend fun getWorkInfo(bookId: String): BookWorkInfoEntity?
}
