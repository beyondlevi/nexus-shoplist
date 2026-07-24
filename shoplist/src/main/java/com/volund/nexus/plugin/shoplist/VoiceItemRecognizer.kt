package com.volund.nexus.plugin.shoplist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException

/**
 * On-device speech-to-text for a single spoken shopping-list item.
 *
 * Transposed from Rokid-Relay's `AndroidCxrSpeechRecognizer` (the ANDROID_CXR
 * engine): the plugin owns the glasses mic via the Nexus audio session and pipes
 * the PCM into Android's [SpeechRecognizer] through `EXTRA_AUDIO_SOURCE`
 * (injected audio). No device mic is opened here, no cloud call, no model
 * bundled. Feed 16 kHz mono PCM16LE frames with [feed]; call [finishInput] to
 * end the utterance (recognizer finalizes on EOF) or [cancel] to abort.
 *
 * Requires Android 13+ and the RECORD_AUDIO runtime permission (the framework
 * gates SpeechRecognizer on it even for injected audio).
 */
class VoiceItemRecognizer(
    context: Context,
    private val languageTag: String?,
    private val listener: Listener,
) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioLock = Any()

    private var recognizer: SpeechRecognizer? = null
    private var pipeRead: ParcelFileDescriptor? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    private var pipeOut: FileOutputStream? = null
    private var active = false
    private var finished = false
    private var bestPartial = ""

    /** Reason the environment can't run injected-audio STT, or null when it can. */
    fun unavailableReason(): String? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "Voice needs Android 13+"
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED -> "Allow the mic in plugin settings"
        !SpeechRecognizer.isRecognitionAvailable(appContext) -> "Speech recognition unavailable"
        else -> null
    }

    /** Create the pipe + recognizer and begin listening on the injected audio source. */
    fun start(): Boolean {
        unavailableReason()?.let { listener.onError(it); return false }
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse {
            listener.onError("Could not open audio pipe"); return false
        }
        synchronized(audioLock) {
            pipeRead = pipe[0]
            pipeWrite = pipe[1]
            pipeOut = FileOutputStream(pipe[1].fileDescriptor)
            active = true
            finished = false
            bestPartial = ""
        }
        val sr = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrElse {
            cleanup(); listener.onError("Recognizer unavailable"); return false
        }
        recognizer = sr
        sr.setRecognitionListener(recognitionListener(sr))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            languageTag?.takeIf { it.isNotBlank() }?.let { tag ->
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            }
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        return runCatching {
            sr.startListening(intent)
            true
        }.getOrElse { error ->
            Log.w(TAG, "startListening failed", error)
            fail("Voice recognizer failed to start"); false
        }
    }

    /** Feed one PCM frame (16 kHz mono PCM16LE) from the Nexus audio session. */
    fun feed(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size) {
        if (length <= 0) return
        synchronized(audioLock) {
            if (!active || finished) return
            val safeOffset = offset.coerceIn(0, pcm.size)
            val safeLength = length.coerceAtMost(pcm.size - safeOffset)
            if (safeLength <= 0) return
            try {
                pipeOut?.write(pcm, safeOffset, safeLength)
            } catch (error: IOException) {
                main.post { fail("Audio pipe failed") }
            }
        }
    }

    /** End the utterance: close the write side so the recognizer finalizes on EOF. */
    fun finishInput() {
        synchronized(audioLock) {
            if (!active) return
            active = false
            closeWriteLocked()
        }
        main.post { runCatching { recognizer?.stopListening() } }
    }

    fun cancel() {
        finish { runCatching { recognizer?.cancel() } }
    }

    private fun recognitionListener(owner: SpeechRecognizer): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (finished || recognizer !== owner) return
                val partial = bestPartial.trim()
                if (partial.isNotBlank() && error.allowsPartialFallback()) {
                    complete(partial)
                } else {
                    fail(error.toMessage())
                }
            }

            override fun onResults(results: Bundle?) {
                if (finished || recognizer !== owner) return
                val text = results.best().ifBlank { bestPartial }
                complete(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (finished || recognizer !== owner) return
                val partial = partialResults.best()
                if (partial.isNotBlank()) {
                    bestPartial = partial
                    listener.onPartial(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

    private fun complete(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) {
            fail("No speech recognized")
            return
        }
        finish { listener.onFinal(clean) }
    }

    private fun fail(message: String) {
        finish { listener.onError(message) }
    }

    private fun finish(afterCleanup: () -> Unit) {
        if (finished) return
        finished = true
        cleanup()
        val local = recognizer
        recognizer = null
        runCatching { local?.cancel() }
        runCatching { local?.destroy() }
        afterCleanup()
    }

    private fun cleanup() {
        synchronized(audioLock) {
            active = false
            closeWriteLocked()
            runCatching { pipeRead?.close() }
            pipeRead = null
        }
    }

    private fun closeWriteLocked() {
        runCatching { pipeOut?.close() }
        runCatching { pipeWrite?.close() }
        pipeOut = null
        pipeWrite = null
    }

    private fun Bundle?.best(): String {
        val values = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        return values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun Int.allowsPartialFallback(): Boolean =
        this == SpeechRecognizer.ERROR_NO_MATCH ||
            this == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
            this == SpeechRecognizer.ERROR_SERVER_DISCONNECTED

    private fun Int.toMessage(): String = when (this) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        SpeechRecognizer.ERROR_AUDIO -> "Audio capture error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the mic in plugin settings"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Speech language unavailable"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech network error"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        else -> "Voice error $this"
    }

    private companion object {
        const val TAG = "ShopListVoice"
        const val SAMPLE_RATE_HZ = 16_000
    }
}
