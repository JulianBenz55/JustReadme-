package com.aryan.reader.data

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "shelves")
data class ShelfEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isSmart: Boolean = false,
    val smartRulesJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false
)

@Entity(
    tableName = "book_shelf_cross_ref",
    primaryKeys =["bookId", "shelfId"],
    foreignKeys =[
        ForeignKey(
            entity = RecentFileEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ShelfEntity::class,
            parentColumns = ["id"],
            childColumns = ["shelfId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["shelfId"]), Index(value = ["bookId"])]
)
data class BookShelfCrossRef(
    val bookId: String,
    val shelfId: String,
    val addedAt: Long
)

@Entity(tableName = "tags")
@Immutable
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: Int? = null,
    val createdAt: Long,
    val description: String? = null,
    val icon: String? = null,
    @ColumnInfo(defaultValue = "'CUSTOM'") val type: String = TAG_TYPE_CUSTOM,
    @ColumnInfo(defaultValue = "0") val trackingEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "1") val visibleInNavigation: Boolean = true,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0
)

const val TAG_TYPE_SYSTEM = "SYSTEM"
const val TAG_TYPE_CUSTOM = "CUSTOM"

const val SYSTEM_TAG_FAVORITES = "default_favorites"
const val SYSTEM_TAG_FINISHED = "default_finished"
const val SYSTEM_TAG_READING = "default_reading"
const val SYSTEM_TAG_REFERENCE = "default_reference"
const val SYSTEM_TAG_TO_READ = "default_to_read"

@Entity(
    tableName = "book_tag_cross_ref",
    primaryKeys = ["bookId", "tagId"],
    foreignKeys =[
        ForeignKey(
            entity = RecentFileEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns =["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices =[Index(value = ["tagId"]), Index(value = ["bookId"])]
)
data class BookTagCrossRef(
    val bookId: String,
    val tagId: String
)

/**
 * One reading pass through a book. Earlier passes are never deleted: re-reading starts a new row.
 * Progress values are percentages (0..100) as stored in `recent_files.progressPercentage`.
 */
@Entity(
    tableName = "reading_sessions",
    foreignKeys = [
        ForeignKey(
            entity = RecentFileEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId"])]
)
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val startProgress: Float,
    val endProgress: Float,
    val maxProgress: Float,
    val lastUpdatedAt: Long,
    val readingAwardedAt: Long? = null,
    val endReachedAt: Long? = null
)

/** Audit log of every tag change made automatically by the reading tracker. */
@Entity(
    tableName = "tag_tracking_events",
    foreignKeys = [
        ForeignKey(
            entity = RecentFileEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId"]), Index(value = ["createdAt"])]
)
data class TagTrackingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: String,
    val tagId: String,
    val action: String,
    val reason: String,
    val progress: Float,
    val sessionId: Long? = null,
    val createdAt: Long
)

const val TRACKING_ACTION_ADDED = "ADDED"
const val TRACKING_ACTION_REMOVED = "REMOVED"
