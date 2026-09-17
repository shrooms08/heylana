package xyz.heylana.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelResetTest {

    /** A pane left as the SMOKE found it: thinking from the last exchange. */
    private class FakePane : PanelReset.Resettable {
        var status = "thinking…"
        var input = "set a timer for 1 minute"
        var ask = false
        var confirm = true
        var shape = ChatPanelView.Shape.STRIP
        var signals = true
        override fun setStatusText(text: String) { status = text }
        override fun setInputText(text: String) { input = text }
        override fun setAskEnabled(enabled: Boolean) { ask = enabled }
        override fun setConfirmShown(shown: Boolean) { confirm = shown }
        override fun setShapeNow(shape: ChatPanelView.Shape) { this.shape = shape }
        override fun clearSignals() { signals = false }
    }

    @Test
    fun `a reset leaves an empty compose box - no status, empty input, ask enabled`() {
        val pane = FakePane()
        PanelReset.apply(pane)
        assertEquals("", pane.status)
        assertEquals("", pane.input)
        assertTrue(pane.ask)
        assertFalse(pane.confirm)
        assertEquals(ChatPanelView.Shape.BOX, pane.shape)
        assertFalse(pane.signals)
    }

    @Test
    fun `every close - action, answer, cancel, timeout, tap outside - docks from the box or HUD, and resets`() {
        // All of them end in closePanel, which docks from COMPOSE; a task ends from HUD.
        for (from in listOf("COMPOSE", "HUD")) assertTrue(from, PanelReset.resetsOn(from, "DOCKED"))
        // Opening, or the capsule of a spoken question melting away, is not a close of the box.
        assertFalse(PanelReset.resetsOn("DOCKED", "COMPOSE"))
        assertFalse(PanelReset.resetsOn("CAPSULE", "DOCKED"))
        assertFalse(PanelReset.resetsOn("COMPOSE", "HUD"))
    }
}
