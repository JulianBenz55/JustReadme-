// BaseTtsSynthesizerTest.kt
package com.aryan.reader.tts

import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaseTtsSynthesizerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var synthesizer: BaseTtsSynthesizer
    private var ttsEngineAvailable = false

    @Before
    fun setUp() {
        // Ensure TTS is available on the device/emulator before running tests
        val tts = TextToSpeech(ApplicationProvider.getApplicationContext(), null)
        if (tts.engines.isNotEmpty()) {
            ttsEngineAvailable = true
            synthesizer = BaseTtsSynthesizer(ApplicationProvider.getApplicationContext())
        }
        tts.shutdown()
    }

    @After
    fun tearDown() {
        if (this::synthesizer.isInitialized) {
            synthesizer.shutdown()
        }
    }

    @Test
    fun initialize_initializesTtsEngineSuccessfully() {
        if (!ttsEngineAvailable) return

        runBlocking {
            // This will throw if it fails
            withTimeout(10000L) {
                synthesizer.initialize()
            }
            // No assertion needed, success is not throwing an exception.
        }
    }

    @Test
    fun synthesizeToFile_withValidText_createsAudioFile() {
        if (!ttsEngineAvailable) return

        runBlocking {
            withTimeout(15000L) {
                synthesizer.initialize()
                val (file, returnedText) = synthesizer.synthesizeToFile("This is a test.")

                assertThat(file).isNotNull()
                assertThat(file?.exists()).isTrue()
                assertThat(file?.length()).isGreaterThan(0L)
                assertThat(returnedText).isEqualTo("This is a test.")

                file?.delete()
            }
        }
    }

    @Test
    fun synthesizeToFile_withBlankText_returnsNullFile() {
        if (!ttsEngineAvailable) return

        runBlocking {
            synthesizer.initialize()
            val (file, returnedText) = synthesizer.synthesizeToFile("  ")

            assertThat(file).isNull()
            assertThat(returnedText).isEqualTo("  ")
        }
    }

    @Test
    fun synthesizeToFile_withoutInitializingFirst_initializesAndSucceeds() {
        if (!ttsEngineAvailable) return

        runBlocking {
            withTimeout(15000L) {
                val (file, returnedText) = synthesizer.synthesizeToFile("This should work.")

                assertThat(file).isNotNull()
                assertThat(file?.exists()).isTrue()
                assertThat(file?.length()).isGreaterThan(0L)
                assertThat(returnedText).isEqualTo("This should work.")

                file?.delete()
            }
        }
    }
}