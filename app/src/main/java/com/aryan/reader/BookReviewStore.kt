package com.aryan.reader

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One personal review of a book: 1-5 stars and free text. Stored on the device only. */
class BookReview(
    val id: String,
    val rating: Int,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Local store for the user's own book reviews. It lives in a settings file, so it is part of
 * the full backup.
 */
object BookReviewStore {
    private const val PREFS = "book_reviews_prefs"
    private const val KEY = "reviews"

    var reviews by mutableStateOf<Map<String, List<BookReview>>>(emptyMap())
        private set

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        reviews = if (raw == null) emptyMap() else try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { bookId ->
                val array = json.getJSONArray(bookId)
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    BookReview(
                        id = item.getString("id"),
                        rating = item.optInt("rating", 0).coerceIn(0, 5),
                        text = item.optString("text"),
                        createdAt = item.optLong("createdAt"),
                        updatedAt = item.optLong("updatedAt"),
                    )
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Adds a review, or replaces the one with the same [reviewId]. */
    fun save(context: Context, bookId: String, reviewId: String?, rating: Int, text: String) {
        val now = System.currentTimeMillis()
        val existing = reviews[bookId].orEmpty()
        val previous = existing.firstOrNull { it.id == reviewId }
        val review = BookReview(
            id = previous?.id ?: UUID.randomUUID().toString(),
            rating = rating.coerceIn(0, 5),
            text = text.trim(),
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
        )
        val updated = if (previous != null) existing.map { if (it.id == previous.id) review else it } else listOf(review) + existing
        commit(context, reviews + (bookId to updated))
    }

    fun delete(context: Context, bookId: String, reviewId: String) {
        val remaining = reviews[bookId].orEmpty().filterNot { it.id == reviewId }
        commit(context, if (remaining.isEmpty()) reviews - bookId else reviews + (bookId to remaining))
    }

    private fun commit(context: Context, updated: Map<String, List<BookReview>>) {
        reviews = updated
        val json = JSONObject()
        updated.forEach { (bookId, list) ->
            val array = JSONArray()
            list.forEach { review ->
                array.put(
                    JSONObject()
                        .put("id", review.id)
                        .put("rating", review.rating)
                        .put("text", review.text)
                        .put("createdAt", review.createdAt)
                        .put("updatedAt", review.updatedAt),
                )
            }
            json.put(bookId, array)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }
}
