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

/**
 * Headless adapter between the Nexus bus and the pure [ShopListState].
 *
 * Runs only while the plugin is open. The card is walked with the R08 ring:
 * NEXT/PREV move the cursor over [Add by voice · items], SELECT starts voice
 * dictation (row 0) or checks the focused item off, BACK closes. Voice uses the
 * Nexus mic (`nexusAudioSession`) piped into on-device STT ([VoiceItemRecognizer]).
 */
class ShopListPluginService : NexusPluginService() {
    private val state = ShopListState()
    private var store: ShopListStore? = null
    private var surface: NexusSurfaceSession? = null
    private val main = Handler(Looper.getMainLooper())

    private enum class Mode { NORMAL, LISTENING, NOTICE }
    private var mode = Mode.NORMAL
    private var partial = ""
    private var notice = ""

    private var audio: NexusAudioSession? = null
    private var recognizer: VoiceItemRecognizer? = null

    override fun onNexusOpen() {
        store = ShopListStore(this)
        state.setItems(store?.load().orEmpty())
        surface = nexusSurfaceSession(SURFACE_ID)
        mode = Mode.NORMAL
        render(show = true)
    }

    override fun onNexusClose() {
        stopVoice()
        surface?.hide()
        surface = null
        store = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (mode) {
            Mode.LISTENING -> onListeningInput(event.keyCode)
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
                    ShopListState.SelectResult.StartVoice -> {
                        startVoice()
                        return
                    }
                    is ShopListState.SelectResult.Toggled -> store?.save(state.items())
                    ShopListState.SelectResult.None -> Unit
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                surface?.hide()
                return
            }
            else -> return
        }
        render(show = false)
    }

    private fun onListeningInput(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                recognizer?.finishInput() // stop speaking -> finalize
            KeyEvent.KEYCODE_BACK -> {
                stopVoice()
                mode = Mode.NORMAL
                render(show = false)
            }
            else -> Unit
        }
    }

    private fun onNoticeInput(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> startVoice() // retry
            else -> {
                mode = Mode.NORMAL
                render(show = false)
            }
        }
    }

    // --- Voice capture ---------------------------------------------------------

    private fun startVoice() {
        if (mode == Mode.LISTENING) return
        val rec = VoiceItemRecognizer(this, store?.voiceLanguage(), recognizerListener)
        rec.unavailableReason()?.let { showNotice(it); return }
        val session = nexusAudioSession(audioCallbacks) ?: run {
            showNotice("Voice unavailable"); return
        }
        recognizer = rec
        if (!rec.start()) return // recognizerListener.onError already fired -> notice
        when (session.start()) {
            NexusSdkResult.SENT -> {
                audio = session
                partial = ""
                mode = Mode.LISTENING
                render(show = false)
            }
            NexusSdkResult.CAPABILITY_NOT_GRANTED ->
                abortVoice("Approve the microphone in Plugin access")
            else -> abortVoice("Glasses mic unavailable")
        }
    }

    private val audioCallbacks = object : NexusAudioCallbacks {
        override fun onAudioStarted(format: NexusAudioFormat) = Unit
        override fun onAudioFrame(pcm: ByteArray, seq: Long, elapsedRealtimeMs: Long) {
            recognizer?.feed(pcm)
        }
        override fun onAudioStopped(reason: NexusAudioStopReason) {
            main.post { if (mode == Mode.LISTENING) recognizer?.finishInput() }
        }
    }

    private val recognizerListener = object : VoiceItemRecognizer.Listener {
        override fun onPartial(text: String) {
            if (mode != Mode.LISTENING) return
            partial = text
            render(show = false)
        }

        override fun onFinal(text: String) {
            stopAudio()
            recognizer = null
            val added = store?.add(text) == true
            store?.let { state.setItems(it.load()) }
            if (added) state.items().lastOrNull()?.let { state.focusItem(it.id) }
            mode = Mode.NORMAL
            render(show = false)
        }

        override fun onError(message: String) {
            stopAudio()
            recognizer = null
            showNotice(message)
        }
    }

    private fun abortVoice(message: String) {
        recognizer?.cancel()
        recognizer = null
        stopAudio()
        showNotice(message)
    }

    private fun stopVoice() {
        recognizer?.cancel()
        recognizer = null
        stopAudio()
    }

    private fun stopAudio() {
        audio?.stop()
        audio = null
    }

    private fun showNotice(message: String) {
        notice = message
        mode = Mode.NOTICE
        render(show = false)
    }

    // --- Rendering -------------------------------------------------------------

    private fun render(show: Boolean) {
        val card = when (mode) {
            Mode.LISTENING -> NexusCard(
                title = "Listening…",
                lines = listOf(partial.ifBlank { "  (speak the item)" }),
                footer = "tap to add . back to cancel",
                contentKey = "voice-live-" + Integer.toHexString(partial.hashCode()),
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
