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

    fun sent(shortSignature: String): String = "Sent. Signature $shortSignature."

    const val CANCELLED = "Cancelled. Nothing was sent."
    const val NO_ACTION = "I couldn't tell what to send. Say the amount, the token and who it's for."
    const val NO_WALLET = "Connect your wallet in Heylana Settings first, then ask again."
}
