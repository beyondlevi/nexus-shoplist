package com.volund.nexus.plugin.shoplist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proves multi-line paste is split into exactly one item per non-blank line. */
class ShopListParserTest {
    @Test
    fun `splits on newlines into one label per line`() {
        assertEquals(
            listOf("Milk", "Eggs", "Bread"),
            ShopListParser.parseLines("Milk\nEggs\nBread"),
        )
    }

    @Test
    fun `handles carriage returns and trims whitespace`() {
        assertEquals(
            listOf("Milk", "Eggs"),
            ShopListParser.parseLines("  Milk \r\n\tEggs\r\n"),
        )
    }

    @Test
    fun `drops blank and whitespace-only lines`() {
        assertEquals(
            listOf("Milk", "Bread"),
            ShopListParser.parseLines("Milk\n\n   \nBread\n"),
        )
    }

    @Test
    fun `empty or blank input yields no items`() {
        assertTrue(ShopListParser.parseLines("").isEmpty())
        assertTrue(ShopListParser.parseLines("\n  \n\r\n").isEmpty())
    }

    @Test
    fun `caps each label at the line limit`() {
        val long = "x".repeat(500)
        val parsed = ShopListParser.parseLines("$long\nEggs", maxLabelChars = 120)
        assertEquals(120, parsed[0].length)
        assertEquals("Eggs", parsed[1])
    }
}
