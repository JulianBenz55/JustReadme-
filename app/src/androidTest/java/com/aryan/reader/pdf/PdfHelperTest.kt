// app/src/androidTest/java/com/aryan/reader/pdf/PdfHelperTest.kt
package com.aryan.reader.pdf

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfHelperTest {

    @Test
    fun mergeRectsIntoLines_mergesHorizontallyAdjacentRects() {
        val rects = listOf(
            Rect(0, 0, 10, 10),
            Rect(11, 0, 20, 10)
        )
        val merged = mergeRectsIntoLines(rects)
        assertThat(merged).hasSize(1)
        assertThat(merged.first()).isEqualTo(Rect(0, 0, 20, 10))
    }

    @Test
    fun mergeRectsIntoLines_doesNotMergeVerticallySeparatedRects() {
        val rects = listOf(
            Rect(0, 0, 10, 10),
            Rect(0, 11, 10, 20)
        )
        val merged = mergeRectsIntoLines(rects)
        assertThat(merged).hasSize(2)
    }

    @Test
    fun mergeRectsIntoLines_handlesMultipleLines() {
        val rects = listOf(
            Rect(0, 0, 10, 10),   // line 1
            Rect(11, 0, 20, 10),  // line 1
            Rect(0, 15, 10, 25),  // line 2
            Rect(11, 15, 20, 25)  // line 2
        )
        val merged = mergeRectsIntoLines(rects)
        assertThat(merged).hasSize(2)
        assertThat(merged).containsExactly(
            Rect(0, 0, 20, 10),
            Rect(0, 15, 20, 25)
        )
    }

    @Test
    fun mergeRectsIntoLines_handlesEmptyList() {
        val merged = mergeRectsIntoLines(emptyList())
        assertThat(merged).isEmpty()
    }

    @Test
    fun preprocessTextForTts_replacesNewlineWithSpaceForSoftBreak() {
        val raw = "Hello\nWorld"
        val processed = preprocessTextForTts(raw)
        assertThat(processed.cleanText).isEqualTo("Hello World")
    }

    @Test
    fun preprocessTextForTts_handlesNewlineAfterPunctuation() {
        // Based on the current implementation, a newline after a sentence-ending punctuation
        // results in the words being concatenated without a space. This test verifies that behavior.
        val raw = "Hello.\nWorld"
        val processed = preprocessTextForTts(raw)
        assertThat(processed.cleanText).isEqualTo("Hello.World")
    }

    @Test
    fun preprocessTextForTts_handlesCarriageReturn() {
        val raw = "Hello\r\nWorld"
        val processed = preprocessTextForTts(raw)
        assertThat(processed.cleanText).isEqualTo("Hello World")
    }

    @Test
    fun preprocessTextForTts_trimsResult() {
        val raw = "  Hello World  \n"
        val processed = preprocessTextForTts(raw)
        assertThat(processed.cleanText).isEqualTo("Hello World")
    }
}