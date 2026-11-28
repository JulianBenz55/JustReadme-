package com.aryan.reader.data

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VellumDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun samePositionIsNeverCountedTwice() = runTest {
        val dao = db.readPositionDao()
        // Foreign keys need the book; use the DAO-level insert result as the dedupe contract.
        insertBook("b1")
        assertTrue(dao.insertPosition(ReadPositionEntity("b1", "p:1", 1L)) != -1L)
        assertEquals(-1L, dao.insertPosition(ReadPositionEntity("b1", "p:1", 2L)))
        assertEquals(1, dao.countPositions("b1"))
    }

    @Test
    fun newCustomTagIsHiddenByDefault() {
        assertEquals(false, TagEntity(id = "t", name = "x", createdAt = 0L).visibleInNavigation)
    }

    private suspend fun insertBook(id: String) {
        db.recentFileDao().insertOrUpdateFile(
            RecentFileEntity(
                bookId = id, uriString = null, type = com.aryan.reader.FileType.PDF, displayName = id,
                timestamp = 0L, coverImagePath = null, title = id, author = null, lastChapterIndex = null,
                lastPage = null, lastPositionCfi = null, progressPercentage = null, isRecent = true,
                isAvailable = true, lastModifiedTimestamp = 0L, isDeleted = false, locatorBlockIndex = null,
                locatorCharOffset = null, bookmarks = null, sourceFolderUri = null, isReflowPreferred = false,
                customName = null, highlights = null, fileSize = 0L, seriesName = null, seriesIndex = null,
                description = null, folderTextMetadataParsed = false
            )
        )
    }
}
