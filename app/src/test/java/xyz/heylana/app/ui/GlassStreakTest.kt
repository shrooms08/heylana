package xyz.heylana.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The purple streak against design/refs/liquid_glass_motion_render.py (the GIF), whose
 * lines are, per frame at phase ph:
 *
 *     ang = radians(122 - 90)
 *     u = (x - left) * cos(ang) + (y - top) * sin(ang)
 *     width = 30 + 8 * sin(ph * 2π)
 *     streak = exp(-((u - centre) / width) ** 2)
 *     streak2 = exp(-((u - centre + 90) / 55) ** 2) * 0.35
 *     bg + (streak + streak2) * purple * 0.75
 */
class GlassStreakTest {

    private val ang = Math.toRadians(122.0 - 90.0)

    @Test
    fun `direction, profile, trail and breathing are the renderer's`() {
        for (x in 0..300 step 25) for (y in 0..150 step 25) {
            val want = (x * cos(ang) + y * sin(ang)).toFloat()
            assertEquals(want, GlassSpec.streakU(x.toFloat(), y.toFloat()), 1e-3f)
        }
        for (i in 0..10) {
            val ph = i / 10f
            assertEquals((30 + 8 * sin(ph * 2 * Math.PI)).toFloat(), GlassSpec.streakWidth(ph, 30f, 8f), 1e-4f)
        }
        val centre = 120f
        for (u in -100..400 step 10) {
            val w = 34.0
            val want = exp(-((u - centre) / w).let { it * it }) + 0.35 * exp(-((u - centre + 90) / 55.0).let { it * it })
            assertEquals(want.toFloat(), GlassSpec.streak(u.toFloat(), centre, w.toFloat(), 90f, 55f), 1e-5f)
        }
    }

    @Test
    fun `colour is the accent from the tokens mixed at 75 percent, and the shader's constants agree`() {
        assertEquals(0.75f, GlassSpec.STREAK_MIX)
        val agsl = GlassSpec.AGSL
        assertTrue(agsl.contains("float u = local.x * 0.848048 + local.y * 0.529919;"))
        assertEquals(0.848048f, GlassSpec.STREAK_COS, 1e-6f)
        assertEquals(0.529919f, GlassSpec.STREAK_SIN, 1e-6f)
        // No colour written into the shader: the streak's comes in as a uniform, set from the accent.
        assertTrue(agsl.contains("uniform half3 streakColour;"))
        assertTrue(agsl.contains("col.rgb += streakColour * k;"))
        assertTrue(!agsl.contains("0.560784"))
        assertTrue(agsl.contains("streakStrength * 0.75 * (exp(-a * a) + 0.35 * exp(-b * b))"))
    }

    @Test
    fun `a pass starts fully off the top-left and ends with the trail fully off the bottom-right`() {
        val extent = GlassSpec.streakExtent(342f, 150f)
        val w = 38f
        val start = GlassSpec.streakCentre(0f, extent, w, 90f, 55f)
        assertTrue(GlassSpec.streak(0f, start, w, 90f, 55f) < 0.02f)
        val end = GlassSpec.streakCentre(1f, extent, w, 90f, 55f)
        assertTrue(GlassSpec.streak(extent, end, w, 90f, 55f) < 0.02f)
        assertEquals(6_000L, GlassSpec.STREAK_PASS_MS)
        assertEquals(4_000L, GlassSpec.STREAK_THINKING_PASS_MS)
        assertEquals(0.95f, GlassSpec.STREAK_THINKING_STRENGTH)
    }

    @Test
    fun `the streak never covers more than a third of a pane at once`() {
        for ((w, h) in listOf(342f to 150f, 342f to 70f, 264f to 120f, 80f to 80f, 64f to 64f)) {
            val extent = GlassSpec.streakExtent(w, h)
            val fit = GlassSpec.streakFit(extent, 30f, 8f)
            // Visible over about ±2 widths at its widest breath.
            assertTrue("$w x $h covers ${4 * 38f * fit} of $extent", 4 * 38f * fit <= extent / 3f + 1e-3f)
        }
        assertEquals(1f, GlassSpec.streakFit(2000f, 30f, 8f), 1e-6f)
    }
}
