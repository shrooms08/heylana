package xyz.heylana.app.wallet

import xyz.heylana.app.brain.AddressText
import java.math.BigDecimal

/** A send the worker checked and kept for fifteen minutes, ready for the user to confirm. */
data class SendQuote(
    val id: String,
    val toAddress: String,
    /** The .skr or .sol name the address came from, if it did. */
    val resolvedFrom: String?,
    val amount: String,
    val token: String,
    /** Null for SOL. */
    val mint: String?,
    val decimals: Int,
    val tokenProgram: String?,
    val feeEstimate: String,
    val accountRent: String,
    val willCreateAta: Boolean,
    val balance: String?,
    val cluster: Cluster
) {
    /** Lamports for SOL, base units for a token — exactly, or an exception. */
    val units: Long get() = BigDecimal(amount).movePointRight(decimals).longValueExact()
}

/** The words of the confirmation strip, read aloud as they are shown. */
object SendText {

    fun short(address: String): String = AddressText.short(address)

    /**
     * "Send 0.05 USDC to 7c2y…SxSv. Confirm?" — written by the app, never by the
     * model, and the same words shown and spoken.
     */
    fun strip(quote: SendQuote): String {
        val who = quote.resolvedFrom?.let { "$it (${short(quote.toAddress)})" } ?: short(quote.toAddress)
        val rent = if (quote.willCreateAta) {
            " It also opens their ${quote.token} account for ${quote.accountRent} SOL."
        } else {
            ""
        }
        return "Send ${quote.amount} ${quote.token} to $who.$rent Confirm?"
    }

    /**
     * The strip once the simulation has passed: from, to and who that is, amount, token,
     * fee, any account it opens, and the cluster. "Send 0.05 USDC from your wallet
     * (9WzD…AWWM) to your Heylana treasury (7c2y…SxSv). Fee 0.000005 SOL, on devnet.
     * Nothing has been signed. Confirm?"
     */
    fun previewed(p: TransferPreview, firstSend: Boolean = false): String {
        val opens = if (p.createsAccount) " It opens their ${p.token} account for ${p.accountRentSol} SOL." else ""
        val hint = if (firstSend) " ${BuildText.TRUST_HINT}" else ""
        return "Send ${p.amount} ${p.token} from ${p.fromLabel} (${p.from}) to ${p.toLabel} (${p.to}). " +
            "Fee ${p.feeSol} SOL, on ${p.cluster}.$opens Nothing has been signed.$hint Confirm?"
    }

    fun sent(shortSignature: String): String = "Sent. Signature $shortSignature."

    const val CANCELLED = "Cancelled. Nothing was sent."
    const val NO_ACTION = "I couldn't tell what to send. Say the amount, the token and who it's for."
    const val NO_WALLET = "Connect your wallet in Heylana Settings first, then ask again."
}
