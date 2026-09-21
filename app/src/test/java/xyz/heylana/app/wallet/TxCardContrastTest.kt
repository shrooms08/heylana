package xyz.heylana.app.wallet

import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.ui.HeylanaTokens
import kotlin.math.pow

/**
 * Every word on a transaction card, held to WCAG AA (4.5:1) against the worst page behind it.
 *
 * On the Seeker's home screen the old clear-glass card let white icons and their labels show
 * through the amounts. The card is now a near-opaque blue-black; the worst case for it is a
 * pure white page, which leaks through the 5% it lets by. These are the tokens the views use
 * (their hex constants), composited the way the screen does it.
 */
class TxCardContrastTest {

    private data class Rgb(val r: Double, val g: Double, val b: Double) {
        fun over(below: Rgb, alpha: Double) = Rgb(
            alpha * r + (1 - alpha) * below.r,
            alpha * g + (1 - alpha) * below.g,
            alpha * b + (1 - alpha) * below.b
        )

        val luminance: Double
            get() {
                fun lin(c: Double): Double { val s = c / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
                return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
            }
    }

    private fun hex(h: String): Rgb {
        val v = h.removePrefix("#").toLong(16)
        return Rgb(((v shr 16) and 0xFF).toDouble(), ((v shr 8) and 0xFF).toDouble(), (v and 0xFF).toDouble())
    }

    private fun contrast(a: Rgb, b: Rgb): Double {
        val (hi, lo) = listOf(a.luminance, b.luminance).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private val white = Rgb(255.0, 255.0, 255.0)

    /** The card as it looks over a pure white page. */
    private val card = hex(HeylanaTokens.TX_CARD_BASE_HEX).over(white, HeylanaTokens.TX_CARD_FILL_ALPHA.toDouble())

    private fun assertReadable(name: String, words: Rgb, on: Rgb) {
        val ratio = contrast(words, on)
        println("tx card contrast over white: $name ${"%.2f".format(ratio)}:1")
        assertTrue("$name is ${"%.2f".format(ratio)}:1 over a white page, under 4.5:1", ratio >= 4.5)
    }

    @Test
    fun `the card is near-opaque`() {
        assertTrue(HeylanaTokens.TX_CARD_FILL_ALPHA in 0.94f..0.96f)
    }

    @Test
    fun `every word on the card reads over a white page`() {
        assertReadable("title, amount, values, line", hex(HeylanaTokens.TEXT_PRIMARY_HEX), card)
        assertReadable("warning, details", hex(HeylanaTokens.TEXT_2_HEX), card)
        assertReadable("row labels", hex(HeylanaTokens.TEXT_SECONDARY_HEX), card)
        assertReadable("Simulation passed", hex(HeylanaTokens.SUCCESS_HEX), card)
    }

    @Test
    fun `the badges read on their own pills`() {
        val devnetPill = hex(HeylanaTokens.WARN_HEX).over(card, HeylanaTokens.WARN_SOFT_ALPHA.toDouble())
        assertReadable("Devnet badge", hex(HeylanaTokens.WARN_HEX), devnetPill)
        val mainnetPill = white.over(card, HeylanaTokens.TX_BADGE_NEUTRAL_WHITE.toDouble())
        assertReadable("Mainnet badge", hex(HeylanaTokens.TEXT_2_HEX), mainnetPill)
    }
}
