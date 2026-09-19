package xyz.heylana.app.ui.app

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbCastTest {

    private val ink = Color(0xFFF4F4F6)
    private val cast = Color(0xFF5B8CFF)

    @Test
    fun `the orb is the ink everywhere but its outermost ring`() {
        for (outward in listOf(0f, 0.3f, 0.6f, 0.78f)) assertEquals(ink, AppOrb.inkAt(ink, cast, outward))
    }

    @Test
    fun `the outermost ring takes a faint accent cast, never the accent itself`() {
        val edge = AppOrb.inkAt(ink, cast, 1f)
        assertTrue(edge.blue > edge.red)
        assertTrue("still mostly the ink", edge.red > 0.7f)
        val nearEdge = AppOrb.inkAt(ink, cast, 0.9f)
        assertTrue(nearEdge.red > edge.red && nearEdge.red < ink.red)
    }
}
