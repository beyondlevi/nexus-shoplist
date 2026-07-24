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

    /**
     * Rows rendered for the HUD card. A `NexusCard` has no scroll/selection concept
     * and the hub renders only the top ~6 rows without scrolling to the cursor
     * (field-gotchas §9). So we PAGINATE the focusable rows (voice action + items)
     * into viewport-sized pages that always contain the focused row.
     */
    fun lines(): List<String> {
        if (items.isEmpty()) {
            return listOf(
                "${if (focus == 0) ">" else " "} + Add item by voice",
                "  (empty - speak an item or add on phone)",
            )
        }
        val rows = allRows()
        val page = focus / ROWS_PER_PAGE
        val start = page * ROWS_PER_PAGE
        val end = minOf(start + ROWS_PER_PAGE, rows.size)
        return rows.subList(start, end)
    }

    /** The full 1:1 list of focusable rows (row 0 = voice action, 1..n = items), each marked. */
    private fun allRows(): List<String> {
        val rows = ArrayList<String>(items.size + 1)
        rows += "${if (focus == 0) ">" else " "} + Add item by voice"
        items.forEachIndexed { index, item ->
            val cursor = if (focus - 1 == index) ">" else " "
            val box = if (item.done) "[x]" else "[ ]"
            rows += "$cursor $box ${item.label}"
        }
        return rows
    }

    fun footer(): String {
        if (focus == 0) return "tap to speak a new item . back"
        val remaining = items.count { !it.done }
        // Position hint so the user knows the list continues past the visible page.
        val pos = "$focus/${items.size}"
        return "move . tap . back  .  $remaining left . $pos"
    }

    /** Stable key over the rendered page so the hub only repaints on a real change (<= 128). */
    fun contentKey(): String {
        var hash = 1
        hash = 31 * hash + focus
        for (line in lines()) hash = 31 * hash + line.hashCode()
        return "shop-" + Integer.toHexString(hash)
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
        // Rows shown per HUD page. The glasses card body renders up to
        // CARD_BODY_MAX_LINES = 15 text lines (verified in glasses-hub
        // SurfaceHudView) and never scrolls to the cursor, so a page must hold the
        // focus and fit that budget. 12 leaves slack for the odd row that wraps to
        // two lines while filling the screen far better than a tiny page.
        const val ROWS_PER_PAGE = 12
    }
}
