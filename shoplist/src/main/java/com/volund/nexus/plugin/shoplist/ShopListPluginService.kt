package com.volund.nexus.plugin.shoplist

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSpeechCallbacks
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

/**
 * Headless adapter between the Nexus bus and the pure [ShopListState].
 *
 * The card is walked with the R08 ring: NEXT/PREV move the cursor over
 * [Add by voice · items], SELECT starts voice dictation (row 0) or checks the
 * focused item off, BACK closes. Voice uses the hub's speech-to-text
 * ([NexusSpeechSession], `stt` capability): the plugin asks the hub to listen and
 * gets text back — no mic, audio, API key, or network here. Partial results
 * stream while the user speaks; the final goes to an "Add this item?" confirm.
 */
class ShopListPluginService : NexusPluginService() {
    private val state = ShopListState()
    private var store: ShopListStore? = null
    private var surface: NexusSurfaceSession? = null
    private val main = Handler(Looper.getMainLooper())

    private enum class Mode { NORMAL, LISTENING, CONFIRM, NOTICE }
    private var mode = Mode.NORMAL
    private var notice = ""
    private var pendingText = ""
    private var partial = ""

    private var speech: NexusSpeechSession? = null
    // Rough edges the maintainer flagged: stop() is a no-op while PENDING, and
    // isActive can't tell pending from idle — so track intent ourselves.
    private var stopRequested = false
    private var cancelled = false
    private var gotFinal = false
    private var processing = false

    override fun onNexusOpen() {
        store = ShopListStore(this)
        state.setItems(store?.load().orEmpty())
        surface = nexusSurfaceSession(SURFACE_ID)
        mode = Mode.NORMAL
        render(show = true)
    }

    override fun onNexusClose() {
        // The SDK releases the speech session before this runs; just drop refs.
        speech = null
        surface?.hide()
        surface = null
        store = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (mode) {
            Mode.LISTENING -> onListeningInput(event.keyCode)
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
        // Utterance mode: the hub auto-finalizes when the user stops speaking, then
        // sends onSpeechFinal. stop() means CANCEL (it discards a buffered final),
        // so SELECT must NOT stop — only BACK cancels.
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> cancelVoice()
            else -> Unit
        }
    }

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

    private fun onNoticeInput(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> startVoice() // retry
            else -> { mode = Mode.NORMAL; render(show = false) }
        }
    }

    // --- Voice dictation (hub STT -> text) -------------------------------------

    private fun startVoice() {
        partial = ""
        pendingText = ""
        stopRequested = false
        cancelled = false
        gotFinal = false
        processing = false
        val session = nexusSpeechSession(speechCallbacks) ?: run { showNotice("Voice unavailable"); return }
        speech = session
        when (session.start(null)) {
            NexusSdkResult.SENT -> { mode = Mode.LISTENING; render(show = false) }
            NexusSdkResult.CAPABILITY_NOT_GRANTED -> abortVoice("Approve speech-to-text in Plugin access")
            NexusSdkResult.NOT_REGISTERED -> abortVoice("Hub not connected yet — try again")
            NexusSdkResult.CAPABILITY_NOT_AVAILABLE -> abortVoice("Speech-to-text needs a newer hub")
            else -> abortVoice("Voice unavailable")
        }
    }

    /** SELECT while listening does nothing; the hub finalizes on its own (see onListeningInput). */

    /** BACK while listening: discard and return to the list. */
    private fun cancelVoice() {
        cancelled = true
        val s = speech
        if (s != null) { if (s.isActive) s.stop() else stopRequested = true }
        mode = Mode.NORMAL
        render(show = false)
    }

    private val speechCallbacks = object : NexusSpeechCallbacks {
        override fun onSpeechStarted(realtime: Boolean) {
            main.post {
                if (cancelled || stopRequested) { stopRequested = false; speech?.stop() }
            }
        }

        override fun onSpeechState(state: NexusSpeechState) {
            main.post {
                if (cancelled) return@post
                processing = state == NexusSpeechState.PROCESSING
                if (mode == Mode.LISTENING) render(show = false)
            }
        }

        override fun onSpeechPartial(text: String) {
            main.post {
                if (cancelled) return@post
                partial = text
                mode = Mode.LISTENING
                render(show = false)
            }
        }

        override fun onSpeechFinal(text: String) {
            main.post {
                if (cancelled) return@post
                val clean = text.trim().take(120)
                if (clean.isNotBlank()) {
                    gotFinal = true
                    pendingText = clean
                    mode = Mode.CONFIRM
                    render(show = false)
                }
            }
        }

        override fun onSpeechStopped(reason: NexusSpeechStopReason, error: NexusSpeechError?) {
            main.post {
                speech = null
                if (cancelled) { cancelled = false; return@post }
                if (gotFinal) return@post // final already moved us to CONFIRM
                // Early stop with no final: fall back to the last partial if any.
                val salvage = partial.trim().take(120)
                if (salvage.isNotBlank() && (reason == NexusSpeechStopReason.COMPLETED ||
                        reason == NexusSpeechStopReason.CANCELLED)
                ) {
                    pendingText = salvage
                    mode = Mode.CONFIRM
                    render(show = false)
                } else {
                    showNotice(stopText(reason, error))
                }
            }
        }
    }

    private fun abortVoice(message: String) {
        speech = null
        showNotice(message)
    }

    private fun showNotice(message: String) {
        notice = message
        mode = Mode.NOTICE
        render(show = false)
    }

    private fun stopText(reason: NexusSpeechStopReason, error: NexusSpeechError?): String = when (reason) {
        NexusSpeechStopReason.NO_SPEECH -> "Didn't catch that — tap to retry"
        NexusSpeechStopReason.DENIED_BUSY -> "Voice is busy — try again"
        NexusSpeechStopReason.DENIED_NO_LINK, NexusSpeechStopReason.LINK_LOST -> "No connection to the glasses"
        NexusSpeechStopReason.DENIED_NOT_READY -> "Hub not ready — try again"
        NexusSpeechStopReason.REVOKED -> "Speech-to-text was revoked"
        else -> when (error?.kind?.uppercase()) {
            "AUTH" -> "Speech-to-text key rejected — check Nexus speech settings"
            "NETWORK" -> "Speech network error — try again"
            "CONFIG", "NO_ENGINE" -> "Set up speech-to-text in Nexus settings"
            else -> "Couldn't transcribe — tap to retry"
        }
    }

    // --- Rendering -------------------------------------------------------------

    private fun render(show: Boolean) {
        val card = when (mode) {
            Mode.LISTENING -> NexusCard(
                title = if (processing) "Transcribing…" else "Listening…",
                lines = listOf(
                    partial.ifBlank { if (processing) "  One moment…" else "  Speak the item, then pause." },
                ),
                footer = "back to cancel",
                contentKey = "voice-listening-" + Integer.toHexString(
                    (if (processing) "p:" else "l:").plus(partial).hashCode(),
                ),
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
