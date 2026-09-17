package xyz.heylana.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The goo against liquid-gooey's filter.tsx: blur 6, contrast 18, intercept
 * `round((0.5 - contrast × 5/12) × 100) / 100`, and the geometry of each merge.
 */
class GooeySpecTest {

    @Test
    fun `blur 6 and contrast 18 with the library's intercept`() {
        assertEquals(6f, GooeySpec.BLUR_DP)
        assertEquals(18f, GooeySpec.CONTRAST)
        assertEquals(-7f, GooeySpec.intercept(18f), 1e-6f) // "the classic 18/-7 goo pairing"
        assertEquals(-3.67f, GooeySpec.intercept(10f), 1e-6f)
    }

    @Test
    fun `the alpha contrast turns a soft blur back into a hard edge`() {
        assertEquals(0f, GooeySpec.contrastAlpha(0.3f), 1e-6f)
        assertEquals(0f, GooeySpec.contrastAlpha(7f / 18f), 1e-5f)
        assertEquals(1f, GooeySpec.contrastAlpha(0.5f), 1e-6f)
        // The edge sits where the blurred alpha is about 0.39 to 0.44: a band a few percent wide.
        assertTrue(GooeySpec.contrastAlpha(0.41f) in 0.1f..0.9f)
    }

    @Test
    fun `the box starts as a seed overlapping the disc, and ends as the box`() {
        // An 80dp disc (210px) centred at (540, 300); the box below it.
        val out = FloatArray(5)
        GooeySpec.growFromDisc(540f, 300f, 210f, 60f, 440f, 1020f, 700f, 52f, 0f, out)
        val seedTop = out[1]
        val discBottom = 300f + 105f
        assertTrue("the seed starts inside the disc, so the two are one blob", seedTop < discBottom)
        assertEquals(out[2] - out[0], 147f, 0.5f)          // 70% of the disc
        assertEquals((out[2] - out[0]) / 2f, out[4], 1e-4f) // a circle
        GooeySpec.growFromDisc(540f, 300f, 210f, 60f, 440f, 1020f, 700f, 52f, 1f, out)
        assertEquals(listOf(60f, 440f, 1020f, 700f, 52f), out.toList())
        // Separated at the end: the box's top is below the disc's bottom.
        assertTrue(out[1] > discBottom)
    }
}
