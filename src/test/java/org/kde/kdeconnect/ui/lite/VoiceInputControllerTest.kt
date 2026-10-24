/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui.lite

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceInputControllerTest {
    private lateinit var controller: VoiceInputController
    private lateinit var recognizer: SpeechRecognizer
    private val listeners = mutableListOf<RecognitionListener>()
    private val results = mutableListOf<String>()
    private var available = true
    private var createdCount = 0

    @Before
    fun setUp() {
        recognizer = mockk(relaxed = true)
        every { recognizer.setRecognitionListener(capture(listeners)) } just Runs
        controller = VoiceInputController(
            RuntimeEnvironment.getApplication(),
            { results.add(it) },
            { createdCount++; recognizer },
            { available },
        )
    }

    @After
    fun tearDown() {
        controller.close()
    }

    @Test
    fun missingPermissionOrServiceNeverStartsRecording() {
        controller.start(hasRecordAudioPermission = false)
        assertEquals(VoiceInputError.PERMISSION_REQUIRED, controller.state.value.error)
        assertEquals(0, createdCount)

        available = false
        controller.start(hasRecordAudioPermission = true)
        assertEquals(VoiceInputError.SERVICE_UNAVAILABLE, controller.state.value.error)
        assertEquals(0, createdCount)
    }

    @Test
    fun releasingBeforeServiceIsReadyStillStopsAndAcceptsFinalResult() {
        controller.start(hasRecordAudioPermission = true)
        controller.stop()
        assertEquals(VoiceInputPhase.PROCESSING, controller.state.value.phase)
        verify(exactly = 1) { recognizer.stopListening() }

        listeners.single().onReadyForSpeech(Bundle())
        listeners.single().onBeginningOfSpeech()
        assertEquals(VoiceInputPhase.PROCESSING, controller.state.value.phase)

        listeners.single().onResults(resultBundle("测试 Android SDK"))
        assertEquals(listOf("测试 Android SDK"), results)
        assertEquals(VoiceInputPhase.IDLE, controller.state.value.phase)
        verify(exactly = 1) { recognizer.destroy() }
    }

    @Test
    fun cancelDiscardsPartialAndLateResultsFromOldRequest() {
        controller.start(hasRecordAudioPermission = true)
        val oldListener = listeners.single()
        oldListener.onPartialResults(resultBundle("尚未确认"))
        assertEquals("尚未确认", controller.state.value.partialText)
        assertTrue(results.isEmpty())

        controller.cancel()
        controller.start(hasRecordAudioPermission = true)
        oldListener.onResults(resultBundle("不应追加"))
        oldListener.onError(SpeechRecognizer.ERROR_CLIENT)
        assertTrue(results.isEmpty())
        assertEquals(VoiceInputPhase.STARTING, controller.state.value.phase)

        listeners.last().onResults(resultBundle("新的请求"))
        assertEquals(listOf("新的请求"), results)
    }

    @Test
    fun processingDoesNotAllowASecondConcurrentStart() {
        controller.start(hasRecordAudioPermission = true)
        listeners.single().onReadyForSpeech(Bundle())
        controller.stop()
        controller.start(hasRecordAudioPermission = true)
        assertEquals(1, createdCount)

        listeners.single().onResults(resultBundle("第一段"))
        controller.start(hasRecordAudioPermission = true)
        assertEquals(2, createdCount)
    }

    @Test
    fun elapsedTimeStopsAdvancingWhenRecordingEnds() {
        controller.start(hasRecordAudioPermission = true)
        listeners.single().onReadyForSpeech(Bundle())
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertTrue(controller.state.value.elapsedMillis >= 1_000)

        controller.stop()
        val stoppedAt = controller.state.value.elapsedMillis
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(stoppedAt, controller.state.value.elapsedMillis)
    }

    @Test
    fun stalledStartupAndStalledResultsAreReleased() {
        controller.start(hasRecordAudioPermission = true)
        shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS)
        assertEquals(VoiceInputError.TIMEOUT, controller.state.value.error)
        verify(exactly = 1) { recognizer.cancel() }
        verify(exactly = 1) { recognizer.destroy() }

        controller.start(hasRecordAudioPermission = true)
        listeners.last().onReadyForSpeech(Bundle())
        controller.stop()
        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
        assertEquals(VoiceInputError.TIMEOUT, controller.state.value.error)
        verify(exactly = 2) { recognizer.destroy() }
        assertTrue(results.isEmpty())
    }

    @Test
    fun emptyResultsAndUnknownServiceErrorsDoNotAppendText() {
        controller.start(hasRecordAudioPermission = true)
        listeners.single().onResults(resultBundle("  "))
        assertEquals(VoiceInputError.NO_MATCH, controller.state.value.error)
        assertTrue(results.isEmpty())

        controller.start(hasRecordAudioPermission = true)
        listeners.last().onError(9_999)
        assertEquals(VoiceInputError.UNKNOWN, controller.state.value.error)
        assertEquals(9_999, controller.state.value.platformErrorCode)
        assertTrue(results.isEmpty())
    }

    @Test
    fun closeIgnoresAllLaterCommandsAndCallbacks() {
        controller.start(hasRecordAudioPermission = true)
        controller.close()
        listeners.single().onResults(resultBundle("页面已经关闭"))
        controller.start(hasRecordAudioPermission = true)
        assertEquals(1, createdCount)
        assertTrue(results.isEmpty())
        assertEquals(VoiceInputPhase.IDLE, controller.state.value.phase)
    }

    private fun resultBundle(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }
}
