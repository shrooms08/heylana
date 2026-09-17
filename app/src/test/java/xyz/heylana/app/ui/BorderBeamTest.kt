package xyz.heylana.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * border-beam has no golden vectors (its ports were checked by a pixel harness against
 * the web demo), so this is a phase test of the ported geometry: the border path, the
 * beam window from spec/beam-spec.json, and where the lit arc is at each moment of a lap.
 */
class BorderBeamTest {

    @Test
    fun `the beam window is the spec's rotate beamMaskStops`() {
        // spec/beam-spec.json, rotate.beamMaskStops
        val spec = listOf(0 to 0f, 30 to 0f, 36 to 0.1f, 44 to 0.35f, 52 to 1f, 80 to 1f, 86 to 0.35f, 92 to 0.1f, 95 to 0f, 100 to 0f)
        for ((pos, alpha) in spec) assertEquals("at $pos", alpha, BorderBeam.stopsAlpha(pos / 100f), 1e-5f)
        assertEquals(0.225f, BorderBeam.stopsAlpha(0.40f), 1e-4f)
        // Fully lit over 28% of a lap: about a quarter of the rim.
        assertEquals(0.28f, 0.80f - 0.52f, 1e-6f)
    }

    @Test
    fun `the border path runs clockwise from the top centre and sums to the perimeter`() {
        val hw = 170f
        val hh = 40f
        val r = 20f
        val ex = hw - r
        val ey = hh - r
        val arc = 0.5f * PI.toFloat() * r
        val perimeter = 4 * ex + 4 * ey + 4 * arc
        assertEquals(perimeter, BorderBeam.borderPathCoord(0f, -hh, hw, hh, r).perimeter, 1e-3f)
        assertEquals(0f, BorderBeam.borderPathCoord(0f, -hh, hw, hh, r).s, 1e-3f)      // top centre
        assertEquals(ex + arc + ey, BorderBeam.borderPathCoord(hw, 0f, hw, hh, r).s, 1e-3f) // right middle
        assertEquals(perimeter / 2, BorderBeam.borderPathCoord(0f, hh, hw, hh, r).s, 1e-3f) // bottom centre
        assertEquals(0f, BorderBeam.borderPathCoord(0f, -hh, hw, hh, r).d, 1e-3f)       // on the edge
        assertEquals(5f, BorderBeam.borderPathCoord(0f, -hh + 5f, hw, hh, r).d, 1e-3f)   // 5px inside
    }

    @Test
    fun `a circle is a rounded rect whose corners meet`() {
        val r = 40f
        val c = BorderBeam.borderPathCoord(r * 0.7071f, -r * 0.7071f, r, r, r)
        assertEquals((2 * PI * r).toFloat(), c.perimeter, 1e-3f)
        assertEquals((PI * r / 4).toFloat(), c.s, 1e-2f) // an eighth of the way round
    }

    @Test
    fun `the lit arc travels clockwise and a lap takes 1,6 seconds`() {
        assertEquals(1_600L, BorderBeam.LAP_MS)
        for (quarter in 0..7) {
            val phase = quarter / 8f
            // The middle of the lit arc sits 66% of the rim ahead of the phase; the dark
            // side 10% ahead. As the phase grows, both move clockwise with it.
            assertEquals(1f, BorderBeam.window(((0.66f + phase) % 1f) * 100f, 100f, phase), 1e-5f)
            assertEquals(0f, BorderBeam.window(((0.10f + phase) % 1f) * 100f, 100f, phase), 1e-5f)
        }
        // One lap later every point is exactly as it was.
        for (i in 0..20) {
            val f = i / 20f
            assertEquals(BorderBeam.window(f * 100f, 100f, 0.3f), BorderBeam.window(f * 100f, 100f, 1.3f), 1e-4f)
        }
    }

    @Test
    fun `the glow is brightest on the rim and gone a few widths in`() {
        val lit = 0.66f // mid-window at phase 0, along the top edge of a wide box
        val hw = 170f
        val hh = 40f
        val r = 20f
        val on = BorderBeam.intensity(0f, -hh, hw, hh, r, phase = -lit, glow = 6f)
        val deep = BorderBeam.intensity(0f, -hh + 18f, hw, hh, r, phase = -lit, glow = 6f)
        assertEquals(1f, on, 1e-3f)
        assertTrue(deep < 0.001f)
    }
}
