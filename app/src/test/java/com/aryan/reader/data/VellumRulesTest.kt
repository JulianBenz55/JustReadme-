package com.aryan.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VellumRulesTest {
    private fun vol(finished: Boolean = false, started: Boolean = finished) = VolumeState(finished, started)

    // ---- series progress ----
    @Test
    fun halfOfTenVolumesIsFiftyPercentAndPartial() {
        val volumes = List(10) { vol(finished = it < 5) }
        val p = SeriesProgressCalculator.calculate(volumes, null, false)
        assertEquals(50f, p.percent, 0.01f)
        assertEquals(SeriesStatus.PARTIALLY_READ, p.status)
    }

    @Test
    fun allPublishedReadIsNotCompleted() {
        val p = SeriesProgressCalculator.calculate(List(10) { vol(true) }, null, storyComplete = false)
        assertEquals(SeriesStatus.ALL_PUBLISHED_READ, p.status)
    }

    @Test
    fun newEleventhVolumeLowersProgressToTenOfEleven() {
        val p = SeriesProgressCalculator.calculate(List(10) { vol(true) } + vol(), null, false)
        assertEquals(10, p.finishedVolumes)
        assertEquals(11, p.totalVolumes)
        assertEquals(SeriesStatus.PARTIALLY_READ, p.status)
    }

    @Test
    fun completedOnlyWhenStoryMarkedComplete() {
        val p = SeriesProgressCalculator.calculate(List(3) { vol(true) }, null, storyComplete = true)
        assertEquals(SeriesStatus.COMPLETED, p.status)
    }

    @Test
    fun knownVolumeCountAboveLibraryPreventsAllRead() {
        val p = SeriesProgressCalculator.calculate(List(3) { vol(true) }, knownVolumeCount = 5, storyComplete = false)
        assertEquals(SeriesStatus.PARTIALLY_READ, p.status)
        assertEquals(5, p.totalVolumes)
    }

    @Test
    fun untouchedSeriesIsNotStarted() {
        assertEquals(SeriesStatus.NOT_STARTED, SeriesProgressCalculator.calculate(List(4) { vol() }, null, false).status)
    }

    // ---- inheritance ----
    private val a = InheritanceAssignment("overgeared", "fantasy", INHERIT_EXISTING_AND_FUTURE)

    @Test
    fun existingAndFutureCoversExistingAndNewVolumes() {
        val plan = TagInheritancePlanner.plan(mapOf("overgeared" to setOf("b1", "b2")), listOf(a), emptySet())
        assertEquals(setOf(InheritedRef("b1", "fantasy", "overgeared"), InheritedRef("b2", "fantasy", "overgeared")), plan.toAdd)
        val withNew = TagInheritancePlanner.plan(
            mapOf("overgeared" to setOf("b1", "b2", "b3")), listOf(a), plan.toAdd
        )
        assertEquals(setOf(InheritedRef("b3", "fantasy", "overgeared")), withNew.toAdd)
        assertTrue(withNew.toRemove.isEmpty())
    }

    @Test
    fun existingModeDoesNotTouchNewVolumes() {
        val existing = setOf(InheritedRef("b1", "fantasy", "overgeared"))
        val plan = TagInheritancePlanner.plan(
            mapOf("overgeared" to setOf("b1", "b2")), listOf(a.copy(inheritMode = INHERIT_EXISTING)), existing
        )
        assertTrue(plan.toAdd.isEmpty())
        assertTrue(plan.toRemove.isEmpty())
    }

    @Test
    fun removingAssignmentRemovesOnlyInheritedRows() {
        val existing = setOf(InheritedRef("b1", "fantasy", "overgeared"))
        val plan = TagInheritancePlanner.plan(mapOf("overgeared" to setOf("b1")), emptyList(), existing)
        assertEquals(existing, plan.toRemove)
        assertTrue(plan.toAdd.isEmpty())
    }

    @Test
    fun volumeLeavingSeriesLosesInheritedTag() {
        val existing = setOf(InheritedRef("b1", "fantasy", "overgeared"))
        val plan = TagInheritancePlanner.plan(mapOf("overgeared" to setOf("b2")), listOf(a), existing)
        assertEquals(existing, plan.toRemove)
    }

    // ---- read page gate ----
    private fun gate() = ReadPageGate { 4_000L }

    @Test
    fun pageConfirmedOnlyAfterDwellWhenLeaving() {
        val g = gate()
        assertTrue(g.onPosition("b", "p:1", 0).isEmpty())
        assertEquals(listOf("p:1"), g.onPosition("b", "p:2", 5_000))
    }

    @Test
    fun fastSkippingConfirmsNothing() {
        val g = gate()
        val confirmed = (1..30).flatMap { g.onPosition("b", "p:$it", it * 200L) }
        assertTrue(confirmed.isEmpty())
    }

    @Test
    fun stayingOnPageConfirmsItOnce() {
        val g = gate()
        g.onPosition("b", "p:1", 0)
        assertEquals(listOf("p:1"), g.onPosition("b", "p:1", 4_500))
        assertTrue(g.onPosition("b", "p:1", 9_000).isEmpty())
    }

    @Test
    fun flushConfirmsLongVisiblePageOnly() {
        val g = gate()
        g.onPosition("b", "p:1", 0)
        assertEquals(listOf("p:1"), g.flush("b", 6_000))
        g.onPosition("b", "p:2", 10_000)
        assertTrue(g.flush("b", 10_500).isEmpty())
    }

    @Test
    fun epubKeyIsIndependentOfFontSize() {
        assertEquals("c3:b17", ReadPageGate.epubKey(3, 17))
        assertEquals("p:12", ReadPageGate.pdfKey(12))
    }
}
