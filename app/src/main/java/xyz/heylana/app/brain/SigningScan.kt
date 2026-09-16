package xyz.heylana.app.brain

/**
 * What a signing screen shows: the addresses and the amounts on it.
 *
 * Read from the screen text Heylana already took, so nothing extra is read.
 * Wallets usually print addresses shortened (7c2y…SxSv), so those are found as
 * well; the worker matches them against addresses this user already knows. None
 * of it is logged, and none of it is ever used as a send recipient — a recipient
 * only comes from the user's own words.
 */
object SigningScan {

    data class Found(val addresses: List<String>, val shortAddresses: List<String>, val amounts: List<String>) {
        val isEmpty: Boolean get() = addresses.isEmpty() && shortAddresses.isEmpty() && amounts.isEmpty()
    }

    /** Lookups are capped at four per question; three leaves one to spare. */
    const val MAX_ADDRESSES = 3
    const val MAX_AMOUNTS = 5

    private val TOKEN_AMOUNT = Regex(
        "(?<![\\w.,])(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?\\s?(SOL|USDC|USDT|SKR|JUP|BONK|mSOL|JitoSOL)(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )

    private val DOLLAR_AMOUNT = Regex("\\$\\s?\\d[\\d,]*(?:\\.\\d+)?")

    /** An amount whose token is printed as its shortened mint: "0.05 4zMM...ncDU". */
    private val MINT_AMOUNT = Regex(
        "(?<![\\w.,])((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?)\\s?([1-9A-HJ-NP-Za-km-z]{4,8}(?:…|\\.\\.\\.)[1-9A-HJ-NP-Za-km-z]{4,8})(?![1-9A-HJ-NP-Za-km-z])"
    )

    /** Mints a wallet may print in place of a symbol. */
    private val KNOWN_MINTS = mapOf(
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "USDC",
        "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU" to "USDC",
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to "USDT",
        "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3" to "SKR",
        "So11111111111111111111111111111111111111112" to "SOL"
    )

    /** The symbol for a known mint, full or shortened. */
    fun tokenFor(mint: String): String? =
        KNOWN_MINTS.entries.firstOrNull { (full, _) -> mint == full || AddressText.matches(mint, full) }?.value

    /** Short forms that are the token of an amount, not somebody's address. */
    private fun tokenShortForms(text: String): Set<String> =
        MINT_AMOUNT.findAll(text).map { it.groupValues[2].replace("...", "…") }.toSet() +
            AddressText.shortAddresses(text).filter { tokenFor(it) != null }

    /** A wallet's own words for "you are about to sign". "Send" alone is just a button. */
    private val CONFIRM_WORDS = Regex(
        "(?<![\\p{L}])(approve|confirm|sign|signature|review|slide to|swipe to|sending|network fee)(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )

    fun of(screenText: String): Found {
        val full = addresses(screenText)
        val tokens = tokenShortForms(screenText)
        val shortened = AddressText.shortAddresses(screenText)
            .filterNot { it in tokens }
            .filterNot { short -> full.any { AddressText.matches(short, it) } }
            .take((MAX_ADDRESSES - full.size).coerceAtLeast(0))
        return Found(full, shortened, amounts(screenText))
    }

    fun addresses(text: String): List<String> = AddressText.fullAddresses(text).take(MAX_ADDRESSES)

    fun amounts(text: String): List<String> =
        (TOKEN_AMOUNT.findAll(text).map { it.value.replace(Regex("\\s+"), " ") } +
            MINT_AMOUNT.findAll(text).map { match ->
                val short = match.groupValues[2].replace("...", "…")
                "${match.groupValues[1]} ${tokenFor(short) ?: "of the token $short"}"
            } +
            DOLLAR_AMOUNT.findAll(text).map { it.value.replace(" ", "") })
            .distinct()
            .take(MAX_AMOUNTS)
            .toList()

    /**
     * Whether the screen in front is asking to sign: Seed Vault's own screen always
     * is; a wallet's screen is when it says approve, confirm, sign, review or
     * slide next to an amount or an address that is not the user's own.
     */
    fun looksLikeSigning(packageName: String?, screenText: String, ownWallet: String? = null): Boolean {
        val app = SolanaApps.of(packageName) ?: return false
        if (app.kind == SolanaApps.Kind.SIGNING) return true
        if (app.kind != SolanaApps.Kind.WALLET) return false
        if (!CONFIRM_WORDS.containsMatchIn(screenText)) return false
        val tokens = tokenShortForms(screenText)
        val others = addresses(screenText).filterNot { it == ownWallet } +
            AddressText.shortAddresses(screenText)
                .filterNot { it in tokens }
                .filterNot { ownWallet != null && AddressText.matches(it, ownWallet) }
        return others.isNotEmpty() || amounts(screenText).isNotEmpty()
    }
}
