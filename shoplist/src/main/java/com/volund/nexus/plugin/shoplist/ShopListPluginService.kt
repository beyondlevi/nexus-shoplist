package com.volund.nexus.plugin.shoplist

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

/**
 * Headless adapter between the Nexus bus and the pure [ShopListState].
 *
 * Runs only while the plugin is open. The whole surface is a single card the
 * user walks with the R08 ring: NEXT/PREV move the cursor, SELECT checks the
 * focused item off (persisted immediately), BACK hides the surface (self-close).
 */
class ShopListPluginService : NexusPluginService() {
    private val state = ShopListState()
    private var store: ShopListStore? = null
    private var surface: NexusSurfaceSession? = null

    override fun onNexusOpen() {
        val store = ShopListStore(this).also { this.store = it }
        state.setItems(store.load())
        surface = nexusSurfaceSession(SURFACE_ID)
        render(show = true)
    }

    override fun onNexusClose() {
        surface?.hide()
        surface = null
        store = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> state.move(1)
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            -> state.move(-1)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> {
                state.toggle()
                store?.save(state.items())
            }
            KeyEvent.KEYCODE_BACK -> {
                surface?.hide()
                return
            }
            else -> return
        }
        render(show = false)
    }

    private fun render(show: Boolean) {
        val card = NexusCard(
            title = "Shopping List",
            lines = state.lines(),
            footer = state.footer(),
            contentKey = state.contentKey(),
        )
        if (show) surface?.showCard(card) else surface?.updateCard(card)
    }

    private companion object {
        const val SURFACE_ID = "main"
    }
}
