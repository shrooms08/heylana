package xyz.heylana.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachingFlightTest {

    private val point = FloatArray(2)

    @Test
    fun `a hop is teacher pace - longer for further, never a dash or a crawl`() {
        assertEquals(TeachingFlight.PACE_MS, TeachingFlight.durationMs(TeachingFlight.PACE_DP))
        assertTrue(TeachingFlight.durationMs(100f) < TeachingFlight.durationMs(400f))
        assertEquals(TeachingFlight.MIN_MS, TeachingFlight.durationMs(1f))
        assertEquals(TeachingFlight.MAX_MS, TeachingFlight.durationMs(5_000f))
    }

    @Test
    fun `the arc is a fifth of the distance, capped`() {
        assertEquals(40f, TeachingFlight.arcHeightDp(200f), 0.001f)
        assertEquals(TeachingFlight.ARC_MAX_DP, TeachingFlight.arcHeightDp(2_000f), 0.001f)
    }

    @Test
    fun `it leaves and lands exactly where it should, over the top`() {
        TeachingFlight.at(0f, 10f, 100f, 210f, 100f, 60f, point)
        assertEquals(10f, point[0], 0.01f)
        assertEquals(100f, point[1], 0.01f)
        TeachingFlight.at(1f, 10f, 100f, 210f, 100f, 60f, point)
        assertEquals(210f, point[0], 0.01f)
        assertEquals(100f, point[1], 0.01f)
        // Halfway it is over the straight line between the two, by half the arc height.
        TeachingFlight.at(0.5f, 10f, 100f, 210f, 100f, 60f, point)
        assertEquals(110f, point[0], 0.01f)
        assertEquals(70f, point[1], 0.01f)
    }

    @Test
    fun `it eases in and out, and never goes backwards`() {
        assertEquals(0f, TeachingFlight.smoothstep(0f), 0.0001f)
        assertEquals(0.5f, TeachingFlight.smoothstep(0.5f), 0.0001f)
        assertEquals(1f, TeachingFlight.smoothstep(1f), 0.0001f)
        var last = -1f
        for (step in 0..100) {
            TeachingFlight.at(step / 100f, 0f, 0f, 500f, 0f, 40f, point)
            assertTrue("x went backwards at $step", point[0] >= last)
            last = point[0]
        }
    }

    @Test
    fun `the disc swells at the top of the arc and lands its own size`() {
        assertEquals(1f, TeachingFlight.scaleAt(0f), 0.0001f)
        assertEquals(1f, TeachingFlight.scaleAt(1f), 0.0001f)
        assertEquals(1f + TeachingFlight.SWELL, TeachingFlight.scaleAt(0.5f), 0.0001f)
    }

    @Test
    fun `the strip never lands back on the element - the pair needs room, else it goes under`() {
        val out = IntArray(3)
        val disc = 100
        val gap = 20
        val strip = 500
        // Room for the disc on the right but not the disc and its strip: not beside on the right.
        TeachingFlight.standBeside(100, 300, 500, 400, disc, gap, 1080, 2400, out, stripWidth = strip)
        assertTrue("disc at ${out[0]}", out[0] + disc <= 100 || out[1] >= 400 || out[1] + disc <= 300)
        // Plenty of room on the left: beside it on the left, strip further left.
        TeachingFlight.standBeside(700, 300, 1000, 400, disc, gap, 1080, 2400, out, stripWidth = strip)
        assertEquals(580, out[0])
        assertEquals(1, out[2])
        assertTrue(out[0] - strip >= 0)
        // Under a wide element: disc and strip both on screen.
        TeachingFlight.standBeside(0, 300, 1080, 400, disc, gap, 1080, 2400, out, stripWidth = strip)
        assertEquals(420, out[1])
        if (out[2] == 1) assertTrue(out[0] - strip >= 0) else assertTrue(out[0] + disc + strip <= 1080)
    }

    @Test
    fun `it stands beside the element, on the side with room, else under or over it`() {
        val out = IntArray(2)
        val disc = 100
        val gap = 20
        // Room on the right: beside it, level with its middle.
        TeachingFlight.standBeside(100, 300, 300, 400, disc, gap, 1080, 2400, out)
        assertEquals(320, out[0])
        assertEquals(300, out[1])
        // Hard against the right edge: beside it on the left.
        TeachingFlight.standBeside(800, 300, 1060, 400, disc, gap, 1080, 2400, out)
        assertEquals(680, out[0])
        // A full-width element: under it, centred.
        TeachingFlight.standBeside(0, 300, 1080, 400, disc, gap, 1080, 2400, out)
        assertEquals(490, out[0])
        assertEquals(420, out[1])
        // Full width at the very bottom: over it instead.
        TeachingFlight.standBeside(0, 2280, 1080, 2400, disc, gap, 1080, 2400, out)
        assertEquals(2160, out[1])
        // Always on screen.
        TeachingFlight.standBeside(-50, -80, 1200, 40, disc, gap, 1080, 2400, out)
        assertTrue(out[0] >= 0 && out[0] + disc <= 1080)
        assertTrue(out[1] >= 0 && out[1] + disc <= 2400)
    }
}

class PlaceWindowTest {

    @Test
    fun `the whole window stays off the element - the Seeker's Swap step`() {
        val out = IntArray(3)
        // The Swap label, the window as measured (disc and strip), the Seeker's usable screen.
        TeachingFlight.placeWindow(169, 1308, 258, 1359, 910, 617, 42, 1200, 2531, out)
        val (x, y) = out[0] to out[1]
        val clear = x + 910 <= 169 || x >= 258 || y + 617 <= 1308 || y >= 1359
        assertTrue("window at $x,$y overlaps the element", clear)
        assertTrue(x >= 0 && y >= 0 && x + 910 <= 1200 && y + 617 <= 2531)
    }

    @Test
    fun `beside when the window fits, strip on the far side`() {
        val out = IntArray(3)
        // More room on the right: beside it on the right, strip further right.
        TeachingFlight.placeWindow(200, 1000, 350, 1100, 700, 300, 20, 2000, 2400, out)
        assertEquals(370, out[0])
        assertEquals(0, out[2])
        TeachingFlight.placeWindow(1300, 1000, 1400, 1100, 700, 300, 20, 2000, 2400, out)
        assertEquals(580, out[0])
        assertEquals(1, out[2])
    }
}
