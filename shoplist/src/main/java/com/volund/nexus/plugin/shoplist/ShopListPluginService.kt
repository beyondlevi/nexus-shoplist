package com.volund.nexus.plugin.shoplist

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusAudioCallbacks
import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import com.anezium.rokidbus.client.plugin.NexusAudioSession
import com.anezium.rokidbus.client.plugin.NexusAudioStopReason
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import java.io.ByteArrayOutputStream

/**
 * Headless adapter between the Nexus bus and the pure [ShopListState].
 *
 * The card is walked with the R08 ring: NEXT/PREV move the cursor over
 * [Add by voice · items], SELECT starts voice dictation (row 0) or checks the
 * focused item off, BACK closes. Voice buffers the glasses mic PCM
 * (`nexusAudioSession`, delivered because the manifest declares the `microphone`
 * capability AND the `/audio` receive prefix) and transcribes it with OpenAI
 * ([SpeechToText]) — the buffered path proven in rokid-inbox-nexus.
 */
class ShopListPluginService : NexusPluginService() {
    private val state = ShopListState()
    private var store: ShopListStore? = null
    private var surface: NexusSurfaceSession? = null
    private val main = Handler(Looper.getMainLooper())

    private enum class Mode { NORMAL, LISTENING, TRANSCRIBING, CONFIRM, NOTICE }
    private var mode = Mode.NORMAL
    private var notice = ""
    private var pendingText = ""

    private var audio: NexusAudioSession? = null
    private val micLock = Any()
    private val micBuffer = ByteArrayOutputStream()
    private var micSampleRate = 16_000
    private var listening = false
    private var cancelDictation = false

    override fun onNexusOpen() {
        store = ShopListStore(this)
        state.setItems(store?.load().orEmpty())
        surface = nexusSurfaceSession(SURFACE_ID)
        mode = Mode.NORMAL
        render(show = true)
    }

    override fun onNexusClose() {
        audio?.stop()
        audio = null
        surface?.hide()
        surface = null
        store = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (mode) {
            Mode.LISTENING -> onListeningInput(event.keyCode)
            Mode.TRANSCRIBING -> Unit // busy; ignore input until the result lands
            Mode.CONFIRM -> onConfirmInput(event.keyCode)
            Mode.NOTICE -> onNoticeInput(event.keyCode)
            Mode.NORMAL -> onNormalInput(event.keyCode)
        }
    }

    private fun onNormalInput(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> state.move(1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> state.move(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (state.select()) {
                    ShopListState.SelectResult.StartVoice -> { startVoice(); return }
                    is ShopListState.SelectResult.Toggled -> store?.save(state.items())
                    ShopListState.SelectResult.None -> Unit
                }
            }
            KeyEvent.KEYCODE_BACK -> { surface?.hide(); return }
            else -> return
        }
        render(show = false)
    }

    private fun onListeningInput(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Stop speaking -> release the lease, which triggers transcription.
                mode = Mode.TRANSCRIBING
                render(show = false)
                audio?.stop()
            }
            KeyEvent.KEYCODE_BACK -> {
                cancelDictation = true
                audio?.stop()
                mode = Mode.NORMAL
                render(show = false)
            }
            else -> Unit
        }
    }

    private fun onNoticeInput(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> startVoice() // retry
            else -> { mode = Mode.NORMAL; render(show = false) }
        }
    }

    /** Confirm screen after transcription: SELECT adds the item, BACK discards it. */
    private fun onConfirmInput(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val store = this.store
                val added = store?.add(pendingText) == true
                if (added) {
                    state.setItems(store!!.load())
                    state.items().lastOrNull()?.let { state.focusItem(it.id) }
                }
                pendingText = ""
                mode = Mode.NORMAL
                render(show = false)
            }
            KeyEvent.KEYCODE_BACK -> {
                pendingText = ""
                mode = Mode.NORMAL
                render(show = false)
            }
            else -> Unit
        }
    }

    // --- Voice capture (mic -> buffer -> OpenAI STT) ---------------------------

    private fun startVoice() {
        val store = this.store ?: return
        if (!store.voiceReady()) {
            showNotice("Enable voice + set the OpenAI key in plugin settings")
            return
        }
        synchronized(micLock) { micBuffer.reset(); listening = false }
        cancelDictation = false
        val session = nexusAudioSession(audioCallbacks) ?: run { showNotice("Voice unavailable"); return }
        audio = session
        when (session.start()) {
            NexusSdkResult.SENT -> { mode = Mode.LISTENING; render(show = false) }
            NexusSdkResult.CAPABILITY_NOT_GRANTED ->
                abortVoice("Approve the microphone in Plugin access")
            NexusSdkResult.NOT_REGISTERED -> abortVoice("Hub not connected yet — try again")
            else -> abortVoice("Glasses mic unavailable")
        }
    }

    private val audioCallbacks = object : NexusAudioCallbacks {
        override fun onAudioStarted(format: NexusAudioFormat) {
            main.post {
                synchronized(micLock) {
                    micSampleRate = if (format.sampleRate > 0) format.sampleRate else 16_000
                    micBuffer.reset()
                    listening = true
                }
                if (mode == Mode.LISTENING) render(show = false)
            }
        }

        override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) {
            synchronized(micLock) { if (listening) micBuffer.write(pcm) }
        }

        override fun onAudioStopped(reason: NexusAudioStopReason) {
            main.post {
                val bytes = synchronized(micLock) {
                    listening = false
                    micBuffer.toByteArray().also { micBuffer.reset() }
                }
                audio = null
                if (cancelDictation) { cancelDictation = false; mode = Mode.NORMAL; render(show = false); return@post }
                if (reason != NexusAudioStopReason.RELEASED) {
                    showNotice(micErrorText(reason)); return@post
                }
                transcribe(bytes)
            }
        }
    }

    private fun transcribe(bytes: ByteArray) {
        val store = this.store ?: return
        mode = Mode.TRANSCRIBING
        render(show = false)
        val stt = SpeechToText(store.openAiKey(), store.sttModel(), store.sttLanguage())
        val rate = synchronized(micLock) { micSampleRate }
        Thread {
            val result = runCatching { stt.transcribe(bytes, rate) }
            main.post {
                result.onSuccess { text ->
                    if (text.isBlank()) {
                        showNotice("Didn't catch that — tap to retry")
                    } else {
                        pendingText = text.trim().take(120) // matches the store's label cap
                        mode = Mode.CONFIRM
                        render(show = false)
                    }
                }.onFailure {
                    showNotice("Transcription failed: ${it.message?.take(140).orEmpty()}")
                }
            }
        }.start()
    }

    private fun abortVoice(message: String) {
        audio?.stop()
        audio = null
        showNotice(message)
    }

    private fun showNotice(message: String) {
        notice = message
        mode = Mode.NOTICE
        render(show = false)
    }

    private fun micErrorText(reason: NexusAudioStopReason): String = when (reason) {
        NexusAudioStopReason.REVOKED -> "Mic lost (link dropped or another app took it)"
        NexusAudioStopReason.DENIED_BUSY -> "Mic in use by another plugin"
        NexusAudioStopReason.DENIED_NO_LINK -> "No connection to the glasses"
        NexusAudioStopReason.DENIED_NOT_GRANTED -> "Approve the microphone in Plugin access"
        else -> "Could not capture audio"
    }

    // --- Rendering -------------------------------------------------------------

    private fun render(show: Boolean) {
        val card = when (mode) {
            Mode.LISTENING -> NexusCard(
                title = "Listening…",
                lines = listOf("  Speak the item, then tap to add."),
                footer = "tap to add . back to cancel",
                contentKey = "voice-listening",
                handlesBack = true,
            )
            Mode.TRANSCRIBING -> NexusCard(
                title = "Transcribing…",
                lines = listOf("  One moment."),
                footer = "please wait",
                contentKey = "voice-transcribing",
                handlesBack = true,
            )
            Mode.CONFIRM -> NexusCard(
                title = "Add this item?",
                lines = listOf("  \"$pendingText\""),
                footer = "tap to add . back to discard",
                contentKey = "voice-confirm-" + Integer.toHexString(pendingText.hashCode()),
                handlesBack = true,
            )
            Mode.NOTICE -> NexusCard(
                title = "Add by voice",
                lines = listOf("  $notice"),
                footer = "tap to retry . back",
                contentKey = "voice-note-" + Integer.toHexString(notice.hashCode()),
                handlesBack = true,
            )
            Mode.NORMAL -> NexusCard(
                title = "Shopping List",
                lines = state.lines(),
                footer = state.footer(),
                contentKey = state.contentKey(),
                handlesBack = true,
            )
        }
        if (show) surface?.showCard(card) else surface?.updateCard(card)
    }

    private companion object {
        const val SURFACE_ID = "main"
    }
}
