package com.aryan.reader

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Custom order for shelves that are built from files and folders. Those shelves have no database
 * rows of their own, so the order the user picked is kept here. Keys are `books:<shelfId>` and
 * `folders:<shelfId>`; unknown or new entries simply follow the saved ones.
 */
object ShelfOrderStore {
    private const val PREFS = "shelf_order_prefs"
    private const val KEY = "orders"

    var orders by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set

    fun booksKey(shelfId: String) = "books:$shelfId"
    fun foldersKey(shelfId: String) = "folders:$shelfId"

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        orders = if (raw == null) emptyMap() else try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { key ->
                val array = json.getJSONArray(key)
                (0 until array.length()).map { array.getString(it) }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun set(context: Context, key: String, ids: List<String>) {
        orders = orders + (key to ids)
        val json = JSONObject()
        orders.forEach { (k, v) -> json.put(k, JSONArray(v)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }
}

/** Puts entries with a saved position first, in saved order; everything else keeps its current order after them. */
internal fun <T> List<T>.applyStoredOrder(stored: List<String>?, idOf: (T) -> String): List<T> {
    if (stored.isNullOrEmpty()) return this
    val position = HashMap<String, Int>()
    stored.forEachIndexed { index, id -> position[id] = index }
    val (known, unknown) = partition { idOf(it) in position }
    return known.sortedBy { position.getValue(idOf(it)) } + unknown
}
