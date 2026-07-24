package com.volund.nexus.plugin.shoplist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the whole surface is operable on the R08 ring's single axis: row 0 is
 * the "Add by voice" action, rows 1..n are items; move wraps over the combined
 * list, select starts voice or toggles the focused item, and the card never
 * exceeds its row/line limits.
 */
class ShopListStateTest {
    private fun sample(vararg labels: String) =
        labels.mapIndexed { i, l -> ShopItem(id = "id$i", label = l) }

    @Test
    fun `move wraps over the voice row plus items`() {
        val state = ShopListState(sample("Milk", "Eggs")) // rows: voice, Milk, Eggs
        assertEquals(0, state.focus)
        state.move(-1)
        assertEquals(2, state.focus) // wrapped to last item
        state.move(1)
        assertEquals(0, state.focus) // back to voice row
    }

    @Test
    fun `select on row 0 starts voice`() {
        val state = ShopListState(sample("Milk"))
        assertEquals(ShopListState.SelectResult.StartVoice, state.select())
    }

    @Test
    fun `select on an item toggles only that item`() {
        val state = ShopListState(sample("Milk", "Eggs"))
        state.move(2) // focus Eggs (row 2 -> item index 1)
        val result = state.select()
        assertTrue(result is ShopListState.SelectResult.Toggled)
        assertTrue((result as ShopListState.SelectResult.Toggled).item.done)
        assertEquals(1, state.items().count { it.done })
        assertTrue(state.items()[1].done)
        assertFalse(state.items()[0].done)
    }

    @Test
    fun `toggle is idempotent in pairs`() {
        val state = ShopListState(sample("Milk"))
        state.move(1)
        assertTrue((state.select() as ShopListState.SelectResult.Toggled).item.done)
        assertFalse((state.select() as ShopListState.SelectResult.Toggled).item.done)
    }

    @Test
    fun `empty list still exposes the voice row and select starts voice`() {
        val state = ShopListState()
        assertEquals(1, state.rowCount)
        state.move(1)
        assertEquals(0, state.focus) // wraps: only the voice row exists
        assertEquals(ShopListState.SelectResult.StartVoice, state.select())
        assertTrue(state.lines()[0].startsWith(">"))
    }

    @Test
    fun `setItems clamps focus into range`() {
        val state = ShopListState(sample("a", "b", "c", "d"))
        state.move(4) // focus row 4 (item d)
        state.setItems(sample("a", "b"))
        assertEquals(2, state.focus) // rows now: voice, a, b -> clamp to 2
    }

    @Test
    fun `focusItem moves the cursor onto the given item`() {
        val state = ShopListState(sample("Milk", "Eggs", "Bread"))
        state.focusItem("id2")
        assertEquals(3, state.focus)
        assertEquals("Bread", state.selectedItem()?.label)
    }

    @Test
    fun `voice row is always rendered first with a cursor when focused`() {
        val state = ShopListState(sample("Milk"))
        assertTrue(state.lines()[0].contains("Add item by voice"))
        assertTrue(state.lines()[0].startsWith(">"))
        assertEquals(1, state.lines().count { it.startsWith(">") })
    }

    @Test
    fun `contentKey changes on move and on toggle and stays within limit`() {
        val state = ShopListState(sample("Milk", "Eggs"))
        val initial = state.contentKey()
        assertTrue(initial.length <= 128)
        state.move(1)
        assertNotEquals(initial, state.contentKey())
        val afterMove = state.contentKey()
        state.select()
        assertNotEquals(afterMove, state.contentKey())
    }

    @Test
    fun `deep focus in a large list stays visible on a small page`() {
        val many = (0 until 200).map { ShopItem(id = "id$it", label = "item $it") }
        val state = ShopListState(many)
        state.move(150) // focus row 150 (item 149)
        val lines = state.lines()
        assertTrue("page must fit the card body budget", lines.size <= 15)
        // The focused row (the one carrying the cursor) must be in the rendered page.
        assertEquals(1, lines.count { it.startsWith(">") })
        assertTrue("focused item must be visible", lines.any { it.startsWith("> ") && it.contains("item 149") })
    }

    @Test
    fun `every focus position keeps the focused row on the page`() {
        val many = (0 until 130).map { ShopItem(id = "id$it", label = "item $it") }
        val state = ShopListState(many)
        for (step in 0 until many.size) {
            assertEquals(
                "focus $step must render its cursor row",
                1,
                state.lines().count { it.startsWith(">") },
            )
            state.move(1)
        }
    }
}
