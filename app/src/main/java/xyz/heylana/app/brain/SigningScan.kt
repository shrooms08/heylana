package xyz.heylana.app.brain

import org.sol4k.Base58

/**
 * What a signing screen shows: the addresses and the amounts on it.
 *
 * Read from the screen text Heylana already took, so nothing extra is read. The
 * addresses go to the model to be looked up with explain_address; they are never
 * logged, and they are never used as a send recipient — a recipient only ever
 * comes from the user's own words.
 */
object SigningScan {

    data class Found(val addresses: List<String>, val amounts: List<String>)

    /** Lookups are capped at four per question; three leaves one to spare. */
    const val MAX_ADDRESSES = 3
    const val MAX_AMOUNTS = 5

    private val BASE58_RUN = Regex("(?<![1-9A-HJ-NP-Za-km-z])[1-9A-HJ-NP-Za-km-z]{32,44}(?![1-9A-HJ-NP-Za-km-z])")

    private val TOKEN_AMOUNT = Regex(
        "(?<![\\w.,])(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?\\s?(SOL|USDC|USDT|SKR|JUP|BONK|mSOL|JitoSOL)(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )

    private val DOLLAR_AMOUNT = Regex("\\$\\s?\\d[\\d,]*(?:\\.\\d+)?")

    /** A wallet's own words for "you are about to sign". "Send" alone is just a button. */
    private val CONFIRM_WORDS = Regex("(?<![\\p{L}])(approve|confirm|sign|signature)(?![\\p{L}])", RegexOption.IGNORE_CASE)

    fun of(screenText: String): Found = Found(addresses(screenText), amounts(screenText))

    fun addresses(text: String): List<String> =
        BASE58_RUN.findAll(text).map { it.value }
            .filter { isAddress(it) }
            .distinct()
            .take(MAX_ADDRESSES)
            .toList()

    fun amounts(text: String): List<String> =
        (TOKEN_AMOUNT.findAll(text).map { it.value.replace(Regex("\\s+"), " ") } +
            DOLLAR_AMOUNT.findAll(text).map { it.value.replace(" ", "") })
            .distinct()
            .take(MAX_AMOUNTS)
            .toList()

    /**
     * Whether the screen in front is asking to sign: Seed Vault's own screen always
     * is; a wallet's screen is when it says approve, confirm or sign next to an
     * address or an amount. Anything else is not, whatever it says.
     */
    fun looksLikeSigning(packageName: String?, screenText: String): Boolean {
        val app = SolanaApps.of(packageName) ?: return false
        if (app.kind == SolanaApps.Kind.SIGNING) return true
        if (app.kind != SolanaApps.Kind.WALLET) return false
        if (!CONFIRM_WORDS.containsMatchIn(screenText)) return false
        return addresses(screenText).isNotEmpty() || amounts(screenText).isNotEmpty()
    }

    private fun isAddress(candidate: String): Boolean =
        runCatching { Base58.decode(candidate).size == ADDRESS_BYTES }.getOrDefault(false)

    private const val ADDRESS_BYTES = 32
}
