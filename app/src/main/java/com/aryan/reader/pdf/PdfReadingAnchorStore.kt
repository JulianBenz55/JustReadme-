package com.aryan.reader.pdf

import android.content.Context

/**
 * Merkt sich die genaue Leseposition im PDF (Seite + Anteil innerhalb der Seite), nicht nur die Seitennummer.
 * Dadurch öffnet das PDF wieder exakt dort, wo man aufgehört hat, statt am Anfang bzw. in der Mitte der Seite.
 */
internal object PdfReadingAnchorStore {
    private const val PREFS = "pdf_reading_anchor"

    data class Anchor(val page: Int, val fraction: Float)

    fun save(context: Context, key: String, page: Int, fraction: Float) {
        if (page < 0 || !fraction.isFinite()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, "$page|${fraction.coerceIn(0f, 1f)}").apply()
    }

    fun load(context: Context, key: String): Anchor? {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null) ?: return null
        val parts = raw.split("|")
        val page = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val fraction = parts.getOrNull(1)?.toFloatOrNull() ?: return null
        return Anchor(page, fraction.coerceIn(0f, 1f))
    }
}
