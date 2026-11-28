package com.aryan.reader.data

import androidx.room.Room
import com.aryan.reader.FileType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VellumRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var tags: TagRepository
    private lateinit var series: SeriesRepository

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        tags = TagRepository(db)
        series = SeriesRepository(db)
        db.tagDao().insertTag(TagEntity(id = "fantasy", name = "Fantasy", createdAt = 0L))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun book(id: String, seriesName: String?) {
        db.recentFileDao().insertOrUpdateFile(
            RecentFileEntity(
                bookId = id, uriString = null, type = FileType.PDF, displayName = id, timestamp = 0L,
                coverImagePath = null, title = id, author = null, lastChapterIndex = null, lastPage = null,
                lastPositionCfi = null, progressPercentage = null, isRecent = true, isAvailable = true,
                lastModifiedTimestamp = 0L, isDeleted = false, locatorBlockIndex = null, locatorCharOffset = null,
                bookmarks = null, sourceFolderUri = null, isReflowPreferred = false, customName = null,
                highlights = null, fileSize = 0L, seriesName = seriesName, seriesIndex = null,
                description = null, folderTextMetadataParsed = false
            )
        )
    }

    private suspend fun inheritedBooks() =
        db.seriesDao().getAllInherited().filter { it.tagId == "fantasy" }.map { it.bookId }.toSet()

    @Test
    fun existingAndFutureCoversNewVolumeAfterReconcile() = runTest {
        book("b1", "Overgeared"); book("b2", "Overgeared")
        tags.assignToSeries("Overgeared", "fantasy", INHERIT_EXISTING_AND_FUTURE)
        assertEquals(setOf("b1", "b2"), inheritedBooks())
        book("b3", "overgeared ")
        series.reconcile()
        assertEquals(setOf("b1", "b2", "b3"), inheritedBooks())
    }

    @Test
    fun existingModeIgnoresNewVolume() = runTest {
        book("b1", "S")
        tags.assignToSeries("S", "fantasy", INHERIT_EXISTING)
        book("b2", "S")
        series.reconcile()
        assertEquals(setOf("b1"), inheritedBooks())
    }

    @Test
    fun removingSeriesAssignmentKeepsDirectTag() = runTest {
        book("b1", "S")
        tags.assignToBooks("fantasy", listOf("b1"))
        tags.assignToSeries("S", "fantasy", INHERIT_EXISTING_AND_FUTURE)
        tags.removeFromSeries("S", "fantasy")
        assertTrue(inheritedBooks().isEmpty())
        assertEquals(1, db.tagDao().countBookTag("b1", "fantasy"))
    }

    @Test
    fun hidingTagKeepsAssignments() = runTest {
        book("b1", null)
        tags.assignToBooks("fantasy", listOf("b1"))
        tags.setVisibleInNavigation("fantasy", false)
        tags.setEnabled("fantasy", false)
        assertEquals(1, db.tagDao().countBookTag("b1", "fantasy"))
        assertNotNull(db.tagDao().getTagById("fantasy"))
    }

    @Test
    fun insertTagNeverResetsExistingTag() = runTest {
        book("b1", null)
        tags.assignToBooks("fantasy", listOf("b1"))
        tags.setVisibleInNavigation("fantasy", true)
        db.tagDao().insertTag(TagEntity(id = "fantasy", name = "Other", createdAt = 1L))
        assertEquals(true, db.tagDao().getTagById("fantasy")!!.visibleInNavigation)
        assertEquals(1, db.tagDao().countBookTag("b1", "fantasy"))
    }

    @Test
    fun partialEpubIsNotFinishedBySingleFile() = runTest {
        val store = TagTrackingSettingsStore(RuntimeEnvironment.getApplication())
        db.tagDao().insertTag(TagEntity(id = SYSTEM_TAG_FINISHED, name = "Finished", createdAt = 0L, type = TAG_TYPE_SYSTEM, trackingEnabled = true))
        db.tagDao().insertTag(TagEntity(id = SYSTEM_TAG_READING, name = "Reading", createdAt = 0L, type = TAG_TYPE_SYSTEM, trackingEnabled = true))
        book("b1", null)
        db.readPositionDao().upsertWorkInfo(BookWorkInfoEntity("b1", coversWholeWork = false))
        db.readPositionDao().insertPosition(ReadPositionEntity("b1", "c0:b1", 1L))
        val tracker = ReadingTracker(db, store)
        tracker.onProgress("b1", 50f, now = 1L)
        tracker.onProgress("b1", 95f, now = 1L)
        tracker.onProgress("b1", 100f, now = 2L)
        assertEquals(1, db.tagDao().countBookTag("b1", SYSTEM_TAG_READING))
        assertEquals(0, db.tagDao().countBookTag("b1", SYSTEM_TAG_FINISHED))
    }

    @Test
    fun seriesFinishedOnlyWhenStoryComplete() = runTest {
        val store = TagTrackingSettingsStore(RuntimeEnvironment.getApplication())
        db.tagDao().insertTag(TagEntity(id = SYSTEM_TAG_FINISHED, name = "Finished", createdAt = 0L, type = TAG_TYPE_SYSTEM, trackingEnabled = true))
        db.tagDao().insertTag(TagEntity(id = SYSTEM_TAG_READING, name = "Reading", createdAt = 0L, type = TAG_TYPE_SYSTEM, trackingEnabled = true))
        book("b1", "S"); book("b2", "S")
        db.tagDao().insertBookTagCrossRef(BookTagCrossRef("b1", SYSTEM_TAG_FINISHED))
        db.tagDao().insertBookTagCrossRef(BookTagCrossRef("b2", SYSTEM_TAG_FINISHED))
        val tracker = ReadingStatusTracker(db, store)
        tracker.onBookChanged("b1")
        val key = seriesKeyOf("S")
        assertNotNull(db.seriesDao().getAssignment(key, SYSTEM_TAG_READING))
        assertEquals(null, db.seriesDao().getAssignment(key, SYSTEM_TAG_FINISHED))
        series.setSeriesInfo("S", knownVolumeCount = null, isStoryComplete = true)
        tracker.onBookChanged("b1")
        assertNotNull(db.seriesDao().getAssignment(key, SYSTEM_TAG_FINISHED))
        assertEquals(null, db.seriesDao().getAssignment(key, SYSTEM_TAG_READING))
    }

    @Test
    fun effectiveTagsCombineDirectAndInheritedWithoutDuplicates() = runTest {
        book("b1", "S"); book("b2", "S")
        tags.assignToBooks("fantasy", listOf("b1"))
        tags.assignToSeries("S", "fantasy", INHERIT_EXISTING_AND_FUTURE)
        val effective = db.seriesDao().observeEffectiveBookTags().first()
        assertEquals(setOf("b1", "b2"), effective.filter { it.tagId == "fantasy" }.map { it.bookId }.toSet())
        assertEquals(2, effective.size)
    }
}
