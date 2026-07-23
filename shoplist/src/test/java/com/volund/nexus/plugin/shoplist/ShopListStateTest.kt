package com.volund.nexus.plugin.shoplist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the whole surface is operable on the R08 ring's single axis:
 * move (NEXT/PREV) wraps, select (toggle) affects only the focused row, and the
 * surface never violates the card contract (line count / stable contentKey).
 */
class ShopListStateTest {
    private fun sample(vararg labels: String) =
        labels.mapIndexed { i, l -> ShopItem(id = "id$i", label = l) }

    @Test
    fun `move wraps once in either direction`() {
        val state = ShopListState(sample("Milk", "Eggs", "Bread"))
        state.move(-1)
        assertEquals(2, state.selectedIndex)
        state.move(1)
        assertEquals(0, state.selectedIndex)
    }

    @Test
    fun `move and toggle are no-ops on an empty list`() {
        val state = ShopListState()
        state.move(1)
        assertEquals(0, state.selectedIndex)
        assertNull(state.toggle())
        assertEquals(listOf("  (empty - add items on your phone)"), state.lines())
    }

    @Test
    fun `toggle checks only the focused row`() {
        val state = ShopListState(sample("Milk", "Eggs", "Bread"))
        state.move(1)
        val toggled = state.toggle()
        assertEquals("Eggs", toggled?.label)
        assertTrue(toggled?.done == true)
        assertEquals(1, state.items().count { it.done })
        assertTrue(state.items()[1].done)
        assertFalse(state.items()[0].done)
    }

    @Test
    fun `toggle is idempotent in pairs`() {
        val state = ShopListState(sample("Milk"))
        assertTrue(state.toggle()?.done == true)
        assertFalse(state.toggle()?.done == true)
    }

    @Test
    fun `setItems clamps selection into range`() {
        val state = ShopListState(sample("a", "b", "c", "d"))
        state.move(3) // index 3
        state.setItems(sample("a", "b"))
        assertEquals(1, state.selectedIndex)
    }

    @Test
    fun `only the focused row is marked with a cursor`() {
        val state = ShopListState(sample("Milk", "Eggs"))
        state.move(1)
        assertEquals(1, state.lines().count { it.startsWith(">") })
        assertTrue(state.lines()[1].startsWith("> "))
    }

    @Test
    fun `contentKey changes on move and on toggle and stays within limit`() {
        val state = ShopListState(sample("Milk", "Eggs", "Bread"))
        val initial = state.contentKey()
        assertTrue(initial.length <= 128)
        state.move(1)
        val afterMove = state.contentKey()
        assertNotEquals(initial, afterMove)
        state.toggle()
        assertNotEquals(afterMove, state.contentKey())
    }

    @Test
    fun `large list never exceeds the card row ceiling`() {
        val many = (0 until 200).map { ShopItem(id = "id$it", label = "item $it") }
        val state = ShopListState(many)
        state.move(150)
        assertTrue(state.lines().size <= 64)
    }
}
