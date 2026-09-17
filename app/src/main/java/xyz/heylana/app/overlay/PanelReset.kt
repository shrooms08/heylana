package xyz.heylana.app.overlay

/**
 * What the box looks like when it is opened again: empty. Every close — an action firing,
 * an answer settling, a cancelled send, a timeout, a tap outside — ends with the window
 * going from the box (or the task HUD) back to the docked disc, and that is the moment
 * the pane is put back to its empty compose state, so nothing from the last exchange
 * ("thinking…", an old answer, a greyed ask pill) greets the next one.
 */
object PanelReset {

    /** The parts of the pane a reset touches, so the rule can be tested without a view. */
    interface Resettable {
        fun setStatusText(text: String)
        fun setInputText(text: String)
        fun setAskEnabled(enabled: Boolean)
        fun setConfirmShown(shown: Boolean)
        fun setShapeNow(shape: ChatPanelView.Shape)
        fun clearSignals()
    }

    /** Whether moving the window from [from] to [to] is a close that resets the pane. */
    fun resetsOn(from: String, to: String): Boolean = to == "DOCKED" && (from == "COMPOSE" || from == "HUD")

    fun apply(panel: Resettable) {
        panel.clearSignals()
        panel.setShapeNow(ChatPanelView.Shape.BOX)
        panel.setStatusText("")
        panel.setInputText("")
        panel.setConfirmShown(false)
        panel.setAskEnabled(true)
    }
}
