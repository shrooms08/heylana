package xyz.heylana.app.home

import xyz.heylana.app.ui.app.Glyph

/** A suggestion chip on Home: what it says, its icon, and what tapping it does. */
data class HomeChip(val label: String, val glyph: Glyph, val action: ChipAction)

sealed interface ChipAction {
    /** Sent as a message, exactly as if typed. */
    data class Send(val message: String) : ChipAction
    /** "Ask about this screen": screen questions belong to the buddy over the app, not here. */
    data object StartBuddy : ChipAction
    /** "Learn Solana": the topic list, Build and Infrastructure. */
    data object Learn : ChipAction
}

/**
 * The export's chips (frame 1), in its order, in two rows, with "Learn Solana" second. Every
 * one but those two sends its own words as a message; "Ask about this screen" starts the
 * buddy, since the app never reads a screen itself, and "Learn Solana" opens the topics.
 */
object HomeChips {

    // What Heylana is for, in the order it matters: the signing moment first. The two that
    // are about another app's screen start the buddy, since the app never reads one itself.
    val FIRST_ROW = listOf(
        HomeChip("What am I signing?", Glyph.SHIELD, ChipAction.StartBuddy),
        HomeChip("Check my balance", Glyph.BALANCE, ChipAction.Send("Check my balance")),
        HomeChip("Explain this screen", Glyph.DOC, ChipAction.StartBuddy)
    )

    val SECOND_ROW = listOf(
        HomeChip("Learn Solana", Glyph.BOOK, ChipAction.Learn),
        HomeChip("Set a timer", Glyph.TIMER, ChipAction.Send("Set a timer")),
        HomeChip("Play a song", Glyph.PLAY, ChipAction.Send("Play a song"))
    )

    val ALL: List<HomeChip> get() = FIRST_ROW + SECOND_ROW

    /** What a tap does: [send] a message, [startBuddy], or open the topics to [learn]. */
    fun tap(chip: HomeChip, send: (String) -> Unit, startBuddy: () -> Unit, learn: () -> Unit = {}) = when (val action = chip.action) {
        is ChipAction.Send -> send(action.message)
        ChipAction.StartBuddy -> startBuddy()
        ChipAction.Learn -> learn()
    }

    /** What a chip that needs the buddy says, running or started. */
    const val BUDDY_LINE = "I'm on. Open the app you want help with and tap me there."
    const val BUDDY_NEEDS_OVERLAY = "Allow Heylana over other apps first: Menu, Settings, Permissions."
}
