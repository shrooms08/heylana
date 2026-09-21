package xyz.heylana.app.wallet

import xyz.heylana.app.brain.SolanaApps

/**
 * A balance the worker read is on the worker's network, and says so. On the Seeker, with the
 * worker on devnet and the Wallet (mainnet) in front, Heylana said "You've got 17.65 USDC",
 * which read as the Wallet's own number. So when a balance was read and the network is not
 * mainnet, the line names it — "17.65 USDC on devnet" — and inside a wallet or swap app,
 * whose screen shows mainnet, it says plainly that the two differ. On mainnet nothing changes.
 */
object BalanceNetwork {

    private val AMOUNT = Regex(
        "\\b\\d[\\d,]*(?:\\.\\d+)?\\s+(?:SOL|USDC|USDT|SKR|JUP|BONK|JUPSOL|[A-Z]{2,6})\\b(?!\\s+on\\s+devnet)"
    )
    private val SAYS_DEVNET = Regex("\\bdevnet\\b", RegexOption.IGNORE_CASE)

    /**
     * The words to say for an answer whose balances were read ([balancesRead]) on the worker's
     * network ([devnet] or not), with [appInFront] the wallet or swap app on screen, if any,
     * as [appName] names it.
     */
    fun said(text: String, devnet: Boolean, balancesRead: Boolean, appInFront: String?): String {
        if (!devnet || !balancesRead || text.isBlank()) return text
        return withWalletLine(withNetwork(text), appInFront)
    }

    /** The same for an answer given in pieces: the network on the first amount, the app's line last. */
    fun saidPieces(pieces: List<String>, devnet: Boolean, balancesRead: Boolean, appInFront: String?): List<String> {
        if (!devnet || !balancesRead || pieces.isEmpty()) return pieces
        val out = pieces.toMutableList()
        if (out.none { SAYS_DEVNET.containsMatchIn(it) }) {
            val first = out.indexOfFirst { AMOUNT.containsMatchIn(it) }.takeIf { it >= 0 } ?: 0
            out[first] = withNetwork(out[first])
        }
        out[out.lastIndex] = withWalletLine(out.last(), appInFront)
        return out
    }

    /** "17.65 USDC" becomes "17.65 USDC on devnet"; with no amount to hang it on, "On devnet: …". */
    fun withNetwork(text: String): String {
        if (SAYS_DEVNET.containsMatchIn(text)) return text
        val amount = AMOUNT.find(text) ?: return "On devnet: $text"
        val end = amount.range.last + 1
        return text.substring(0, end) + " on devnet" + text.substring(end)
    }

    private fun withWalletLine(text: String, appInFront: String?): String {
        // The model often says it already ("not the mainnet wallet shown"): once is enough.
        if (appInFront == null || text.contains("mainnet", ignoreCase = true)) return text
        return text.trimEnd() + " That's your devnet balance; $appInFront shows mainnet."
    }

    /** How the line names the app in front, when it is one that shows balances; else null. */
    fun appName(packageName: String?): String? {
        val app = SolanaApps.of(packageName) ?: return null
        if (app.kind != SolanaApps.Kind.WALLET && app.kind != SolanaApps.Kind.SWAP) return null
        return if (app.packageName == WALLET_PACKAGE) "the Wallet" else app.name
    }

    private const val WALLET_PACKAGE = "com.solanamobile.wallet"

    /** Whether the worker's usage line says get_balances ran for this answer. */
    fun readBalances(tools: String?): Boolean = tools?.contains("get_balances") == true
}
