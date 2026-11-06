package com.aryan.reader.epubreader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aryan.reader.epub.EpubChapter
import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpubReaderBookmarkTest {

    private lateinit var context: Context
    private val testBookTitle = "My Test Book"
    private val chapters = listOf(
        EpubChapter(chapterId = "ch1", title = "Chapter 1", htmlFilePath = "", absPath = "", htmlContent = "", plainTextContent = ""),
        EpubChapter(chapterId = "ch2", title = "Chapter 2", htmlFilePath = "", absPath = "", htmlContent = "", plainTextContent = "")
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear any old prefs to ensure a clean slate for each test
        val prefs = context.getSharedPreferences("epub_reader_bookmarks", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun loadBookmarks_withValidJson_parsesCorrectly() {
        val bookmark1 = JSONObject().apply {
            put("cfi", "/4/2:10")
            put("chapterTitle", "Chapter 1")
            put("snippet", "A snippet of text")
            put("chapterIndex", 0)
        }
        val bookmark2 = JSONObject().apply {
            put("cfi", "/6/4:22")
            put("chapterTitle", "Chapter 2")
            put("snippet", "Another snippet")
            put("chapterIndex", 1)
        }
        val bookmarksJson = JSONArray(listOf(bookmark1.toString(), bookmark2.toString())).toString()

        val bookmarks = loadBookmarks(context, testBookTitle, chapters, bookmarksJson)

        assertThat(bookmarks).hasSize(2)
        assertThat(bookmarks).contains(
            Bookmark(
                cfi = "/4/2:10",
                chapterTitle = "Chapter 1",
                snippet = "A snippet of text",
                pageInChapter = null,
                totalPagesInChapter = null,
                chapterIndex = 0
            )
        )
    }

    @Test
    fun loadBookmarks_withInvalidJson_returnsEmptySet() {
        val invalidJson = "[{\"cfi\": \"/4/2:10\", snippet: \"invalid json\"}]" // snippet value not in quotes
        val bookmarks = loadBookmarks(context, testBookTitle, chapters, invalidJson)
        assertThat(bookmarks).isEmpty()
    }

    @Test
    fun loadBookmarks_withMissingChapterIndex_calculatesItFromTitle() {
        val bookmark1 = JSONObject().apply {
            put("cfi", "/6/4:22")
            put("chapterTitle", "Chapter 2") // This should map to index 1
            put("snippet", "Another snippet")
        }
        val bookmarksJson = JSONArray(listOf(bookmark1.toString())).toString()

        val bookmarks = loadBookmarks(context, testBookTitle, chapters, bookmarksJson)

        assertThat(bookmarks).hasSize(1)
        val loadedBookmark = bookmarks.first()
        assertThat(loadedBookmark.chapterIndex).isEqualTo(1)
        assertThat(loadedBookmark.chapterTitle).isEqualTo("Chapter 2")
    }

    @Test
    fun loadBookmarks_withNullJson_fallsBackToSharedPreferences() {
        // This test doesn't write to shared prefs, so it should return an empty set.
        val bookmarks = loadBookmarks(context, testBookTitle, chapters, null)
        assertThat(bookmarks).isEmpty()
    }
}