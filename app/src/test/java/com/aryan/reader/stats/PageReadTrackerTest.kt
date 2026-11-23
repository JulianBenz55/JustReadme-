package com.aryan.reader.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class PageReadTrackerTest {
    private class FakeLedger : PageReadLedger {
        private val seen = HashMap<String, MutableSet<Int>>()
        override fun markRead(bookKey: String, totalPages: Int, first: Int, last: Int, date: LocalDate): Int {
            val set = seen.getOrPut("$bookKey|$date") { HashSet() }
            return (first..last).count { set.add(it) }
        }
    }

    private class Harness {
        var now = 1_000_000L
        var date: LocalDate = LocalDate.of(2026, 11, 23)
        var pages = 0
        val tracker = PageReadTracker(
            ledger = FakeLedger(),
            clock = { now },
            today = { date },
            minDwellMs = 3_000L,
        ) { _, n -> pages += n }

        init { tracker.resume() }
        fun view(page: Int, last: Int = page, total: Int = 100, key: String = "book") =
            tracker.onViewport(key, total, page, last)
        fun wait(ms: Long) { now += ms }
    }

    @Test
    fun `a page that stays long enough counts once it is left`() {
        val h = Harness()
        h.view(0); h.wait(5_000)
        h.view(1)
        assertEquals(1, h.pages)
    }

    @Test
    fun `flipping through pages quickly counts nothing`() {
        val h = Harness()
        for (p in 0..20) { h.view(p); h.wait(600) }
        h.tracker.pause()
        assertEquals(0, h.pages)
    }

    @Test
    fun `a jump far ahead is not counted as pages in between`() {
        val h = Harness()
        h.view(0); h.wait(5_000)
        h.view(80); h.wait(5_000)
        h.view(81)
        // page 0 and page 80 were actually looked at; 1..79 were jumped over.
        assertEquals(2, h.pages)
    }

    @Test
    fun `turning back to a page the same day does not count it twice`() {
        val h = Harness()
        h.view(3); h.wait(5_000)
        h.view(4); h.wait(5_000)
        h.view(3); h.wait(5_000)
        h.view(4); h.wait(5_000)
        h.tracker.pause()
        assertEquals(2, h.pages)
    }

    @Test
    fun `the same page counts again on the next day`() {
        val h = Harness()
        h.view(3); h.wait(5_000); h.tracker.pause()
        h.date = h.date.plusDays(1)
        h.tracker.resume(); h.view(3); h.wait(5_000); h.tracker.pause()
        assertEquals(2, h.pages)
    }

    @Test
    fun `a spread counts both of its pages`() {
        val h = Harness()
        h.view(10, last = 11); h.wait(5_000)
        h.view(12, last = 13)
        assertEquals(2, h.pages)
    }

    @Test
    fun `checkpoint counts a page without waiting for a page turn`() {
        val h = Harness()
        h.view(7); h.wait(4_000)
        h.tracker.checkpoint()
        assertEquals(1, h.pages)
        h.wait(60_000); h.tracker.checkpoint(); h.view(8)
        assertEquals(1, h.pages)
    }

    @Test
    fun `time with the reader in the background does not count towards the page`() {
        val h = Harness()
        h.view(5); h.wait(1_000)
        h.tracker.pause()
        h.wait(60_000)
        h.tracker.resume(); h.wait(1_000)
        h.tracker.pause()
        assertEquals(0, h.pages)
    }

    @Test
    fun `virtual page range covers the text on screen`() {
        // 100 virtual pages; a screen showing 12.3% .. 13.4% of the book touches pages 12 and 13.
        assertEquals(12..13, PageReadTracker.virtualRange(0.123, 0.134, 100))
        // A screen that ends exactly on a page boundary does not spill into the next page.
        assertEquals(12..12, PageReadTracker.virtualRange(0.1205, 0.1295, 100))
        // A single position still covers one page.
        assertEquals(50..50, PageReadTracker.virtualRange(0.505, 0.505, 100))
        // The very end of the book stays inside the book.
        assertEquals(99..99, PageReadTracker.virtualRange(1.0, 1.0, 100))
    }
}
