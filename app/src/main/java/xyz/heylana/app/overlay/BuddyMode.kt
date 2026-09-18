package xyz.heylana.app.overlay

import xyz.heylana.app.wallet.SendStage

/**
 * What Heylana is doing right now, as the chip on the strip and the task HUD says it:
 * reading the screen, thinking, preparing a send, simulating it, waiting for the user
 * to approve it in the wallet, sent, or working out a task's next step. No chip is
 * shown when it is doing nothing.
 */
enum class BuddyMode(val label: String) {
    READING("reading"),
    THINKING("thinking"),
    PREPARING("preparing"),
    SIMULATING("simulating"),
    APPROVE_IN_WALLET("approve in wallet"),
    SENT("sent"),
    WORKING("working");

    companion object {
        /** A confirmed send's stages: the final simulation, the wallet, then the chain. */
        fun of(stage: SendStage): BuddyMode = when (stage) {
            SendStage.SIMULATING -> SIMULATING
            SendStage.APPROVE_IN_WALLET -> APPROVE_IN_WALLET
            SendStage.CHECKING -> WORKING
        }
    }
}
