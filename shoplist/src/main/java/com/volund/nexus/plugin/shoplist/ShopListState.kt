package com.volund.nexus.plugin.shoplist

/**
 * Pure, Android-free state machine for the shopping-list HUD surface.
 *
 * The whole plugin is driven by the R08 ring's single axis. Row 0 is always the
 * "Add by voice" action; rows 1..n are the items. [move] is NEXT/PREV over that
 * combined list, [select] acts on the focused row (start voice, or toggle an
 * item), and BACK is handled by the service. Keeping this logic free of Android
 * types lets the JUnit suite prove one-axis navigability without any hardware.
 */
internal class ShopListState(initial: List<ShopItem> = emptyList()) {
    private val items = ArrayList<ShopItem>(initial)

    /** 0 = the "Add by voice" action row; 1..n = items[focus - 1]. */
    var focus: Int = 0
        private set

    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()
    val rowCount: Int get() = items.size + 1
    val isVoiceRowFocused: Boolean get() = focus == 0

    fun items(): List<ShopItem> = items.toList()

    fun selectedItem(): ShopItem? = if (focus == 0) null else items.getOrNull(focus - 1)

    /** Replace the whole list (e.g. reloaded from storage), keeping focus in range. */
    fun setItems(newItems: List<ShopItem>) {
        items.clear()
        items.addAll(newItems)
        focus = focus.coerceIn(0, items.size)
    }

    /** Move focus onto the item with this id (e.g. one just added by voice), or the voice row. */
    fun focusItem(id: String) {
        val idx = items.indexOfFirst { it.id == id }
        focus = if (idx >= 0) idx + 1 else 0
    }

    /** R08 NEXT/PREV: move the single-axis cursor over [voice row + items] with wrap. */
    fun move(delta: Int) {
        focus = Math.floorMod(focus + delta, items.size + 1)
    }

    /** R08 SELECT on the focused row. */
    fun select(): SelectResult {
        if (focus == 0) return SelectResult.StartVoice
        val idx = focus - 1
        val current = items.getOrNull(idx) ?: return SelectResult.None
        val updated = current.copy(done = !current.done)
        items[idx] = updated
        return SelectResult.Toggled(updated)
    }

    /** Rows rendered for the HUD card: the voice action first, then a window of items. */
    fun lines(): List<String> {
        val out = ArrayList<String>()
        out += "${if (focus == 0) ">" else " "} + Add item by voice"
        if (items.isEmpty()) {
            out += "  (empty - speak an item or add on phone)"
            return out
        }
        for (index in visibleItemRange()) {
            val item = items[index]
            val cursor = if (focus - 1 == index) ">" else " "
            val box = if (item.done) "[x]" else "[ ]"
            out += "$cursor $box ${item.label}"
        }
        return out
    }

    fun footer(): String {
        if (focus == 0) return "tap to speak a new item . back"
        val remaining = items.count { !it.done }
        return "move . tap to check . back  .  $remaining left"
    }

    /** Stable key over the rendered content so the hub only repaints on a real change (<= 128). */
    fun contentKey(): String {
        var hash = 1
        hash = 31 * hash + focus
        for (index in visibleItemRange()) {
            val item = items[index]
            hash = 31 * hash + item.id.hashCode()
            hash = 31 * hash + item.label.hashCode()
            hash = 31 * hash + (if (item.done) 1 else 0)
        }
        return "shop-" + Integer.toHexString(hash)
    }

    /** Window of item indices shown on the card, guaranteeing <= [MAX_VISIBLE] item rows. */
    private fun visibleItemRange(): IntRange {
        if (items.isEmpty()) return IntRange.EMPTY
        if (items.size <= MAX_VISIBLE) return items.indices
        val itemFocus = (focus - 1).coerceAtLeast(0)
        val half = MAX_VISIBLE / 2
        var start = (itemFocus - half).coerceAtLeast(0)
        var end = start + MAX_VISIBLE - 1
        if (end > items.lastIndex) {
            end = items.lastIndex
            start = end - MAX_VISIBLE + 1
        }
        return start..end
    }

    sealed interface SelectResult {
        /** Focus was on the voice action row. */
        object StartVoice : SelectResult
        /** An item row was toggled. */
        data class Toggled(val item: ShopItem) : SelectResult
        /** Nothing actionable. */
        object None : SelectResult
    }

    private companion object {
        // Voice row (1) + items window (<= 59) stays under the 64-row NexusCard cap.
        const val MAX_VISIBLE = 59
    }
}
