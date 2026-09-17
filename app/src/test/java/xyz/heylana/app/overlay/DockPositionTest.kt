package xyz.heylana.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class DockPositionTest {

    // The Seeker: 1200px usable, a 64dp disc's view is 237px, the inset to the visible disc is -23px.
    private val width = 1200
    private val view = 237
    private val inset = -23

    @Test
    fun `the dock target is where the window really lands, on both sides`() {
        // Measured before the fix: aimed at 986 and -23, landed at 963 and 0.
        assertEquals(963, DockPosition.dockLeft(onLeft = false, inset = inset, viewSize = view, usableWidth = width))
        assertEquals(0, DockPosition.dockLeft(onLeft = true, inset = inset, viewSize = view, usableWidth = width))
    }

    @Test
    fun `a position already on screen is left alone, and clamping twice changes nothing`() {
        assertEquals(500, DockPosition.clamped(500, view, width))
        val once = DockPosition.clamped(1100, view, width)
        assertEquals(once, DockPosition.clamped(once, view, width))
        assertEquals(0, DockPosition.clamped(40, 300, 200))
    }
}
