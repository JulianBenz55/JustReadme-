package com.aryan.reader.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTrackingRulesTest {
    @Test
    fun openingABookIsNotReading() {
        assertFalse(ReadingTrackingRules.isReadingConfirmed(startProgress = 0.4f, progress = 1.0f))
    }

    @Test
    fun readingFurtherConfirmsReading() {
        assertTrue(ReadingTrackingRules.isReadingConfirmed(startProgress = 0.4f, progress = 4.0f))
    }

    @Test
    fun jumpingToTheLastPageDoesNotFinishImmediately() {
        assertFalse(ReadingTrackingRules.isFinishConfirmed(previousMaxProgress = 20f, progress = 100f, endReachedAt = null, now = 1_000L))
    }

    @Test
    fun staying_at_the_end_long_enough_finishes() {
        val reached = 1_000L
        val later = reached + ReadingTrackingRules.FINISH_DWELL_MS
        assertTrue(ReadingTrackingRules.isFinishConfirmed(previousMaxProgress = 20f, progress = 100f, endReachedAt = reached, now = later))
    }

    @Test
    fun readingThroughTheEndFinishes() {
        assertTrue(ReadingTrackingRules.isFinishConfirmed(previousMaxProgress = 95f, progress = 100f, endReachedAt = null, now = 1_000L))
    }

    @Test
    fun lingeringAtTheEndOfAFinishedPassStartsNoNewPass() {
        assertFalse(ReadingTrackingRules.startsNewPass(latestPassFinished = true, progress = 100f))
        assertTrue(ReadingTrackingRules.startsNewPass(latestPassFinished = true, progress = 2f))
        assertFalse(ReadingTrackingRules.startsNewPass(latestPassFinished = false, progress = 2f))
    }

    @Test
    fun readingNeedsAtLeastOneConfirmedPage() {
        assertFalse(ReadingTrackingRules.isReadingConfirmedByPages(0))
        assertTrue(ReadingTrackingRules.isReadingConfirmedByPages(1))
    }
}
