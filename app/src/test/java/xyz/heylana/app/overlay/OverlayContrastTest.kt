package xyz.heylana.app.overlay

import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.ui.GlassSpec
import xyz.heylana.app.ui.HeylanaTokens
import kotlin.math.pow

/**
 * Every word Heylana draws over another app, held to WCAG AA against the worst page behind it.
 *
 * On the Seeker's home screen the clear glass let white icons and their labels through the
 * words, and typed text vanished over the dApp Store icon. Every overlay surface with words is
 * now a near-opaque blue-black; the worst case for it is a pure white page, which leaks through
 * the 5% it lets by. These are the tokens the views use (their constants), composited the way
 * the screen does it — including words that are themselves translucent (white at 72%).
 *
 * The surfaces: the pane in all its shapes (the ask box, the reply strip, the task HUD, teaching
 * steps, lookout and signing warnings, seed-phrase and look-alike warnings, notices, send cards),
 * its question field, its pills (ask, confirm, next, done, cancel, source chips, the step chip),
 * the mode chip, the network badges, and the listening capsule (listening, and thinking on the
 * aurora). The disc is not here: it carries no words.
 */
class OverlayContrastTest {

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

    private fun argb(color: Int): Rgb = Rgb(((color shr 16) and 0xFF).toDouble(), ((color shr 8) and 0xFF).toDouble(), (color and 0xFF).toDouble())

    private fun contrast(a: Rgb, b: Rgb): Double {
        val (hi, lo) = listOf(a.luminance, b.luminance).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private val white = Rgb(255.0, 255.0, 255.0)
    private val black = Rgb(0.0, 0.0, 0.0)

    /** Every overlay surface with words, over a pure white page. */
    private val pane = hex(HeylanaTokens.TX_CARD_BASE_HEX).over(white, HeylanaTokens.TX_CARD_FILL_ALPHA.toDouble())
    private val field = hex(HeylanaTokens.INPUT_FILL_SOLID_HEX)
    private val primary = hex(HeylanaTokens.TEXT_PRIMARY_HEX)

    /** White at 72%: secondary words, placeholders, unselected chips' words. Drawn over [on]. */
    private fun secondaryOn(on: Rgb) = white.over(on, HeylanaTokens.SECONDARY_WHITE.toDouble())

    private fun assertAtLeast(minimum: Double, name: String, words: Rgb, on: Rgb) {
        val ratio = contrast(words, on)
        println("overlay contrast over white: $name ${"%.2f".format(ratio)}:1")
        assertTrue("$name is ${"%.2f".format(ratio)}:1 over a white page, under $minimum:1", ratio >= minimum)
    }

    private fun assertReadable(name: String, words: Rgb, on: Rgb) = assertAtLeast(4.5, name, words, on)

    @Test
    fun `every surface with words is near-opaque`() {
        assertTrue(HeylanaTokens.TX_CARD_FILL_ALPHA in 0.94f..0.96f)
    }

    @Test
    fun `the pane - ask box, reply strip, task HUD, teaching steps, warnings, send cards`() {
        assertReadable("pane: answers, titles, typed words' echo", primary, pane)
        assertReadable("pane: details, warning lines", hex(HeylanaTokens.TEXT_2_HEX), pane)
        assertReadable("pane: row labels", hex(HeylanaTokens.TEXT_SECONDARY_HEX), pane)
        assertReadable("pane: secondary words, notes, status", secondaryOn(pane), pane)
        assertReadable("pane: Simulation passed", hex(HeylanaTokens.SUCCESS_HEX), pane)
    }

    @Test
    fun `the question field - typed words at 15 to 1, the placeholder and the caret`() {
        assertAtLeast(15.0, "field: typed words", primary, field)
        assertReadable("field: placeholder", secondaryOn(field), field)
        // A caret is a control, not text: 3:1 is its bar.
        assertAtLeast(3.0, "field: caret", hex(HeylanaTokens.ACCENT_HEX), field)
    }

    @Test
    fun `the pills on the pane - ask, confirm, next, done, cancel, source and step chips`() {
        val unselected = black.over(pane, GlassSpec.CHIP_UNSELECTED_BLACK.toDouble())
        assertReadable("pill: cancel, done, source chips, step chip", secondaryOn(unselected), unselected)
        assertReadable("pill: next", primary, unselected)
        val selected = white.over(pane, GlassSpec.CHIP_SELECTED_WHITE.toDouble())
        assertReadable("pill: ask, confirm", argb(GlassSpec.CHIP_SELECTED_TEXT), selected)
    }

    @Test
    fun `the mode chip and the network badges`() {
        val chip = white.over(pane, HeylanaTokens.INPUT_FILL_WHITE.toDouble())
        assertReadable("mode chip", primary, chip)
        val devnet = hex(HeylanaTokens.WARN_HEX).over(pane, HeylanaTokens.WARN_SOFT_ALPHA.toDouble())
        assertReadable("Devnet badge", hex(HeylanaTokens.WARN_HEX), devnet)
        val mainnet = white.over(pane, HeylanaTokens.TX_BADGE_NEUTRAL_WHITE.toDouble())
        assertReadable("Mainnet badge", hex(HeylanaTokens.TEXT_2_HEX), mainnet)
    }

    @Test
    fun `the listening capsule - the words heard, and thinking on the aurora`() {
        assertReadable("capsule: words heard", primary, pane)
        // The aurora is opaque, so the page behind does not matter; every stop is checked.
        for ((name, stop) in listOf(
            "accent" to HeylanaTokens.ACCENT_HEX,
            "accent hover" to HeylanaTokens.ACCENT_HOVER_HEX,
            "accent text" to HeylanaTokens.ACCENT_TEXT_HEX
        )) {
            assertReadable("capsule: thinking on $name", hex(HeylanaTokens.ON_ACCENT_HEX), hex(stop))
        }
    }
}
