package xyz.heylana.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.ui.HeylanaTokens

class GlideTest {

    @Test
    fun `the glide is a soft spring that barely overshoots, softer than the flight to the top`() {
        assertEquals(0.85f, HeylanaTokens.GLIDE_DAMPING)
        assertTrue(HeylanaTokens.GLIDE_STIFFNESS < HeylanaTokens.SPRING_STIFFNESS)
        assertTrue(HeylanaTokens.GLIDE_STIFFNESS <= 200f) // SpringForce.STIFFNESS_LOW
        assertTrue(HeylanaTokens.GLIDE_DAMPING > HeylanaTokens.SPRING_DAMPING)
    }

    @Test
    fun `a fling carries into the glide, capped both ways`() {
        assertEquals(1200f, Glide.startVelocity(1200f, 3000f))
        assertEquals(3000f, Glide.startVelocity(9000f, 3000f))
        assertEquals(-3000f, Glide.startVelocity(-9000f, 3000f))
    }

    @Test
    fun `the drag speed follows the finger without jumping on one sample`() {
        var v = 0f
        v = Glide.smooth(v, 1000f)
        assertEquals(600f, v, 1e-3f)
        v = Glide.smooth(v, 1000f)
        assertEquals(840f, v, 1e-3f)
    }
}
