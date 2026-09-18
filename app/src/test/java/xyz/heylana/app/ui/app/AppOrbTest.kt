package xyz.heylana.app.ui.app

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.heylana.app.orbs.OrbState

class AppOrbTest {

    @Test
    fun `the states are the library's`() {
        assertEquals(OrbState.BREATHING, AppOrb.stateFor(OrbMode.IDLE))
        assertEquals(OrbState.WORKING, AppOrb.stateFor(OrbMode.THINKING))
        assertEquals(OrbState.LISTENING, AppOrb.stateFor(OrbMode.LISTENING))
        assertEquals(OrbState.COMPOSING, AppOrb.stateFor(OrbMode.SPEAKING))
        assertEquals(0.5, AppOrb.speedFor(OrbMode.IDLE), 0.0)
        assertEquals(1.0, AppOrb.speedFor(OrbMode.THINKING), 0.0)
    }

    /** core.ts inkColor, untinted: Math.round((dark ? 1 - w : w) * 255), clamped first. */
    @Test
    fun `grey ink is the library's, mirrored on dark`() {
        assertEquals(255, AppOrb.grey(0.0, dark = true))
        assertEquals(0, AppOrb.grey(0.0, dark = false))
        assertEquals(56, AppOrb.grey(0.78, dark = true))     // 0.22 * 255 = 56.1
        assertEquals(128, AppOrb.grey(0.5, dark = true))     // 127.5 rounds up, as Math.round does
        assertEquals(255, AppOrb.grey(-0.2, dark = true))
        assertEquals(0, AppOrb.grey(1.4, dark = true))
    }

    /** core.ts inkColor, tinted: dark fades the tint toward black with depth, light toward white. */
    @Test
    fun `tinted ink ramps the tint the library's way`() {
        assertEquals(143, AppOrb.tinted(143, 0.0, dark = true))
        assertEquals(72, AppOrb.tinted(143, 0.5, dark = true))   // 71.5 up
        assertEquals(0, AppOrb.tinted(143, 1.0, dark = true))
        assertEquals(199, AppOrb.tinted(143, 0.5, dark = false)) // 143 + 112 * 0.5
        assertEquals(255, AppOrb.tinted(143, 1.0, dark = false))
    }
}
