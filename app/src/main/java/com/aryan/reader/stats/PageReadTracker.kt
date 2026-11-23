package com.aryan.reader.stats

import java.time.LocalDate

/**
 * Remembers which pages of a book have been read, so the same page is only ever counted once per
 * day. Implemented by [ReadingStatsStore] (persistent) and by a fake in the unit tests.
 */
interface PageReadLedger {
    /**
     * Marks pages [first]..[last] (0-based, inclusive) of [bookKey] as read on [date].
     * @return how many of them had not been read yet on that day.
     */
    fun markRead(bookKey: String, totalPages: Int, first: Int, last: Int, date: LocalDate): Int
}

/**
 * Counts pages the way page-visit trackers such as KOReader's statistics do: a page is *read*
 * when it was on screen for at least [minDwellMs]; flipping through, slider jumps and
 * table-of-contents jumps never rest on a page long enough to count. This replaces the earlier
 * "high-water mark" heuristic, which had to guess from sparse, debounced position events
 * whether a jump was reading or navigation and discarded real pages when it guessed wrong.
 *
 * The reader reports what is currently visible through [onViewport]. A viewport is a range of
 * pages: one page normally, two for a double-page spread. Pages are numbered 0..total-1; for
 * reflowable books callers use equally sized "virtual" pages of text, so the count does not
 * depend on font size or screen size.
 *
 * Not thread-safe; callers synchronise.
 */
class PageReadTracker(
    private val ledger: PageReadLedger,
    private val clock: () -> Long,
    private val today: () -> LocalDate,
    private val minDwellMs: Long = DEFAULT_MIN_DWELL_MS,
    private val onPagesRead: (date: LocalDate, pages: Int) -> Unit,
) {
    private data class Viewport(val key: String, val total: Int, val first: Int, val last: Int)

    private var viewport: Viewport? = null
    private var visibleSince = 0L
    private var counted = false
    private var running = false

    /** The reader shows pages [first]..[last] of [totalPages] now. Repeating the same viewport is free. */
    fun onViewport(bookKey: String, totalPages: Int, first: Int, last: Int) {
        if (totalPages <= 0) return
        val f = first.coerceIn(0, totalPages - 1)
        val l = last.coerceIn(f, totalPages - 1)
        val next = Viewport(bookKey, totalPages, f, l)
        if (next == viewport) return
        if (running) commit()
        viewport = next
        visibleSince = clock()
        counted = false
    }

    /** The reader is on screen again: the page that is showing starts a fresh dwell. */
    fun resume() {
        if (running) return
        running = true
        visibleSince = clock()
        // Same page, new stay: the ledger still keeps a page from counting twice on one day.
        counted = false
    }

    /** The reader left the screen: count the page that was showing, if it was there long enough. */
    fun pause() {
        if (!running) return
        commit()
        running = false
    }

    /** Counts the current page once it has been visible long enough, without waiting for a page turn. */
    fun checkpoint() {
        if (running) commit()
    }

    /** Forget the current page (the reader closed). */
    fun clear() {
        pause()
        viewport = null
    }

    private fun commit() {
        val vp = viewport ?: return
        if (counted) return
        val now = clock()
        if (now - visibleSince < minDwellMs) return
        counted = true
        val date = today()
        val fresh = ledger.markRead(vp.key, vp.total, vp.first, vp.last, date)
        if (fresh > 0) onPagesRead(date, fresh)
    }

    companion object {
        /** A page counts as read after this long on screen. Shorter is skimming or navigating. */
        const val DEFAULT_MIN_DWELL_MS = 3_000L

        /** Characters per virtual page of a reflowable book. */
        const val CHARS_PER_VIRTUAL_PAGE = 1_800.0

        fun virtualPageCount(totalChars: Int): Int =
            Math.round(totalChars / CHARS_PER_VIRTUAL_PAGE).toInt().coerceAtLeast(1)

        /**
         * Virtual pages covered by the text range [startFraction]..[endFraction] of a book
         * (fractions of the whole text, 0..1). Always covers at least one page.
         */
        fun virtualRange(startFraction: Double, endFraction: Double, totalPages: Int): IntRange {
            val s = startFraction.coerceIn(0.0, 1.0)
            val e = endFraction.coerceIn(s, 1.0)
            val first = Math.floor(s * totalPages).toInt().coerceIn(0, totalPages - 1)
            val last = (Math.ceil(e * totalPages - 1e-9).toInt() - 1).coerceIn(first, totalPages - 1)
            return first..last
        }
    }
}
