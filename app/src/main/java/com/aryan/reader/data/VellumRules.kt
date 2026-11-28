package com.aryan.reader.data

/** Pure rules (no database, no Android) so they can be unit tested on the JVM. */

// ---------------------------------------------------------------- series progress

data class VolumeState(val finished: Boolean, val started: Boolean = finished)

enum class SeriesStatus { NOT_STARTED, PARTIALLY_READ, ALL_PUBLISHED_READ, COMPLETED }

data class SeriesProgress(
    val percent: Float,
    val finishedVolumes: Int,
    val totalVolumes: Int,
    val status: SeriesStatus
)

object SeriesProgressCalculator {
    /**
     * Progress is finished volumes / known volumes. The known count is the larger of the volumes in
     * the library and [knownVolumeCount] (published but not owned). Unpublished volumes never count.
     * "Completed" needs [storyComplete]; reading every published volume is only ALL_PUBLISHED_READ.
     */
    fun calculate(volumes: List<VolumeState>, knownVolumeCount: Int?, storyComplete: Boolean): SeriesProgress {
        val total = maxOf(volumes.size, knownVolumeCount ?: 0)
        val finished = volumes.count { it.finished }
        val percent = if (total == 0) 0f else finished * 100f / total
        val status = when {
            total == 0 -> SeriesStatus.NOT_STARTED
            finished >= total ->
                if (storyComplete) SeriesStatus.COMPLETED else SeriesStatus.ALL_PUBLISHED_READ
            finished > 0 || volumes.any { it.started } -> SeriesStatus.PARTIALLY_READ
            else -> SeriesStatus.NOT_STARTED
        }
        return SeriesProgress(percent, finished, total, status)
    }
}

// ---------------------------------------------------------------- tag inheritance

data class InheritanceAssignment(val seriesKey: String, val tagId: String, val inheritMode: String)

data class InheritedRef(val bookId: String, val tagId: String, val seriesKey: String)

data class InheritancePlan(val toAdd: Set<InheritedRef>, val toRemove: Set<InheritedRef>)

object TagInheritancePlanner {
    /**
     * Compares the inherited rows that should exist with those that do. Only EXISTING_AND_FUTURE
     * adds rows for volumes after the assignment; EXISTING is a snapshot taken at assign time and
     * is only cleaned up (volume left the series), never extended. Direct tags are not involved.
     */
    fun plan(
        volumesBySeries: Map<String, Set<String>>,
        assignments: List<InheritanceAssignment>,
        existing: Set<InheritedRef>
    ): InheritancePlan {
        val inheriting = assignments.filter { it.inheritMode != INHERIT_SERIES_ONLY }
            .associateBy { it.seriesKey to it.tagId }
        val toRemove = existing.filter { ref ->
            val a = inheriting[ref.seriesKey to ref.tagId]
            a == null || ref.bookId !in volumesBySeries[ref.seriesKey].orEmpty()
        }.toSet()
        val toAdd = HashSet<InheritedRef>()
        inheriting.values.filter { it.inheritMode == INHERIT_EXISTING_AND_FUTURE }.forEach { a ->
            volumesBySeries[a.seriesKey].orEmpty().forEach { bookId ->
                val ref = InheritedRef(bookId, a.tagId, a.seriesKey)
                if (ref !in existing) toAdd += ref
            }
        }
        return InheritancePlan(toAdd, toRemove)
    }
}

// ---------------------------------------------------------------- read page gate

/**
 * Decides when a position counts as read: it must have been visible for at least the dwell time.
 * A position is confirmed when the reader leaves it (or stays on it long enough, or on [flush]).
 * Fast flipping through a book therefore confirms nothing. State is memory only and may only lose
 * the single position currently on screen; confirmed positions are stored by the caller at once.
 */
class ReadPageGate(private val minDwellMs: () -> Long) {
    private class State(var key: String, var since: Long, var confirmed: Boolean)

    private val states = HashMap<String, State>()

    @Synchronized
    fun onPosition(bookId: String, key: String, nowMs: Long): List<String> {
        val st = states[bookId]
        if (st == null) {
            states[bookId] = State(key, nowMs, false)
            return emptyList()
        }
        if (st.key == key) {
            if (!st.confirmed && nowMs - st.since >= minDwellMs()) {
                st.confirmed = true
                return listOf(key)
            }
            return emptyList()
        }
        val result = if (!st.confirmed && nowMs - st.since >= minDwellMs()) listOf(st.key) else emptyList()
        st.key = key
        st.since = nowMs
        st.confirmed = false
        return result
    }

    /** Call when the reader pauses or closes: confirms the visible position if it was long enough. */
    @Synchronized
    fun flush(bookId: String, nowMs: Long): List<String> {
        val st = states[bookId] ?: return emptyList()
        if (!st.confirmed && nowMs - st.since >= minDwellMs()) {
            st.confirmed = true
            return listOf(st.key)
        }
        return emptyList()
    }

    @Synchronized
    fun reset(bookId: String) {
        states.remove(bookId)
    }

    companion object {
        const val DEFAULT_MIN_DWELL_MS = 4_000L
        fun pdfKey(page: Int): String = "p:$page"
        fun epubKey(chapterIndex: Int, blockIndex: Int): String = "c$chapterIndex:b$blockIndex"
    }
}
