package com.aryan.reader.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

enum class StatsPeriod { WEEK, MONTH, YEAR }
enum class StatsMetric { TIME, PAGES }

/** One chart bar. [labelDate] is the first day it covers; Year bars cover a whole month. */
data class StatsBar(val start: LocalDate, val seconds: Long, val pages: Int, val isFuture: Boolean) {
    fun value(metric: StatsMetric): Double =
        if (metric == StatsMetric.TIME) seconds / 60.0 else pages.toDouble()
}

data class StatsWindow(val bars: List<StatsBar>, val first: LocalDate, val last: LocalDate, val elapsedDays: Int)

fun periodStart(period: StatsPeriod, offset: Int, today: LocalDate): LocalDate = when (period) {
    StatsPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.toLong())
    StatsPeriod.MONTH -> today.withDayOfMonth(1).plusMonths(offset.toLong())
    StatsPeriod.YEAR -> today.withDayOfYear(1).plusYears(offset.toLong())
}

fun buildWindow(snapshot: ReadingStatsSnapshot, period: StatsPeriod, offset: Int, today: LocalDate): StatsWindow {
    val start = periodStart(period, offset, today)
    val end = when (period) {
        StatsPeriod.WEEK -> start.plusDays(6)
        StatsPeriod.MONTH -> start.withDayOfMonth(YearMonth.from(start).lengthOfMonth())
        StatsPeriod.YEAR -> start.withDayOfYear(start.lengthOfYear())
    }
    val bars = if (period == StatsPeriod.YEAR) {
        (0 until 12).map { m ->
            val ms = start.plusMonths(m.toLong())
            val me = ms.withDayOfMonth(YearMonth.from(ms).lengthOfMonth())
            var s = 0L; var p = 0
            var d = ms
            while (!d.isAfter(me)) {
                snapshot.days[d]?.let { s += it.seconds; p += it.pages }
                d = d.plusDays(1)
            }
            StatsBar(ms, s, p, ms.isAfter(today))
        }
    } else {
        generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.map { d ->
            val v = snapshot.days[d]
            StatsBar(d, v?.seconds ?: 0L, v?.pages ?: 0, d.isAfter(today))
        }.toList()
    }
    val lastCounted = if (today.isBefore(end)) today else end
    val elapsed = if (lastCounted.isBefore(start)) 0 else (lastCounted.toEpochDay() - start.toEpochDay() + 1).toInt()
    return StatsWindow(bars, start, end, elapsed)
}

fun StatsWindow.total(metric: StatsMetric): Double =
    bars.filter { !it.isFuture }.sumOf { it.value(metric) }

/** Average per elapsed day (Monday..today for the running week), so early-week values aren't diluted. */
fun StatsWindow.averagePerDay(metric: StatsMetric): Double =
    if (elapsedDays <= 0) 0.0 else total(metric) / elapsedDays

data class StatsRecords(
    val currentStreak: Int,
    val longestStreak: Int,
    val bestDay: Pair<LocalDate, ReadingDayStat>?,
    val totalSeconds: Long,
    val totalPages: Int,
    val daysRead: Int,
    val goalDays: Int,
)

fun computeRecords(s: ReadingStatsSnapshot, today: LocalDate): StatsRecords {
    val read = s.days.filter { it.value.seconds >= 60 || it.value.pages > 0 }.keys.sorted()
    var longest = 0; var run = 0; var prev: LocalDate? = null
    for (d in read) {
        run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
        if (run > longest) longest = run
        prev = d
    }
    val set = read.toHashSet()
    var cursor = if (set.contains(today)) today else today.minusDays(1)
    var current = 0
    while (set.contains(cursor)) { current++; cursor = cursor.minusDays(1) }
    return StatsRecords(
        currentStreak = current,
        longestStreak = longest,
        bestDay = s.days.maxByOrNull { it.value.seconds }?.takeIf { it.value.seconds > 0 }?.toPair(),
        totalSeconds = s.days.values.sumOf { it.seconds },
        totalPages = s.days.values.sumOf { it.pages },
        daysRead = read.size,
        goalDays = s.goalResults.size,
    )
}

/** Historic pace over all days before [before]: how long pages took (and how many pages a time slot gave). */
data class HistoricPace(val avgSecondsPerPage: Double, val bestSecondsPerPage: Double)

fun historicPace(s: ReadingStatsSnapshot, before: LocalDate): HistoricPace? {
    val valid = s.days.filter { it.key.isBefore(before) && it.value.pages >= 5 && it.value.seconds >= 60 }
    if (valid.isEmpty()) return null
    val totalS = valid.values.sumOf { it.seconds }.toDouble()
    val totalP = valid.values.sumOf { it.pages }.toDouble()
    val best = valid.values.minOf { it.seconds.toDouble() / it.pages }
    return HistoricPace(totalS / totalP, best)
}

fun niceAxisMax(value: Double, metric: StatsMetric): Double {
    if (value <= 0.0) return if (metric == StatsMetric.TIME) 30.0 else 10.0
    if (metric == StatsMetric.TIME) {
        val steps = listOf(10, 20, 30, 45, 60, 90, 120, 180, 240, 360, 480, 720, 1440, 2880, 5760, 11520)
        return (steps.firstOrNull { it >= value } ?: (Math.ceil(value / 1440.0) * 1440.0)).toDouble()
    }
    val exp = Math.pow(10.0, Math.floor(Math.log10(value)))
    val f = value / exp
    val nice = when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 5 -> 5.0; else -> 10.0 }
    return nice * exp
}
