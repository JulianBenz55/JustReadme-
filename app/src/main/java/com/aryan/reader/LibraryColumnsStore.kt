package com.aryan.reader

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.aryan.reader.shared.ui.LibraryColumnsDefault
import com.aryan.reader.shared.ui.LibraryColumnsMax
import com.aryan.reader.shared.ui.LibraryColumnsMin

/** Persists how many books the library shows per row (1-4) and exposes it as Compose state. */
object LibraryColumnsStore {
    private const val PREFS = "library_layout_prefs"
    private const val KEY_COLUMNS = "books_per_row"

    var columns by mutableIntStateOf(LibraryColumnsDefault)
        private set

    fun load(context: Context) {
        columns = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_COLUMNS, LibraryColumnsDefault)
            .coerceIn(LibraryColumnsMin, LibraryColumnsMax)
    }

    fun set(context: Context, value: Int) {
        columns = value.coerceIn(LibraryColumnsMin, LibraryColumnsMax)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_COLUMNS, columns).apply()
    }
}
