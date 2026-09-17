package xyz.heylana.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The clear glass maths against the reference: design/refs/liquid_glass_render.py for
 * what the renderer computes (distance field, smoothing, the light terms), and the
 * design-2d brief for the tuned band, pull and lighting formulas, each written out here
 * the way the source writes it.
 */
class GlassSpecTest {

    // ------------------------------------------------ the renderer, verbatim in shape

    private fun rendererSdRbox(px: Double, py: Double, cx: Double, cy: Double, hw: Double, hh: Double, r: Double): Double {
        val qx = kotlin.math.abs(px - cx) - hw + r
        val qy = kotlin.math.abs(py - cy) - hh + r
        return hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - r
    }

    private fun rendererSmooth(x: Double, a: Double, b: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    // light=(-0.45, -0.89); ndotl = clip(nx*(-light[0]) + ny*(-light[1])); ndotb = clip(nx*light[0] + ny*light[1])
    private fun rendererNdotl(nx: Double, ny: Double) = (nx * 0.45 + ny * 0.89).coerceIn(0.0, 1.0)
    private fun rendererNdotb(nx: Double, ny: Double) = (nx * -0.45 + ny * -0.89).coerceIn(0.0, 1.0)

    @Test
    fun `distance field, smoothing and light terms are the renderer's`() {
        for (px in -120..120 step 7) for (py in -60..60 step 5) {
            val want = rendererSdRbox(px.toDouble(), py.toDouble(), 0.0, 0.0, 100.0, 40.0, 20.0)
            assertEquals(want, GlassSpec.sdRoundBox(px.toFloat(), py.toFloat(), 100f, 40f, 20f).toDouble(), 1e-3)
        }
        for (x in 0..20) {
            val v = x / 20.0
            assertEquals(rendererSmooth(v, 0.30, 0.80), GlassSpec.smoothstep(0.30f, 0.80f, v.toFloat()).toDouble(), 1e-5)
        }
        for (deg in 0 until 360 step 15) {
            val a = Math.toRadians(deg.toDouble())
            val nx = kotlin.math.cos(a)
            val ny = kotlin.math.sin(a)
            assertEquals(rendererNdotl(nx, ny), GlassSpec.ndotl(nx.toFloat(), ny.toFloat()).toDouble(), 1e-5)
            assertEquals(rendererNdotb(nx, ny), GlassSpec.ndotb(nx.toFloat(), ny.toFloat()).toDouble(), 1e-5)
        }
    }

    @Test
    fun `the light direction is a unit vector toward the top-left`() {
        assertEquals(1.0, sqrt((GlassSpec.LIGHT_X * GlassSpec.LIGHT_X + GlassSpec.LIGHT_Y * GlassSpec.LIGHT_Y).toDouble()), 0.01)
        assertTrue(GlassSpec.LIGHT_X < 0 && GlassSpec.LIGHT_Y < 0)
    }

    // ------------------------------------------------------------ the brief's formulas

    private val e = 30f

    @Test
    fun `band is 1 minus smoothstep(0,30, 0,80, -d over E)`() {
        for (i in 0..40) {
            val d = -i * e / 40f
            val t2 = -d / e
            val want = 1f - GlassSpec.smoothstep(0.30f, 0.80f, t2)
            assertEquals(want, GlassSpec.band(d, e), 1e-6f)
        }
        assertEquals(1f, GlassSpec.band(0f, e), 1e-6f)
        assertEquals(1f, GlassSpec.band(-0.3f * e, e), 1e-6f)
        assertEquals(0f, GlassSpec.band(-0.8f * e, e), 1e-6f)
    }

    @Test
    fun `pull is strength times band times (0,45 + 0,55 clamp(1 - t2 over 0,80))`() {
        assertEquals(34f, GlassSpec.pull(0f, e, 34f), 1e-4f)
        val d = -0.4f * e
        val want = 34f * GlassSpec.band(d, e) * (0.45f + 0.55f * (1f - 0.4f / 0.80f))
        assertEquals(want, GlassSpec.pull(d, e, 34f), 1e-4f)
        assertEquals(0f, GlassSpec.pull(-e, e, 34f), 1e-4f)
    }

    @Test
    fun `gradient runs from +0,07 at the top to -0,04 at the bottom, by depth`() {
        assertEquals(0.07f, GlassSpec.gradient(0f, -e, e), 1e-6f)
        assertEquals(-0.04f, GlassSpec.gradient(1f, -2 * e, e), 1e-6f)
        assertEquals(0.015f, GlassSpec.gradient(0.5f, -e, e), 1e-6f)
        assertEquals(0f, GlassSpec.gradient(0f, 0f, e), 1e-6f)
        assertEquals(0.035f, GlassSpec.gradient(0f, -e / 2, e), 1e-6f)
    }

    @Test
    fun `rim is 7,5 percent of E, adds 0,10 + 0,32 ndotl and takes 0,10 ndotb`() {
        val lit = GlassSpec.rim(0f, e, 0.45f, 0.89f) // facing -LIGHT: ndotl 1, ndotb 0
        assertEquals(0.10f + 0.32f * GlassSpec.ndotl(0.45f, 0.89f), lit, 1e-4f)
        val far = GlassSpec.rim(0f, e, -0.45f, -0.89f) // facing LIGHT: ndotl 0, ndotb 1
        assertEquals(0.10f - 0.10f * GlassSpec.ndotb(-0.45f, -0.89f), far, 1e-4f)
        assertEquals(0f, GlassSpec.rim(-0.075f * e, e, 0.45f, 0.89f), 1e-6f)
        assertEquals(0f, GlassSpec.rim(1f, e, 0.45f, 0.89f), 1e-6f)
    }

    @Test
    fun `lens line sits at 10 percent of E, 4,5 percent wide, at -0,03`() {
        assertEquals(-0.03f, GlassSpec.lensLine(-0.10f * e, e), 1e-6f)
        val oneWidth = GlassSpec.lensLine(-(0.10f + 0.045f) * e, e)
        assertEquals((-0.03 * exp(-1.0)).toFloat(), oneWidth, 1e-6f)
    }

    @Test
    fun `bottom shade is -0,06 ndotb over 0,9 E, and the hairline 0,30 (0,3 + 0,7 ndotl)`() {
        assertEquals(-0.06f * GlassSpec.ndotb(-0.45f, -0.89f), GlassSpec.bottomShade(0f, e, -0.45f, -0.89f), 1e-5f)
        assertEquals(0f, GlassSpec.bottomShade(-0.9f * e, e, -0.45f, -0.89f), 1e-6f)
        assertEquals(0.30f * (0.3f + 0.7f * GlassSpec.ndotl(0.45f, 0.89f)), GlassSpec.hairline(0f, 0.45f, 0.89f), 1e-5f)
        assertEquals(0.09f, GlassSpec.hairline(0f, -0.45f, -0.89f), 1e-4f)
        assertEquals(0f, GlassSpec.hairline(-1f, 0.45f, 0.89f), 1e-6f)
    }

    @Test
    fun `the rim reads brightest toward the bottom-right, as the reference renders do`() {
        val bottomRight = GlassSpec.lighting(-0.5f, e, 0.7071f, 0.7071f, 1f)
        val topLeft = GlassSpec.lighting(-0.5f, e, -0.7071f, -0.7071f, 0f)
        assertTrue("bottom-right $bottomRight top-left $topLeft", bottomRight > topLeft)
    }

    @Test
    fun `the numbers are the brief's`() {
        assertEquals(GlassSpec.Surface(20f, 30f, 34f, 0.985f), GlassSpec.PANEL)
        assertEquals(GlassSpec.Surface(40f, 20f, 30f, 0.96f), GlassSpec.DISC_OPEN)
        assertEquals(GlassSpec.Surface(32f, 16f, 24f, 0.96f), GlassSpec.DISC_DOCKED)
        assertEquals(GlassSpec.DISC_DOCKED, GlassSpec.disc(64f))
        assertEquals(GlassSpec.DISC_OPEN, GlassSpec.disc(80f))
        assertEquals(0.04f, GlassSpec.FILL_WHITE)
        assertEquals(1.18f, GlassSpec.SATURATION)
        assertEquals(8f, GlassSpec.BLUR_BEHIND_DP)
        assertEquals(0.22f, GlassSpec.SHADOW_ALPHA)
        assertTrue(!GlassSpec.TINTED_EXTRAS)
    }
}
