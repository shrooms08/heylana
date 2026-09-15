package xyz.heylana.app.wallet

/**
 * Everything that can stop a wallet trip, in the words the user reads.
 *
 * Kept away from Android so the mapping is testable: it takes what the wallet
 * library reported — an error class name and a message — and never shows either.
 */
enum class WalletProblem(val words: String) {
    CANCELLED("Cancelled in Seed Vault."),
    NOT_ENOUGH("Not enough in this wallet to pay, including the network fee."),
    TOOK_TOO_LONG("That took too long. If Seed Vault said it was sent, check again in a minute."),
    NO_WALLET("No wallet app found. Install Seed Vault Wallet and try again."),
    UNREACHABLE("Couldn't reach Heylana. Check the connection and try again."),
    NOT_SET_UP("Wallets aren't set up on Heylana's server yet."),
    MISMATCH("That payment didn't match the quote, so nothing was unlocked."),
    SESSION_ENDED("Your wallet session ended. Connect the wallet again."),
    UNKNOWN("That didn't go through. Try again.");

    companion object {
        /** From what the wallet library reported. Nothing it said is shown as-is. */
        fun from(errorClass: String?, message: String?): WalletProblem {
            val text = "${errorClass.orEmpty()} ${message.orEmpty()}".lowercase()
            return when {
                "insufficient" in text || "not enough" in text || "0x1" in text -> NOT_ENOUGH
                "declin" in text || "cancel" in text || "not signed" in text ||
                    "not_signed" in text || "authorization" in text || "rejected" in text -> CANCELLED
                "timeout" in text || "timed out" in text -> TOOK_TOO_LONG
                "no wallet" in text || "activitynotfound" in text -> NO_WALLET
                else -> UNKNOWN
            }
        }

        /** From the worker's one-word refusal reasons. */
        fun fromWorker(reason: String): WalletProblem = when (reason) {
            "not_configured" -> NOT_SET_UP
            "bad_session", "session_required" -> SESSION_ENDED
            "wrong_mint", "short_amount", "wrong_destination", "wrong_sender", "no_reference",
            "failed_on_chain", "no_transfer", "signature_used", "not_yours" -> MISMATCH
            "unknown_quote" -> TOOK_TOO_LONG
            else -> UNKNOWN
        }
    }
}
