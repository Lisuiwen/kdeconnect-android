/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui.lite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class VoiceInputPhase { IDLE, STARTING, LISTENING, PROCESSING, ERROR }

enum class VoiceInputError {
    PERMISSION_REQUIRED,
    SERVICE_UNAVAILABLE,
    AUDIO,
    CLIENT,
    NETWORK,
    NETWORK_TIMEOUT,
    NO_MATCH,
    BUSY,
    SERVER,
    SERVER_DISCONNECTED,
    SPEECH_TIMEOUT,
    TOO_MANY_REQUESTS,
    LANGUAGE_NOT_SUPPORTED,
    LANGUAGE_UNAVAILABLE,
    TIMEOUT,
    UNKNOWN,
}

data class VoiceInputState(
    val phase: VoiceInputPhase = VoiceInputPhase.IDLE,
    val elapsedMillis: Long = 0,
    val partialText: String = "",
    val rmsDb: Float = 0f,
    val error: VoiceInputError? = null,
    val platformErrorCode: Int? = null,
)

/**
 * A single speech recognition request at a time. Both hold-to-talk and tap-to-toggle controls
 * use [start] and [stop]; stopping asks the service to recognise the audio captured so far.
 * Only final, nonempty results reach [onResult]. They are never sent to a remote device here.
 *
 * The system recognition service owns audio routing, including Bluetooth fallback. A connected
 * headset does not tell us which microphone that service actually uses. Audio buffers are not
 * retained, and the service may use its own cloud processing.
 *
 * Call [cancel] when leaving the screen and [close] when its owner is destroyed. Public methods
 * marshal work to the main thread, as required by SpeechRecognizer.
 */
class VoiceInputController internal constructor(
    context: Context,
    private val onResult: (String) -> Unit,
    private val recognizerFactory: (Context) -> SpeechRecognizer,
    private val recognitionAvailable: (Context) -> Boolean,
) : AutoCloseable {
    constructor(context: Context, onResult: (String) -> Unit) : this(
        context,
        onResult,
        { SpeechRecognizer.createSpeechRecognizer(it) },
        { SpeechRecognizer.isRecognitionAvailable(it) },
    )

    private val applicationContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(VoiceInputState())
    val state: StateFlow<VoiceInputState> = mutableState.asStateFlow()

    private var session: Session? = null
    private var closed = false

    private class Session(val recognizer: SpeechRecognizer) {
        var listeningStartedAt: Long? = null
        var stoppedAt: Long? = null
        var stopRequested = false
        var timeout: Runnable? = null
        var ticker: Runnable? = null
    }

    /** Permission is requested by the Activity, never from a background callback. */
    fun start(hasRecordAudioPermission: Boolean) = onMain {
        if (closed || session != null) return@onMain
        if (!hasRecordAudioPermission) {
            mutableState.value = VoiceInputState(
                phase = VoiceInputPhase.ERROR,
                error = VoiceInputError.PERMISSION_REQUIRED,
            )
            return@onMain
        }

        mutableState.value = VoiceInputState(phase = VoiceInputPhase.STARTING)
        try {
            if (!recognitionAvailable(applicationContext)) {
                mutableState.value = VoiceInputState(
                    phase = VoiceInputPhase.ERROR,
                    error = VoiceInputError.SERVICE_UNAVAILABLE,
                )
                return@onMain
            }
            val request = Session(recognizerFactory(applicationContext))
            session = request
            request.recognizer.setRecognitionListener(listener(request))
            scheduleTimeout(request, START_TIMEOUT_MILLIS)
            request.recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        } catch (_: SecurityException) {
            fail(VoiceInputError.PERMISSION_REQUIRED)
        } catch (_: RuntimeException) {
            fail(VoiceInputError.CLIENT)
        }
    }

    /** Release a hold gesture, or stop a tap-to-toggle request, without discarding its result. */
    fun stop() = onMain {
        val request = session ?: return@onMain
        if (request.stopRequested) return@onMain
        finishListening(request)
        try {
            // SpeechRecognizer queues this even while its service is still binding. A late
            // onReadyForSpeech must not move the UI back to LISTENING after the user releases.
            request.recognizer.stopListening()
        } catch (_: SecurityException) {
            fail(VoiceInputError.PERMISSION_REQUIRED)
        } catch (_: RuntimeException) {
            fail(VoiceInputError.CLIENT)
        }
    }

    /** Discard the pending request, including any results delivered after leaving the screen. */
    fun cancel() = onMain {
        releaseSession(cancel = true)
        mutableState.value = VoiceInputState()
    }

    override fun close() = onMain {
        closed = true
        releaseSession(cancel = true)
        mutableState.value = VoiceInputState()
    }

    private fun listener(request: Session) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = withSession(request) {
            beginListening(request)
        }

        override fun onBeginningOfSpeech() = withSession(request) {
            beginListening(request)
        }

        override fun onEndOfSpeech() = withSession(request) {
            finishListening(request)
        }

        override fun onRmsChanged(rmsdB: Float) = withSession(request) {
            if (!request.stopRequested) {
                mutableState.value = mutableState.value.copy(rmsDb = rmsdB.coerceIn(0f, 12f))
            }
        }

        override fun onPartialResults(partialResults: Bundle?) = withSession(request) {
            mutableState.value = mutableState.value.copy(partialText = firstResult(partialResults).orEmpty())
        }

        override fun onResults(results: Bundle?) = withSession(request) {
            val text = firstResult(results)
            if (text == null) {
                fail(VoiceInputError.NO_MATCH)
            } else {
                val elapsed = elapsedMillis(request)
                releaseSession(cancel = false)
                mutableState.value = VoiceInputState(elapsedMillis = elapsed)
                onResult(text)
            }
        }

        override fun onError(error: Int) = withSession(request) {
            fail(errorFor(error), error)
        }

        // Do not store or forward the raw audio supplied by some recognition services.
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun beginListening(request: Session) {
        if (request.stopRequested || request.listeningStartedAt != null) return
        request.listeningStartedAt = SystemClock.elapsedRealtime()
        clearTimeout(request)
        mutableState.value = mutableState.value.copy(phase = VoiceInputPhase.LISTENING)
        val ticker = object : Runnable {
            override fun run() {
                if (session !== request || request.stopRequested) return
                mutableState.value = mutableState.value.copy(elapsedMillis = elapsedMillis(request))
                handler.postDelayed(this, TIMER_INTERVAL_MILLIS)
            }
        }
        request.ticker = ticker
        handler.post(ticker)
    }

    private fun finishListening(request: Session) {
        if (request.stopRequested) return
        request.stopRequested = true
        request.stoppedAt = SystemClock.elapsedRealtime()
        request.ticker?.let(handler::removeCallbacks)
        mutableState.value = mutableState.value.copy(
            phase = VoiceInputPhase.PROCESSING,
            elapsedMillis = elapsedMillis(request),
            rmsDb = 0f,
        )
        scheduleTimeout(request, RESULT_TIMEOUT_MILLIS)
    }

    private fun fail(error: VoiceInputError, platformErrorCode: Int? = null) {
        val elapsed = session?.let(::elapsedMillis) ?: 0
        releaseSession(cancel = true)
        mutableState.value = mutableState.value.copy(
            phase = VoiceInputPhase.ERROR,
            elapsedMillis = elapsed,
            rmsDb = 0f,
            error = error,
            platformErrorCode = platformErrorCode,
        )
    }

    private fun releaseSession(cancel: Boolean) {
        val request = session ?: return
        // Invalidate first: cancelling or destroying a service can synchronously cause callbacks.
        session = null
        clearTimeout(request)
        request.ticker?.let(handler::removeCallbacks)
        if (cancel) runCatching { request.recognizer.cancel() }
        runCatching { request.recognizer.destroy() }
    }

    private fun scheduleTimeout(request: Session, timeoutMillis: Long) {
        clearTimeout(request)
        val timeout = Runnable {
            if (session === request) fail(VoiceInputError.TIMEOUT)
        }
        request.timeout = timeout
        handler.postDelayed(timeout, timeoutMillis)
    }

    private fun clearTimeout(request: Session) {
        request.timeout?.let(handler::removeCallbacks)
        request.timeout = null
    }

    private fun elapsedMillis(request: Session): Long {
        val startedAt = request.listeningStartedAt ?: return 0
        return ((request.stoppedAt ?: SystemClock.elapsedRealtime()) - startedAt).coerceAtLeast(0)
    }

    private fun firstResult(results: Bundle?): String? = results
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()

    private fun withSession(request: Session, action: () -> Unit) = onMain {
        if (!closed && session === request) action()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    companion object {
        private const val START_TIMEOUT_MILLIS = 15_000L
        private const val RESULT_TIMEOUT_MILLIS = 30_000L
        private const val TIMER_INTERVAL_MILLIS = 100L

        internal fun errorFor(error: Int): VoiceInputError = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> VoiceInputError.AUDIO
            SpeechRecognizer.ERROR_CLIENT -> VoiceInputError.CLIENT
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceInputError.PERMISSION_REQUIRED
            SpeechRecognizer.ERROR_NETWORK -> VoiceInputError.NETWORK
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceInputError.NETWORK_TIMEOUT
            SpeechRecognizer.ERROR_NO_MATCH -> VoiceInputError.NO_MATCH
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceInputError.BUSY
            SpeechRecognizer.ERROR_SERVER -> VoiceInputError.SERVER
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> VoiceInputError.SERVER_DISCONNECTED
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceInputError.SPEECH_TIMEOUT
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> VoiceInputError.TOO_MANY_REQUESTS
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> VoiceInputError.LANGUAGE_NOT_SUPPORTED
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceInputError.LANGUAGE_UNAVAILABLE
            else -> VoiceInputError.UNKNOWN
        }
    }
}
