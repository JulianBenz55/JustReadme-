package com.aryan.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ShelfDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShelf(shelf: ShelfEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookShelfCrossRefs(crossRefs: List<BookShelfCrossRef>)

    @Query("SELECT * FROM shelves WHERE isDeleted = 0 ORDER BY name ASC")
    fun getAllActiveShelves(): Flow<List<ShelfEntity>>

    @Query("SELECT * FROM shelves WHERE isSmart = 0")
    suspend fun getAllUserShelvesForSync(): List<ShelfEntity>

    @Query("SELECT * FROM book_shelf_cross_ref")
    fun getAllBookShelfCrossRefs(): Flow<List<BookShelfCrossRef>>

    @Query("DELETE FROM book_shelf_cross_ref WHERE shelfId = :shelfId AND bookId IN (:bookIds)")
    suspend fun removeBooksFromShelf(shelfId: String, bookIds: List<String>)

    @Query("UPDATE shelves SET updatedAt = :timestamp WHERE id = :shelfId")
    suspend fun touchShelf(shelfId: String, timestamp: Long)

    @Query("UPDATE shelves SET isDeleted = 1, updatedAt = :timestamp WHERE id = :shelfId")
    suspend fun markShelfAsDeleted(shelfId: String, timestamp: Long)

    @Query("UPDATE shelves SET name = :newName, updatedAt = :timestamp WHERE id = :shelfId")
    suspend fun updateShelfName(shelfId: String, newName: String, timestamp: Long)

    @Query("SELECT * FROM shelves WHERE id = :shelfId")
    suspend fun getShelfById(shelfId: String): ShelfEntity?

    @Query("DELETE FROM book_shelf_cross_ref WHERE shelfId = :shelfId")
    suspend fun clearBooksFromShelf(shelfId: String)

    @Query("SELECT * FROM book_shelf_cross_ref WHERE shelfId = :shelfId")
    suspend fun getCrossRefsForShelf(shelfId: String): List<BookShelfCrossRef>

    @Query("DELETE FROM book_shelf_cross_ref")
    suspend fun clearAllBookShelfCrossRefs()

    @Query("DELETE FROM shelves")
    suspend fun clearAll()
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookTagCrossRef(crossRef: BookTagCrossRef)

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAllTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM book_tag_cross_ref")
    fun getAllBookTagCrossRefs(): Flow<List<BookTagCrossRef>>

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun getTagCount(): Int

    @Query("DELETE FROM book_tag_cross_ref WHERE tagId = :tagId AND bookId = :bookId")
    suspend fun removeTagFromBook(tagId: String, bookId: String)

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteTag(tagId: String)

    @Query("SELECT * FROM tags WHERE id = :tagId")
    suspend fun getTagById(tagId: String): TagEntity?

    @Query("SELECT COUNT(*) FROM book_tag_cross_ref WHERE bookId = :bookId AND tagId = :tagId")
    suspend fun countBookTag(bookId: String, tagId: String): Int

    @Query("UPDATE tags SET trackingEnabled = :enabled WHERE id = :tagId")
    suspend fun setTrackingEnabled(tagId: String, enabled: Boolean)

    @Query("UPDATE tags SET visibleInNavigation = :visible WHERE id = :tagId")
    suspend fun setVisibleInNavigation(tagId: String, visible: Boolean)

    @Query("DELETE FROM book_tag_cross_ref")
    suspend fun clearAllBookTagCrossRefs()

    @Query("DELETE FROM tags")
    suspend fun clearAll()
}

@Dao
interface ReadingTrackingDao {
    @Insert
    suspend fun insertSession(session: ReadingSessionEntity): Long

    @Update
    suspend fun updateSession(session: ReadingSessionEntity)

    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId ORDER BY startedAt DESC, id DESC LIMIT 1")
    suspend fun getLatestSession(bookId: String): ReadingSessionEntity?

    @Query("SELECT COUNT(*) FROM reading_sessions WHERE bookId = :bookId AND finishedAt IS NOT NULL")
    suspend fun countFinishedSessions(bookId: String): Int

    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId ORDER BY startedAt DESC, id DESC")
    fun observeSessions(bookId: String): Flow<List<ReadingSessionEntity>>

    @Insert
    suspend fun insertEvent(event: TagTrackingEventEntity)

    @Query("SELECT * FROM tag_tracking_events ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecentEvents(limit: Int): Flow<List<TagTrackingEventEntity>>
}
