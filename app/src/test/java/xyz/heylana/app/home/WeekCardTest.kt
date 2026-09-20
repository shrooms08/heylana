package xyz.heylana.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.wallet.WeekCatch
import xyz.heylana.app.wallet.WeekItem

class WeekCardTest {

    private val caught = WeekCatch(
        weekStart = "2026-09-14",
        memoryOn = true,
        line = "This week: 14 screens explained, 2 sends checked, 1 stopped before signing.",
        items = listOf(
            WeekItem("screens", "screens explained", 14),
            WeekItem("sends_prepared", "sends checked", 2),
            WeekItem("sends_stopped", "stopped before signing", 1),
        )
    )

    @Test
    fun `a week with nothing in it is not a card`() {
        assertTrue(caught.anything)
        assertFalse(caught.copy(items = emptyList()).anything)
    }

    @Test
    fun `the card carries counts and their words, and nothing that could be private`() {
        val everything = caught.line + caught.items.joinToString(" ") { "${it.count} ${it.label} ${it.key}" }
        // Numbers, the category words, and punctuation: an address, an amount or a token name
        // could never fit through, because nothing but these ever reaches the card.
        assertTrue(everything, Regex("^[0-9A-Za-z ,.:_-]+$").matches(everything))
        for (private in listOf("7c2y", "0.05", "USDC", "SOL", "wallet", "0x")) {
            assertFalse(private, everything.contains(private))
        }
    }

    @Test
    fun `the picture is as tall as it has lines, and draws from the items alone`() {
        val short = WeekImage.height(1)
        val long = WeekImage.height(7)
        assertTrue(long > short)
        assertEquals(1080, WeekImage.WIDTH)
    }
}
