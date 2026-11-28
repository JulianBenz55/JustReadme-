package com.aryan.reader.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

/** How a tag assigned to a series is passed on to its volumes. */
const val INHERIT_SERIES_ONLY = "SERIES_ONLY"
const val INHERIT_EXISTING = "EXISTING"
const val INHERIT_EXISTING_AND_FUTURE = "EXISTING_AND_FUTURE"

/** Series are identified by their normalised name (the only series information books carry). */
fun seriesKeyOf(name: String): String = name.trim().lowercase(Locale.ROOT)

/**
 * Per-series metadata. Membership itself stays in `recent_files.seriesName`; this row only holds what
 * a book cannot: the known number of volumes, whether the story is finished, and the last status the
 * automatic tracker has applied (so a tag the user removed by hand is not put back).
 */
@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val seriesKey: String,
    val name: String,
    val knownVolumeCount: Int? = null,
    @ColumnInfo(defaultValue = "0") val isStoryComplete: Boolean = false,
    @ColumnInfo(defaultValue = "'NOT_STARTED'") val autoStatus: String = "NOT_STARTED",
    val createdAt: Long
)

/** A tag assigned to a whole series. `isAutomatic` marks rows created by the status tracker. */
@Entity(
    tableName = "series_tag_assignments",
    primaryKeys = ["seriesKey", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["seriesKey"],
            childColumns = ["seriesKey"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tagId"])]
)
data class SeriesTagAssignmentEntity(
    val seriesKey: String,
    val tagId: String,
    @ColumnInfo(defaultValue = "'SERIES_ONLY'") val inheritMode: String = INHERIT_SERIES_ONLY,
    @ColumnInfo(defaultValue = "0") val isAutomatic: Boolean = false,
    val createdAt: Long
)

/**
 * A tag a volume holds only because its series has it. Kept apart from `book_tag_cross_ref`, so
 * removing the series assignment can never touch a tag the user set on the volume itself.
 */
@Entity(
    tableName = "book_tag_inherited",
    primaryKeys = ["bookId", "tagId", "seriesKey"],
    foreignKeys = [
        ForeignKey(
            entity = RecentFileEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tagId"]), Index(value = ["seriesKey", "tagId"])]
)
data class BookTagInheritedEntity(
    val bookId: String,
    val tagId: String,
    val seriesKey: String
)

/**
 * One confirmed read position. The primary key (bookId, positionKey) is what rules out double
 * counting: re-reading a position is an ignored insert. Keys are `p:<page>` for PDF and
 * `c<chapter>:b<block>` for EPUB (independent of font and screen size).
 */
@Entity(
    tableName = "read_positions",
    primaryKeys = ["bookId", "positionKey"],
    foreignKeys = [
        ForeignKey(
            entity = RecentFileEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ReadPositionEntity(
    val bookId: String,
    val positionKey: String,
    val readAt: Long,
    val sessionId: Long? = null
)

/** Denormalised counters for a file; always derived from `read_positions` inside one transaction. */
@Entity(
    tableName = "book_read_stats",
    foreignKeys = [
        ForeignKey(
            entity = RecentFileEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BookReadStatsEntity(
    @PrimaryKey val bookId: String,
    val totalUnits: Int? = null,
    @ColumnInfo(defaultValue = "0") val readUnits: Int = 0,
    val lastPositionKey: String? = null,
    val lastUpdatedAt: Long
)

/**
 * Whether this file is the whole work. A partial EPUB of a longer story sets it to false; 100 % of
 * such a file never assigns "Finished" on its own.
 */
@Entity(
    tableName = "book_work_info",
    foreignKeys = [
        ForeignKey(
            entity = RecentFileEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BookWorkInfoEntity(
    @PrimaryKey val bookId: String,
    @ColumnInfo(defaultValue = "1") val coversWholeWork: Boolean = true
)
