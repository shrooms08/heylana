package xyz.heylana.app.overlay

import xyz.heylana.app.wallet.SendStage

/**
 * What Heylana is doing right now, as the chip on the strip and the task HUD says it:
 * reading the screen, thinking, preparing a send, simulating it, waiting for the user
 * to approve it in the wallet, sent, working out a task's next step, watching a signing
 * screen it was not asked about, or a heads-up about what is on one. No chip is shown
 * when it is doing nothing.
 */
enum class BuddyMode(val label: String) {
    READING("reading"),
    THINKING("thinking"),
    PREPARING("preparing"),
    SIMULATING("simulating"),
    /** The same words as the strip's label while Seed Vault is open ([xyz.heylana.app.wallet.TxText.WAITING]). */
    APPROVE_IN_WALLET("waiting for your wallet"),
    SENT("sent"),
    WORKING("working"),

    /** A glance at a signing screen nobody asked about: it is looking, not answering. */
    WATCHING("watching"),

    /** A glance that is a warning — a phrase asked for, a domain that is nearly right. */
    HEADS_UP("heads up"),

    /** Two seconds after a fact the user stated was kept without being asked. */
    REMEMBERED(xyz.heylana.app.memory.MemoryWords.REMEMBERED);

    companion object {
        /** A confirmed send's stages: the final simulation, the wallet, then the chain. */
        fun of(stage: SendStage): BuddyMode = when (stage) {
            SendStage.SIMULATING -> SIMULATING
            SendStage.APPROVE_IN_WALLET -> APPROVE_IN_WALLET
            SendStage.CHECKING, SendStage.LOOKING -> WORKING
        }
    }
}
