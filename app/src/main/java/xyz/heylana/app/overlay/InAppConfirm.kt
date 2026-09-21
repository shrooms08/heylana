package xyz.heylana.app.overlay

import xyz.heylana.app.screen.ScamWatch

/**
 * The lookout for a Solana app's **own** confirm step, where Seed Vault never appears.
 *
 * Jupiter signs with its own wallet: its green Swap puts up Android's fingerprint prompt, not
 * Seed Vault, so the signing lookout stayed silent at exactly the moment the product promises
 * to speak (found on the Seeker, Sept 21). Heylana cannot decode that transaction — there are
 * no bytes to read — so she says what the app's own screen shows: what leaves, what arrives,
 * and the checks she can make from that (a token name that copies a real one, most of the
 * wallet's balance leaving). She never says "safe", and never taps anything.
 *
 * Each app is an entry: its package, its name, and how its confirm arrives —
 *
 *  - [Prompt.SYSTEM_BIOMETRIC]: Android's fingerprint prompt goes up over the app's own form
 *    (Jupiter). The prompt is System UI's and is never read; the form under it is.
 *  - [Prompt.OWN_SHEET]: the app draws its own confirm sheet, told by its button words and an
 *    amount row. This is how Phantom, Solflare or Backpack are added once their sheets have
 *    been walked on the Seeker: an [App] with their package, name and [App.confirmWords].
 *
 * Pure Kotlin on plain positions, so every sheet is tested on the JVM as the phone reads it.
 */
object InAppConfirm {

    enum class Prompt { SYSTEM_BIOMETRIC, OWN_SHEET }

    /**
     * One app. [formWords] are the labels its confirm form shows (Jupiter: "Sell" and "Buy");
     * [confirmWords] the button that asks, for an [Prompt.OWN_SHEET] app.
     */
    data class App(
        val packageName: String,
        val name: String,
        val prompt: Prompt,
        val payLabel: String,
        val getLabel: String,
        val confirmWords: Regex = Regex("^(confirm|approve|sign|swap)$", RegexOption.IGNORE_CASE),
    )

    /** Walked on the Seeker; add others here once their confirm has been seen on the phone. */
    val APPS: List<App> = listOf(
        App("ag.jup.jupiter.android", "Jupiter", Prompt.SYSTEM_BIOMETRIC, payLabel = "Sell", getLabel = "Buy"),
    )

    fun appOf(packageName: String?): App? = APPS.firstOrNull { it.packageName == packageName }

    /** The package whose windows are Android's own: the fingerprint prompt among them. */
    const val SYSTEM_UI = "com.android.systemui"

    /** What a fingerprint prompt's window says about itself: its class, or its title. */
    private val BIOMETRIC = Regex(
        "(biometric|fingerprint|authcontainer|auth_container|credential|touch the (fingerprint )?sensor|use pin)",
        RegexOption.IGNORE_CASE
    )

    /** A prompt that unlocks the app rather than confirming something in it. */
    private val UNLOCK = Regex("(unlock|authenticate to open|log ?in)", RegexOption.IGNORE_CASE)

    /**
     * Whether this new window is Android's fingerprint prompt asking to confirm something —
     * decided from the window's own class and title, never by reading System UI.
     */
    fun biometricPrompt(packageName: String?, describedAs: String): Boolean =
        packageName == SYSTEM_UI && BIOMETRIC.containsMatchIn(describedAs) && !UNLOCK.containsMatchIn(describedAs)

    // ---------------------------------------------------------------- reading the form

    /** One element as read: its words and where it is. */
    data class Item(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** What the confirm form shows: what leaves, what arrives, and the balance it leaves from. */
    data class Sheet(
        val app: App,
        val payAmount: String,
        val payToken: String,
        val getAmount: String?,
        val getToken: String?,
        val balance: String?,
    )

    private val NUMBER = Regex("^[0-9][0-9,]*(\\.[0-9₀-₉]+)?$")
    private val TOKEN = Regex("^[A-Za-z][A-Za-z0-9.]{1,9}$")
    private val NOT_TOKENS = setOf("sell", "buy", "max", "clear", "swap", "market", "limit", "recurring", "confirm", "approve", "sign")

    /**
     * The form under the prompt, or null when this is not one: the pay card and the get card,
     * each with its token and amount. The amount is the number under the card's label row;
     * the number on the label row itself is the wallet's balance.
     */
    fun read(app: App, items: List<Item>): Sheet? {
        val pay = items.firstOrNull { it.text.equals(app.payLabel, ignoreCase = true) } ?: return null
        val get = items.firstOrNull { it.text.equals(app.getLabel, ignoreCase = true) && it.top > pay.top }
        val payBottom = get?.top ?: (pay.top + CARD_FALLBACK_PX)
        val (payAmount, payToken, balance) = card(items, pay, payBottom) ?: return null
        val getCard = get?.let { card(items, it, it.top + (it.top - pay.top)) }
        return Sheet(app, payAmount, payToken, getCard?.first, getCard?.second, balance)
    }

    /** One card: its amount, its token and the balance on its label row. */
    private fun card(items: List<Item>, label: Item, bottom: Int): Triple<String, String, String?>? {
        val rowHeight = (label.bottom - label.top).coerceAtLeast(1)
        val inside = items.filter { it !== label && it.top >= label.top - rowHeight / 2 && it.top < bottom }
        val onLabelRow = inside.filter { kotlin.math.abs(it.top - label.top) < rowHeight }
        val below = inside.filter { it.top >= label.bottom - rowHeight / 4 && it !in onLabelRow }
        val amount = below.filter { NUMBER.matches(it.text.trim()) }.minByOrNull { it.top }?.text?.trim() ?: return null
        val token = below.filter { TOKEN.matches(it.text.trim()) && it.text.trim().lowercase() !in NOT_TOKENS }
            .minByOrNull { it.top }?.text?.trim() ?: return null
        val balance = onLabelRow.firstOrNull { NUMBER.matches(it.text.trim()) }?.text?.trim()
        return Triple(amount, token, balance)
    }

    // ---------------------------------------------------------------- the checks it can make

    /** The token names a copy would be made of. */
    val KNOWN_TOKENS: List<String> = listOf(
        "SOL", "USDC", "USDT", "JUP", "BONK", "JUPSOL", "MSOL", "JITOSOL", "SKR", "WIF", "PYTH",
        "RAY", "ORCA", "WSOL", "ETH", "WBTC", "POPCAT", "RENDER",
    )

    /** The real token [name] imitates, or null: one letter off, or the same once 0 reads as O. */
    fun lookAlikeToken(name: String): String? {
        val upper = name.uppercase()
        if (upper in KNOWN_TOKENS) return null
        for (real in KNOWN_TOKENS) {
            if (ScamWatch.normalise(upper.lowercase()) == ScamWatch.normalise(real.lowercase())) return real
            if (real.length >= 3 && ScamWatch.distance(upper, real, 1) <= 1) return real
        }
        return null
    }

    /** How much of the balance is leaving, 0 to 1, or null when either number is missing. */
    fun shareOfBalance(sheet: Sheet): Double? {
        val amount = sheet.payAmount.toPlainNumber() ?: return null
        val balance = sheet.balance?.toPlainNumber()?.takeIf { it > 0 } ?: return null
        return amount / balance
    }

    private fun String.toPlainNumber(): Double? = replace(",", "").takeIf { it.all { c -> c.isDigit() || c == '.' } }?.toDoubleOrNull()

    /** The warnings, each one short sentence; never a verdict and never "safe". */
    fun warnings(sheet: Sheet): List<String> = buildList {
        lookAlikeToken(sheet.payToken)?.let { add("Check the token: ${sheet.payToken} is not $it.") }
        sheet.getToken?.let { got -> lookAlikeToken(got)?.let { add("Check the token: $got is not $it.") } }
        val share = shareOfBalance(sheet)
        when {
            share != null && share >= ALMOST_ALL -> add("That is almost all the ${sheet.payToken} in this wallet.")
            share != null && share >= MOST -> add("That is most of the ${sheet.payToken} in this wallet.")
        }
    }

    // ---------------------------------------------------------------- what is said

    /** The fixed opening, said the instant the prompt is seen; its audio is kept on the phone. */
    fun opening(app: App): String = "${app.name} wants you to confirm:"

    /** What the form shows, as said: "0.0009 SOL for about 0.1071 USDC." A quote is "about". */
    fun detail(sheet: Sheet): String {
        val getting = if (sheet.getAmount != null && sheet.getToken != null) " for about ${sheet.getAmount} ${sheet.getToken}" else ""
        return (listOf("${sheet.payAmount} ${sheet.payToken}$getting.") + warnings(sheet)).joinToString(" ")
    }

    /** The whole line as said and shown: the opening, then what the form says. */
    fun spoken(sheet: Sheet): String = "${opening(sheet.app)} ${detail(sheet)}"

    /** The strip's line: the same, and where to look before the sensor is touched. */
    fun line(sheet: Sheet): String = "${spoken(sheet)} Check it before you touch the sensor."

    /** What the glance is about, so one confirm is spoken once. */
    fun about(sheet: Sheet): String =
        listOf(sheet.app.packageName, "confirm", sheet.payAmount, sheet.payToken, sheet.getToken ?: "").joinToString("|")

    /** Past this share of the balance, it is said to be most of it. */
    const val MOST = 0.5

    /** Past this, almost all of it. */
    const val ALMOST_ALL = 0.9

    /** A card's height when the other card is not on screen. */
    private const val CARD_FALLBACK_PX = 400
}
