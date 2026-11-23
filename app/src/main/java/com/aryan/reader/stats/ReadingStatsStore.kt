package com.aryan.reader.stats

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.LocalDate
import java.util.BitSet

enum class ReadingGoalType { NONE, TIME, PAGES }

/** [target] is minutes for TIME and pages for PAGES. */
data class ReadingGoal(val type: ReadingGoalType = ReadingGoalType.NONE, val target: Int = 0) {
    val isActive: Boolean get() = type != ReadingGoalType.NONE && target > 0
}

data class ReadingDayStat(val seconds: Long = 0L, val pages: Int = 0)

/** Frozen result of a reached daily goal: the tracker stops once this exists for a date. */
data class ReadingGoalResult(
    val date: LocalDate,
    val goal: ReadingGoal,
    val secondsAtReach: Long,
    val pagesAtReach: Int,
)

data class ReadingStatsSnapshot(
    val days: Map<LocalDate, ReadingDayStat> = emptyMap(),
    val goal: ReadingGoal = ReadingGoal(),
    val goalResults: Map<LocalDate, ReadingGoalResult> = emptyMap(),
    val goalNotificationsEnabled: Boolean = true,
)

/** Local, dependency-free persistence (SharedPreferences + JSON). Reading stats never leave the device. */
object ReadingStatsStore {
    private const val PREFS = "reading_stats_v1"
    private const val KEY_DAYS = "days"
    private const val KEY_GOAL = "goal"
    private const val KEY_RESULTS = "goal_results"
    private const val KEY_NOTIFY = "notify"

    private val lock = Any()
    private var prefs: android.content.SharedPreferences? = null
    private val _state = MutableStateFlow(ReadingStatsSnapshot())
    val state: StateFlow<ReadingStatsSnapshot> = _state.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            pagesPrefs = context.applicationContext.getSharedPreferences(PAGES_PREFS, Context.MODE_PRIVATE)
            _state.value = load(p)
        }
    }

    // ---- Pages read per book -------------------------------------------------------------
    //
    // One bit per page for "ever read" and one for "read today", persisted per book. The daily
    // count only grows for pages that are new that day, so turning back and forth over the same
    // page, or re-reading a chapter after dinner, does not inflate the day.

    private class BookPages(var total: Int, var ever: BitSet, var day: LocalDate?, var today: BitSet)

    private const val PAGES_PREFS = "reading_pages_v1"
    private val bookPages = HashMap<String, BookPages>()
    private var pagesPrefs: android.content.SharedPreferences? = null

    val pageLedger: PageReadLedger = object : PageReadLedger {
        override fun markRead(bookKey: String, totalPages: Int, first: Int, last: Int, date: LocalDate): Int =
            markPagesRead(bookKey, totalPages, first, last, date)
    }

    fun initPages(context: Context) {
        synchronized(lock) {
            if (pagesPrefs == null) {
                pagesPrefs = context.applicationContext.getSharedPreferences(PAGES_PREFS, Context.MODE_PRIVATE)
            }
        }
    }

    /** Distinct pages of [bookKey] read on any day (0 for unknown books). */
    fun pagesEverRead(bookKey: String): Int = synchronized(lock) { loadBookPages(bookKey)?.ever?.cardinality() ?: 0 }

    internal fun markPagesRead(bookKey: String, totalPages: Int, first: Int, last: Int, date: LocalDate): Int {
        if (totalPages <= 0 || last < first) return 0
        synchronized(lock) {
            var book = loadBookPages(bookKey)
            // A different page count means a different pagination (another file or edition): the
            // old bits no longer describe the same pages, so start over rather than mix them.
            if (book == null || book.total != totalPages) {
                book = BookPages(totalPages, BitSet(totalPages), null, BitSet(totalPages))
                bookPages[bookKey] = book
            }
            if (book.day != date) {
                book.day = date
                book.today = BitSet(totalPages)
            }
            var fresh = 0
            for (page in first.coerceAtLeast(0)..last.coerceAtMost(totalPages - 1)) {
                book.ever.set(page)
                if (!book.today.get(page)) {
                    book.today.set(page)
                    fresh++
                }
            }
            persistBookPages(bookKey, book)
            return fresh
        }
    }

    private fun loadBookPages(bookKey: String): BookPages? {
        bookPages[bookKey]?.let { return it }
        val raw = pagesPrefs?.getString(bookKey, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 4) return null
        val total = parts[0].toIntOrNull() ?: return null
        val loaded = BookPages(
            total = total,
            ever = hexToBits(parts[2]),
            day = runCatching { LocalDate.parse(parts[1]) }.getOrNull(),
            today = hexToBits(parts[3]),
        )
        bookPages[bookKey] = loaded
        return loaded
    }

    private fun persistBookPages(bookKey: String, book: BookPages) {
        pagesPrefs?.edit()?.putString(
            bookKey, "${book.total}|${book.day ?: ""}|${bitsToHex(book.ever)}|${bitsToHex(book.today)}",
        )?.apply()
    }

    private fun bitsToHex(bits: BitSet): String =
        bits.toByteArray().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hexToBits(hex: String): BitSet {
        if (hex.length % 2 != 0) return BitSet()
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return BitSet.valueOf(bytes)
    }

    fun addSeconds(date: LocalDate, seconds: Long) {
        if (seconds <= 0) return
        update(persistDays = true) { s ->
            val cur = s.days[date] ?: ReadingDayStat()
            s.copy(days = s.days + (date to cur.copy(seconds = cur.seconds + seconds)))
        }
    }

    fun addPages(date: LocalDate, pages: Int) {
        if (pages <= 0) return
        update(persistDays = true) { s ->
            val cur = s.days[date] ?: ReadingDayStat()
            s.copy(days = s.days + (date to cur.copy(pages = cur.pages + pages)))
        }
    }

    /** Changing the goal re-opens today's tracker (the old result no longer matches the goal). */
    fun setGoal(goal: ReadingGoal, today: LocalDate = LocalDate.now()) {
        update(persistGoal = true, persistResults = true) { s ->
            s.copy(goal = goal, goalResults = s.goalResults - today)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        update(persistNotify = true) { it.copy(goalNotificationsEnabled = enabled) }
    }

    /** @return true only if this call created the result (so the caller notifies exactly once). */
    fun markGoalReached(result: ReadingGoalResult): Boolean {
        var created = false
        update(persistResults = true) { s ->
            if (s.goalResults.containsKey(result.date)) s else {
                created = true
                s.copy(goalResults = s.goalResults + (result.date to result))
            }
        }
        return created
    }

    private fun update(
        persistDays: Boolean = false,
        persistGoal: Boolean = false,
        persistResults: Boolean = false,
        persistNotify: Boolean = false,
        transform: (ReadingStatsSnapshot) -> ReadingStatsSnapshot,
    ) {
        synchronized(lock) {
            val next = transform(_state.value)
            _state.value = next
            val e = prefs?.edit() ?: return
            if (persistDays) e.putString(KEY_DAYS, daysToJson(next.days))
            if (persistGoal) e.putString(KEY_GOAL, goalToJson(next.goal))
            if (persistResults) e.putString(KEY_RESULTS, resultsToJson(next.goalResults))
            if (persistNotify) e.putBoolean(KEY_NOTIFY, next.goalNotificationsEnabled)
            e.apply()
        }
    }

    private fun load(p: android.content.SharedPreferences): ReadingStatsSnapshot = runCatching {
        val days = HashMap<LocalDate, ReadingDayStat>()
        p.getString(KEY_DAYS, null)?.let { raw ->
            val o = JSONObject(raw)
            o.keys().forEach { k ->
                val d = o.getJSONObject(k)
                days[LocalDate.parse(k)] = ReadingDayStat(d.optLong("s"), d.optInt("p"))
            }
        }
        val goal = p.getString(KEY_GOAL, null)?.let { goalFromJson(JSONObject(it)) } ?: ReadingGoal()
        val results = HashMap<LocalDate, ReadingGoalResult>()
        p.getString(KEY_RESULTS, null)?.let { raw ->
            val o = JSONObject(raw)
            o.keys().forEach { k ->
                val r = o.getJSONObject(k)
                val date = LocalDate.parse(k)
                results[date] = ReadingGoalResult(
                    date, goalFromJson(r.getJSONObject("g")), r.optLong("s"), r.optInt("p"),
                )
            }
        }
        ReadingStatsSnapshot(days, goal, results, p.getBoolean(KEY_NOTIFY, true))
    }.getOrDefault(ReadingStatsSnapshot())

    private fun daysToJson(days: Map<LocalDate, ReadingDayStat>) = JSONObject().also { o ->
        days.forEach { (d, v) -> o.put(d.toString(), JSONObject().put("s", v.seconds).put("p", v.pages)) }
    }.toString()

    private fun goalToJson(g: ReadingGoal) = JSONObject().put("t", g.type.name).put("n", g.target).toString()

    private fun goalFromJson(o: JSONObject) = ReadingGoal(
        runCatching { ReadingGoalType.valueOf(o.optString("t")) }.getOrDefault(ReadingGoalType.NONE),
        o.optInt("n"),
    )

    private fun resultsToJson(r: Map<LocalDate, ReadingGoalResult>) = JSONObject().also { o ->
        r.forEach { (d, v) ->
            o.put(
                d.toString(),
                JSONObject()
                    .put("g", JSONObject().put("t", v.goal.type.name).put("n", v.goal.target))
                    .put("s", v.secondsAtReach).put("p", v.pagesAtReach),
            )
        }
    }.toString()
}
