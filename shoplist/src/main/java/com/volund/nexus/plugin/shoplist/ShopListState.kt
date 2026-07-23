package com.volund.nexus.plugin.shoplist

/**
 * Pure, Android-free state machine for the shopping-list HUD surface.
 *
 * The whole plugin is driven by the R08 ring's single axis: [move] is NEXT/PREV,
 * [toggle] is SELECT, and BACK is handled by the service (close). Keeping this
 * logic free of Android types lets the JUnit suite prove one-axis navigability
 * without any device or emulator.
 */
internal class ShopListState(initial: List<ShopItem> = emptyList()) {
    private val items = ArrayList<ShopItem>(initial)

    var selectedIndex: Int = 0
        private set

    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()

    fun items(): List<ShopItem> = items.toList()

    fun selectedItem(): ShopItem? = items.getOrNull(selectedIndex)

    /** Replace the whole list (e.g. reloaded from storage), keeping selection in range. */
    fun setItems(newItems: List<ShopItem>) {
        items.clear()
        items.addAll(newItems)
        selectedIndex = if (items.isEmpty()) 0 else selectedIndex.coerceIn(0, items.size - 1)
    }

    /** R08 NEXT/PREV: move the single-axis cursor with wrap-around. No-op when empty. */
    fun move(delta: Int) {
        if (items.isEmpty()) return
        selectedIndex = Math.floorMod(selectedIndex + delta, items.size)
    }

    /** R08 SELECT: flip the checked state of the focused row. Returns the new item, or null when empty. */
    fun toggle(): ShopItem? {
        val current = items.getOrNull(selectedIndex) ?: return null
        val updated = current.copy(done = !current.done)
        items[selectedIndex] = updated
        return updated
    }

    /** Rows rendered for the HUD card, capped to a window that never exceeds the surface line limit. */
    fun lines(): List<String> {
        if (items.isEmpty()) return listOf("  (empty - add items on your phone)")
        return visibleRange().map { index ->
            val item = items[index]
            val cursor = if (index == selectedIndex) ">" else " "
            val box = if (item.done) "[x]" else "[ ]"
            "$cursor $box ${item.label}"
        }
    }

    fun footer(): String {
        if (items.isEmpty()) return "back to close"
        val remaining = items.count { !it.done }
        return "move . tap to check . back  .  $remaining left"
    }

    /** Stable key over the rendered content so the hub only repaints on a real change (<= 128 chars). */
    fun contentKey(): String {
        var hash = 1
        for (index in visibleRange()) {
            val item = items[index]
            hash = 31 * hash + item.id.hashCode()
            hash = 31 * hash + item.label.hashCode()
            hash = 31 * hash + (if (item.done) 1 else 0)
            hash = 31 * hash + (if (index == selectedIndex) 1 else 0)
        }
        return "shop-" + Integer.toHexString(hash)
    }

    /** Window of item indices shown on the card, guaranteeing <= [MAX_VISIBLE] rows. */
    private fun visibleRange(): IntRange {
        if (items.size <= MAX_VISIBLE) return items.indices
        val half = MAX_VISIBLE / 2
        var start = (selectedIndex - half).coerceAtLeast(0)
        var end = start + MAX_VISIBLE - 1
        if (end > items.lastIndex) {
            end = items.lastIndex
            start = end - MAX_VISIBLE + 1
        }
        return start..end
    }

    private companion object {
        // Below the 64-row NexusCard ceiling, with margin for the surface envelope.
        const val MAX_VISIBLE = 60
    }
}
