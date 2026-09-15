package xyz.heylana.app.wallet

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

    fun short(address: String): String =
        if (address.length > 10) "${address.take(4)}…${address.takeLast(4)}" else address

    /** "Send 5 USDC to bob.skr (7c2y…ab12). Fee ~0.000005 SOL." */
    fun strip(quote: SendQuote): String {
        val who = quote.resolvedFrom?.let { "$it (${short(quote.toAddress)})" } ?: short(quote.toAddress)
        val rent = if (quote.willCreateAta) {
            ", plus ${quote.accountRent} SOL to open their ${quote.token} account"
        } else {
            ""
        }
        return "Send ${quote.amount} ${quote.token} to $who. Fee ~${quote.feeEstimate} SOL$rent."
    }

    fun sent(shortSignature: String): String = "Sent. Signature $shortSignature."

    const val CANCELLED = "Cancelled. Nothing was sent."
    const val NO_WALLET = "Connect your wallet in Heylana Settings first, then ask again."
}
