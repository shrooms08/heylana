package xyz.heylana.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The port's bands against design/refs/liquid_glass.glsl, written out as the
 * reference writes them: for any value of the reference's `roundedBox`, feeding
 * `roundedBox * 10000` to the port gives the same rb1, rb2, rb3 and lens pull.
 */
class LiquidGlassMathTest {

    // The reference, verbatim in shape: constants and all.
    private fun refRb1(rb: Double) = ((1.0 - rb * 10000.0) * 8.0).coerceIn(0.0, 1.0)
    private fun refRb2(rb: Double) = ((0.95 - rb * 9500.0) * 16.0).coerceIn(0.0, 1.0) -
        ((0.9 - rb * 9500.0).pow(1.0) * 16.0).coerceIn(0.0, 1.0)
    private fun refRb3(rb: Double) = ((1.5 - rb * 11000.0) * 2.0).coerceIn(0.0, 1.0) -
        ((1.0 - rb * 11000.0).pow(1.0) * 2.0).coerceIn(0.0, 1.0)
    private fun refLens(rb: Double) = 1.0 - rb * 5000.0

    @Test
    fun `bands and lens match the reference shader across the face`() {
        var roundedBox = 0.0
        while (roundedBox <= 0.000_12) {
            val ours = (roundedBox * 10000.0).toFloat()
            assertEquals("rb1 at $roundedBox", refRb1(roundedBox), LiquidGlassMath.rb1(ours).toDouble(), 1e-4)
            assertEquals("rb2 at $roundedBox", refRb2(roundedBox), LiquidGlassMath.rb2(ours).toDouble(), 1e-4)
            assertEquals("rb3 at $roundedBox", refRb3(roundedBox), LiquidGlassMath.rb3(ours).toDouble(), 1e-4)
            if (ours <= 1f) {
                assertEquals("lens at $roundedBox", refLens(roundedBox), LiquidGlassMath.lensFactor(ours).toDouble(), 1e-4)
            }
            roundedBox += 0.000_001
        }
    }

    @Test
    fun `the mask input is 1 on the edge, 0 at the centre of a disc, and falls inward over the rim`() {
        val r = 100f
        assertEquals(0f, LiquidGlassMath.rb(0f, 0f, r, r, r, r), 1e-4f)
        assertEquals(1f, LiquidGlassMath.rb(r, 0f, r, r, r, r), 1e-4f)
        assertEquals(1f, LiquidGlassMath.rb(r * 0.7071f, r * 0.7071f, r, r, r, r), 1e-3f)
        assertEquals(0.5f, LiquidGlassMath.rb(50f, 0f, r, r, r, r), 1e-4f)
        // A wide panel: 1 at the top edge, 0 one rim-width in, whatever the width.
        assertEquals(1f, LiquidGlassMath.rb(0f, -60f, 300f, 60f, 28f, 24f), 1e-4f)
        assertEquals(0f, LiquidGlassMath.rb(0f, -36f, 300f, 60f, 28f, 24f), 1e-4f)
    }

    @Test
    fun `the rim highlight band sits just inside the edge`() {
        assertTrue(LiquidGlassMath.rb2(0.97f) > 0.2f)
        assertEquals(0f, LiquidGlassMath.rb2(0.5f), 1e-4f)
        assertEquals(1f, LiquidGlassMath.rb1(0f), 1e-4f)
    }
}
