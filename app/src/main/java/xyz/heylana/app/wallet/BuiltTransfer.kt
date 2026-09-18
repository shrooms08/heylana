package xyz.heylana.app.wallet

/**
 * A transfer the worker built and simulated (`/send/build`): what the strip shows,
 * what the simulation said, and — only once the user confirmed and the simulation
 * passed — the unsigned bytes for Seed Vault.
 */
data class BuiltTransfer(
    /** "send" or "pay". */
    val kind: String,
    val preview: TransferPreview,
    val simulation: SimulationResult,
    /** Null on a preview, or when the simulation failed: nothing to sign either way. */
    val transaction: ByteArray?,
    val lastValidBlockHeight: Long
) {
    override fun equals(other: Any?): Boolean =
        other is BuiltTransfer && kind == other.kind && preview == other.preview && simulation == other.simulation &&
            lastValidBlockHeight == other.lastValidBlockHeight &&
            (transaction?.contentEquals(other.transaction ?: ByteArray(0)) ?: (other.transaction == null))

    override fun hashCode(): Int = listOf(kind, preview, simulation, lastValidBlockHeight).hashCode()
}

/** Everything the strip says about a transfer; addresses already shortened by the worker. */
data class TransferPreview(
    val from: String,
    val fromLabel: String,
    val to: String,
    val toLabel: String,
    val amount: String,
    val token: String,
    val feeSol: String,
    val accountRentSol: String,
    val createsAccount: Boolean,
    val programs: List<String>,
    val cluster: String
)

sealed interface SimulationResult {
    data object Passed : SimulationResult

    /** [reason] is the worker's one word; [words] is what the user is told. */
    data class Failed(val reason: String, val words: String) : SimulationResult
}

/** The words around building and simulating, and the endings of a send, from the safe-transaction wording. */
object BuildText {

    const val SIMULATING = "Checking it with the network…"
    const val PASSED = "Simulation passed"

    /** "0.05 USDC to your Heylana treasury (7c2y…SxSv). Fee 0.000005 SOL, on devnet." */
    fun preview(p: TransferPreview): String {
        val opens = if (p.createsAccount) " It opens their ${p.token} account for ${p.accountRentSol} SOL." else ""
        return "${p.amount} ${p.token} to ${p.toLabel} (${p.to}). Fee ${p.feeSol} SOL, on ${p.cluster}.$opens"
    }

    /** A failed simulation, said instead of opening the wallet. */
    fun failed(words: String): String = "I did not open the wallet because the simulation failed. $words"

    /** The bytes the worker handed back were not the transfer the user confirmed. */
    const val NOT_WHAT_WAS_CONFIRMED =
        "The prepared transfer didn't match what you confirmed, so I didn't open the wallet."

    const val REJECTED = "The wallet rejected the request. No transaction was submitted."

    /** On the strip of the first confirmed send on this phone. */
    const val TRUST_HINT = "Seed Vault will ask you to approve. Don't tick 'trust this app', so every send stays yours."

    /** Said after a send the wallet signed too fast for a person to have approved it. */
    const val AUTO_SIGNED =
        "Seed Vault signed that automatically because Heylana is marked trusted there. " +
            "You can remove that in the Wallet's connected apps."

    /** Faster than this after Seed Vault opens, no one read and approved it: the wallet trusts Heylana. */
    const val AUTO_SIGN_MS = 1_500L

    fun signedAutomatically(afterOpenMs: Long): Boolean = afterOpenMs < AUTO_SIGN_MS
    const val EXPIRED = "The prepared transaction expired before signing. Ask again and I'll rebuild and simulate a fresh copy."
    const val UNKNOWN_SIGNED =
        "The wallet returned a signature, but the network hasn't confirmed it yet. " +
            "I won't sign or submit a second copy. Check your wallet in a minute."
    const val NOT_FOUND =
        "I couldn't find it on the network. Nothing more will be sent. Check your wallet before trying again."
}
