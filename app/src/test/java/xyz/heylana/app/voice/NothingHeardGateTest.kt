package xyz.heylana.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "I heard nothing" may only be acted on after the user has let go. The
 * recogniser gives up on its own after a moment of quiet, and acting on that
 * while their finger is still down would put the buddy back to rest mid-hold.
 */
class NothingHeardGateTest {

    @Test
    fun `a no-match while they are still holding is not acted on`() {
        val gate = NothingHeardGate()
        gate.started()
        assertFalse(gate.nothingHeard())
    }

    @Test
    fun `it is acted on when they let go instead`() {
        val gate = NothingHeardGate()
        gate.started()
        gate.nothingHeard()
        assertTrue(gate.releasedNow())
    }

    @Test
    fun `a no-match after they let go is acted on at once`() {
        val gate = NothingHeardGate()
        gate.started()
        assertFalse(gate.releasedNow())
        assertTrue(gate.nothingHeard())
    }

    @Test
    fun `letting go with nothing waiting reports nothing`() {
        val gate = NothingHeardGate()
        gate.started()
        assertFalse(gate.releasedNow())
    }

    @Test
    fun `a held no-match is only ever reported once`() {
        val gate = NothingHeardGate()
        gate.started()
        gate.nothingHeard()
        assertTrue(gate.releasedNow())
        assertFalse(gate.releasedNow())
    }

    @Test
    fun `the next hold starts clean`() {
        val gate = NothingHeardGate()
        gate.started()
        gate.nothingHeard()
        gate.releasedNow()

        gate.started()
        assertFalse(gate.nothingHeard())
        assertTrue(gate.releasedNow())
    }
}
