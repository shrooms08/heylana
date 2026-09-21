package xyz.heylana.app.wallet

import xyz.heylana.app.brain.AddressText

/**
 * Where a transaction stands, in the words on the strip.
 *
 * The user must never have to guess whether Heylana is describing something, has prepared
 * it, or is waiting on their signature — so every send carries one of these labels from the
 * moment its simulation passes until it ends, on the strip itself, above what it says:
 *
 * - **Prepared, not signed** — built and simulated; nothing has been signed.
 * - **Waiting for your wallet** — Seed Vault is open, asking the user.
 * - **Signed, confirming** / **Checking the network** — the wallet is done; the chain is asked.
 * - **Sent** — the network has it.
 * - **Not sent** — cancelled, failed or expired; nothing left the wallet.
 * - **Not confirmed yet** — the wallet may have sent it and the network has not said. Never
 *   "Not sent": that would be a guess, and a wrong one would invite a second send.
 *
 * This is not a mode of its own. It rides on the send path that was already there
 * ([SendFlow], [SendStage], [SendResult]); [TxMachine] only decides which label follows which.
 */
sealed interface TxState {
    val label: String

    data class Prepared(val summary: TxSummary) : TxState {
        override val label get() = TxText.PREPARED
    }

    data object Waiting : TxState {
        override val label get() = TxText.WAITING
    }

    /** [signed] false: the wallet ended without a signature and the send is being looked for. */
    data class Confirming(val signed: Boolean) : TxState {
        override val label get() = if (signed) TxText.CONFIRMING else TxText.LOOKING
    }

    data class Sent(val shortSignature: String, val explorerUrl: String?) : TxState {
        override val label get() = TxText.SENT
    }

    data class NotSent(val ending: TxEnding) : TxState {
        override val label get() = TxText.NOT_SENT
    }

    data object Unsure : TxState {
        override val label get() = TxText.UNSURE
    }

    val ended: Boolean get() = this is Sent || this is NotSent || this is Unsure
}

/** Why a send did not go: said, and shown, differently. */
enum class TxEnding { CANCELLED, FAILED, EXPIRED }

/** What happened to a send, in the order it can happen. */
sealed interface TxEvent {
    data class Built(val summary: TxSummary) : TxEvent
    data object WalletOpened : TxEvent
    /** [signed] false: the wallet gave no signature, so the chain is searched for the send. */
    data class Checking(val signed: Boolean) : TxEvent
    data class Landed(val shortSignature: String, val explorerUrl: String?) : TxEvent
    data object Rejected : TxEvent
    data object Failed : TxEvent
    data object Expired : TxEvent
    /** The wallet may have sent it; the network has not confirmed it. */
    data object Unconfirmed : TxEvent
}

/**
 * Which label follows which. A new build always starts a new send; anything else that
 * does not fit where the send stands is ignored — the network cannot report a send that the
 * wallet was never asked to sign, and nothing moves a send on once it has ended.
 */
object TxMachine {

    fun next(state: TxState?, event: TxEvent): TxState? {
        if (event is TxEvent.Built) return TxState.Prepared(event.summary)
        return when (state) {
            null -> null
            is TxState.Prepared -> when (event) {
                TxEvent.WalletOpened -> TxState.Waiting
                // Cancel on the strip, before the wallet was ever opened.
                TxEvent.Rejected -> TxState.NotSent(TxEnding.CANCELLED)
                TxEvent.Failed -> TxState.NotSent(TxEnding.FAILED)
                TxEvent.Expired -> TxState.NotSent(TxEnding.EXPIRED)
                else -> state
            }
            TxState.Waiting -> when (event) {
                is TxEvent.Checking -> TxState.Confirming(event.signed)
                is TxEvent.Landed -> TxState.Sent(event.shortSignature, event.explorerUrl)
                TxEvent.Rejected -> TxState.NotSent(TxEnding.CANCELLED)
                TxEvent.Failed -> TxState.NotSent(TxEnding.FAILED)
                TxEvent.Expired -> TxState.NotSent(TxEnding.EXPIRED)
                TxEvent.Unconfirmed -> TxState.Unsure
                else -> state
            }
            is TxState.Confirming -> when (event) {
                is TxEvent.Landed -> TxState.Sent(event.shortSignature, event.explorerUrl)
                // The blockhash ran out with nothing on chain: it never went.
                TxEvent.Expired -> TxState.NotSent(TxEnding.EXPIRED)
                // Signed, but not found or not as prepared: say so, never "not sent".
                TxEvent.Failed, TxEvent.Unconfirmed -> TxState.Unsure
                else -> state
            }
            // Ended: only a new build starts again.
            is TxState.Sent, is TxState.NotSent, TxState.Unsure -> state
        }
    }
}

/** What the simulation passed: what leaves the wallet, what arrives, the fee. */
data class TxSummary(
    val leaves: String,
    val arrives: String,
    val fee: String,
    val cluster: Cluster
) {
    companion object {
        /** From the worker's preview of the transfer it built and simulated; nothing is recomputed. */
        fun of(p: TransferPreview): TxSummary {
            val rent = if (p.createsAccount) ", and ${p.accountRentSol} SOL to open their account" else ""
            return TxSummary(
                leaves = "${p.amount} ${p.token}$rent",
                arrives = "${p.amount} ${p.token} at ${p.toLabel} (${p.to})",
                fee = "${p.feeSol} SOL",
                cluster = Cluster.fromWorker(p.cluster)
            )
        }
    }
}

/**
 * The small badge naming the network: amber for devnet, plain for mainnet. It comes from
 * the worker's CLUSTER — /me, or the send's own quote — and never from the app; with nothing
 * heard yet there is no badge rather than a guess.
 */
enum class ClusterBadge(val label: String, val amber: Boolean) {
    DEVNET("Devnet", amber = true),
    MAINNET("Mainnet", amber = false);

    companion object {
        fun of(workerCluster: String?): ClusterBadge? = when (workerCluster) {
            Cluster.DEVNET.id -> DEVNET
            Cluster.MAINNET.id -> MAINNET
            else -> null
        }

        fun of(cluster: Cluster): ClusterBadge = if (cluster == Cluster.DEVNET) DEVNET else MAINNET
    }
}

/** Every word a send's labels and endings say. Written by the app, never by the model. */
object TxText {

    const val PREPARED = "Prepared, not signed"
    const val WAITING = "Waiting for your wallet"
    const val CONFIRMING = "Signed, confirming"
    const val LOOKING = "Checking the network"
    const val SENT = "Sent"
    const val NOT_SENT = "Not sent"
    const val UNSURE = "Not confirmed yet"

    /** Between the label and the badge on the card's first line. */
    const val BADGE_SEPARATOR = " · "

    /** The card's first line: the label, then the network's badge. */
    fun heading(state: TxState, cluster: Cluster): String =
        state.label + BADGE_SEPARATOR + ClusterBadge.of(cluster).label

    /** The prepared card: the heading, then what leaves, what arrives and the fee. */
    fun preparedCard(summary: TxSummary): String =
        heading(TxState.Prepared(summary), summary.cluster) + "\n" +
            "Leaves your wallet: ${summary.leaves}\n" +
            "Arrives: ${summary.arrives}\n" +
            "Fee: ${summary.fee}"

    /**
     * Said once the simulation passes. On devnet it says so, because the first thing heard
     * about a send is where the money is.
     */
    fun preparedSpoken(cluster: Cluster): String =
        if (cluster == Cluster.DEVNET) {
            "I've prepared it on devnet. Nothing moves until you approve in your wallet."
        } else {
            "I've prepared it. Nothing moves until you approve in your wallet."
        }

    /**
     * The first thing said about a send, when it is not the prepared line (the over-a-quarter
     * question comes first): on devnet it opens by saying so. Mainnet is the default and says
     * nothing extra.
     */
    fun onNetwork(line: String, cluster: Cluster): String =
        if (cluster == Cluster.DEVNET) "On devnet: $line" else line

    /** Under "Prepared, not signed" once Confirm is tapped, while it is checked once more. */
    const val OPENING_WALLET = "Checking it once more, then opening your wallet."

    /** The words under "Waiting for your wallet". */
    const val APPROVE_IN_WALLET = "Approve it in Seed Vault, or reject it there."

    /** Under "Signed, confirming". */
    const val CONFIRMING_DETAIL = "Your wallet signed it. Waiting for the network to confirm."

    /** Under "Checking the network": the wallet ended without saying. */
    const val LOOKING_DETAIL = "Your wallet didn't say whether it went. I'm looking for it on the network."

    fun done(amount: String, token: String, to: String): String =
        "Done. $amount $token went to ${AddressText.short(to)}."

    const val CANCELLED = "Cancelled. Nothing left your wallet."

    private const val NOTHING_LEFT = "Nothing left your wallet."

    /**
     * A send that did not go: the plain reason (the worker's simulation words, or the
     * wallet's), then one next step, then whether anything left the wallet — always.
     * Heylana never tries again by itself.
     */
    fun failed(reason: String, code: String?): String =
        listOf(sentence(reason), nextStep(code), NOTHING_LEFT).filter { it.isNotBlank() }.joinToString(" ")

    const val EXPIRED =
        "It expired before you signed it. Ask again and I'll prepare a fresh one. $NOTHING_LEFT"

    /** Signed and not seen, or seen and not as prepared: the honest answer is not yet known. */
    const val UNSURE_SIGNED =
        "The network hasn't confirmed it yet, so it may have left your wallet. " +
            "I won't send it again; check your wallet in a minute."

    /** The wallet gave no signature and the send was not found on the network. */
    const val NOT_FOUND =
        "I couldn't find it on the network, so it most likely didn't leave your wallet. " +
            "Check your wallet before asking again."

    /** The one next step for each way a simulation or a trip to the wallet can fail. */
    fun nextStep(code: String?): String = when (code) {
        "not_enough_token" -> "Send less, or add to that balance first."
        "not_enough_sol" -> "Send less SOL, or add some to your wallet first."
        "no_sol_for_fee" -> "You need about 0.001 SOL more for fees."
        "recipient_account_needs_sol" -> "Add that much SOL to your wallet, then ask again."
        "below_rent" -> "Leave a little SOL behind, or send all of it."
        "blockhash_expired", "simulation_unavailable", "unreachable" -> "The network was busy, try again."
        "program_error" -> "Ask me what the error means, or try a different amount."
        "wrong_cluster", "rpc_wrong_cluster" -> "Switch networks and ask again."
        "no_wallet" -> "Connect your wallet in Heylana Settings first."
        // Refused on purpose: there is no next step that makes it safe.
        "grants_power", "unexpected_drain", "not_what_was_confirmed" -> ""
        else -> "Try again in a moment."
    }

    /** A send's ending: the label it moves to, and the line shown and said under it. */
    data class Ending(val event: TxEvent, val line: String)

    /** How a send that stopped is told: what left the wallet is always said. */
    fun ending(stopped: SendResult.Stopped): Ending = when (stopped.kind) {
        StopKind.CANCELLED -> Ending(TxEvent.Rejected, CANCELLED)
        StopKind.FAILED -> Ending(TxEvent.Failed, failed(stopped.line, stopped.code))
        StopKind.EXPIRED -> Ending(TxEvent.Expired, EXPIRED)
        StopKind.UNSURE_SIGNED -> Ending(TxEvent.Unconfirmed, UNSURE_SIGNED)
        StopKind.NOT_FOUND -> Ending(TxEvent.Unconfirmed, NOT_FOUND)
    }

    /** Explorer's page for a signature, on the send's own network. */
    fun explorerUrl(signature: String, cluster: Cluster): String =
        "https://explorer.solana.com/tx/$signature" + if (cluster == Cluster.DEVNET) "?cluster=devnet" else ""

    /** The chip under "Done": the short signature, opening Explorer. */
    fun signatureChipTitle(shortSignature: String): String = "Signature $shortSignature"

    private fun sentence(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        return if (t.last() in ".!?") t else "$t."
    }
}
