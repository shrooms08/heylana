package xyz.heylana.app.home

import xyz.heylana.app.ui.app.Glyph

/** A suggestion chip on Home: what it says, its icon, and what tapping it does. */
data class HomeChip(val label: String, val glyph: Glyph, val action: ChipAction)

sealed interface ChipAction {
    /** Sent as a message, exactly as if typed. */
    data class Send(val message: String) : ChipAction
    /** "Ask about this screen": screen questions belong to the buddy over the app, not here. */
    data object StartBuddy : ChipAction
}

/**
 * The export's chips (frame 1), in its order, in two rows. Every one but the first sends
 * its own words as a message; "Ask about this screen" starts the buddy, since the app never
 * reads a screen itself.
 */
object HomeChips {

    val FIRST_ROW = listOf(
        HomeChip("Ask about this screen", Glyph.DOC, ChipAction.StartBuddy),
        HomeChip("Teach me to swap", Glyph.SWAP, ChipAction.Send("Teach me to swap")),
        HomeChip("Check my balance", Glyph.BALANCE, ChipAction.Send("Check my balance"))
    )

    val SECOND_ROW = listOf(
        HomeChip("Send USDC", Glyph.SEND, ChipAction.Send("Send USDC")),
        HomeChip("Set a timer", Glyph.TIMER, ChipAction.Send("Set a timer")),
        HomeChip("Play a song", Glyph.PLAY, ChipAction.Send("Play a song"))
    )

    val ALL: List<HomeChip> get() = FIRST_ROW + SECOND_ROW

    /** What a tap does: [send] a message, or [startBuddy]. */
    fun tap(chip: HomeChip, send: (String) -> Unit, startBuddy: () -> Unit) = when (val action = chip.action) {
        is ChipAction.Send -> send(action.message)
        ChipAction.StartBuddy -> startBuddy()
    }

    /** What "Ask about this screen" says, running or started. */
    const val BUDDY_LINE = "I'm on. Open the app you want help with and tap me there."
    const val BUDDY_NEEDS_OVERLAY = "Allow Heylana over other apps first: it's in Menu, then Settings."
}
