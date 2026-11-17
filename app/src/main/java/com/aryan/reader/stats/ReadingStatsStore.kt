package com.aryan.reader.stats

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.LocalDate

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
            _state.value = load(p)
        }
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
