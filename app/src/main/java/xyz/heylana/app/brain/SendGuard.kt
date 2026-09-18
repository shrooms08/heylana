package xyz.heylana.app.brain

import java.math.BigDecimal

/** What the model proposed sending. Heylana prepares it; the user signs it. */
data class SendAction(val to: String, val amount: BigDecimal?, val token: String) {

    companion object {
        val TOKENS = setOf("SOL", "USDC", "SKR")

        /** Only a well-formed send: a recipient, a known token, and an amount if there is one. */
        fun of(type: String?, to: String?, amount: String?, token: String?): SendAction? {
            if (type != "send") return null
            val recipient = to?.trim().orEmpty()
            if (recipient.isEmpty()) return null
            val symbol = token?.trim()?.uppercase()
            if (symbol !in TOKENS) return null
            val value = amount?.trim()?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            return SendAction(recipient, value, symbol!!)
        }
    }
}

/**
 * The rules between "the model said send" and a confirmation strip.
 *
 * The recipient and the amount have to be in the user's own words — typed or
 * heard — never taken from the screen and never made up by the model. And more
 * than a quarter of a balance has to be asked for twice.
 */
object SendGuard {

    const val RECIPIENT_NOT_SAID =
        "I can only send to someone you name yourself, like an address or a .skr name."
    const val AMOUNT_NOT_SAID = "I can only send the exact amount you say. Tell me how much."
    const val PENDING_MS = 2 * 60 * 1000L

    private val QUARTER = BigDecimal("0.25")

    fun overLimitLine(token: String): String =
        "That's more than a quarter of your $token. If you mean it, say \"yes send it all\", " +
            "or say the amount again."

    sealed interface Verdict {
        /** [amount] is a plain decimal, or "all" when the user said everything. */
        data class Allowed(val to: String, val amount: String, val token: String) : Verdict
        data class Refused(val line: String, val reason: String) : Verdict
    }

    private val ALL = Regex(
        "(?<![\\p{L}])(everything|it all|all of (it|my)|all my|my whole|entire balance|max(imum)?)(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )
    private val YES_ALL = Regex("yes,?\\s+send\\s+it\\s+all", RegexOption.IGNORE_CASE)

    /** Numbers as people write them: 5, 0.05, .05, 1,250.5 — not digits inside an address. */
    private val NUMBER = Regex("(?<![\\p{L}\\p{N}.,])(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d*\\.\\d+|\\d+)(?![\\p{L}\\p{N}]|[.,]\\d)")

    private val BASE58 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

    fun check(action: SendAction, userText: String): Verdict {
        if (!recipientSaid(action.to, userText)) return Verdict.Refused(RECIPIENT_NOT_SAID, "recipient_not_said")
        if (ALL.containsMatchIn(userText)) return Verdict.Allowed(action.to.trim(), "all", action.token)
        val amount = action.amount ?: return Verdict.Refused(AMOUNT_NOT_SAID, "amount_not_said")
        if (numbersIn(userText).none { it.compareTo(amount) == 0 }) {
            return Verdict.Refused(AMOUNT_NOT_SAID, "amount_not_said")
        }
        return Verdict.Allowed(action.to.trim(), amount.stripTrailingZeros().toPlainString(), action.token)
    }

    /**
     * More than a quarter of what they hold, up to all of it. An unknown balance cannot be
     * checked, so it is not over; nor is more than the whole balance — there is nothing to
     * ask twice about, and the simulation says "not enough" in plain words instead.
     */
    fun overLimit(amount: String, balance: String?): Boolean {
        val value = amount.toBigDecimalOrNull() ?: return false
        val held = balance?.toBigDecimalOrNull() ?: return false
        if (value > held) return false
        return value > held.multiply(QUARTER)
    }

    /** The second turn: "yes send it all", or the same amount said again. */
    fun confirmsPending(userText: String, pendingAmount: String): Boolean {
        if (YES_ALL.containsMatchIn(userText)) return true
        val amount = pendingAmount.toBigDecimalOrNull() ?: return false
        return numbersIn(userText).any { it.compareTo(amount) == 0 }
    }

    fun numbersIn(text: String): List<BigDecimal> =
        NUMBER.findAll(text).mapNotNull { it.value.replace(",", "").toBigDecimalOrNull() }.toList()

    /**
     * An address must appear exactly as the user gave it: base58 is case-sensitive,
     * and one wrong letter is somebody else. A name may differ in case, and a
     * spoken "bob dot skr" is bob.skr.
     */
    fun recipientSaid(recipient: String, userText: String): Boolean {
        val to = recipient.trim()
        if (to.isEmpty()) return false
        if (BASE58.matches(to)) return userText.contains(to)
        val said = userText.lowercase()
            .replace(Regex("\\s+dot\\s+"), ".")
            .replace(Regex("\\s*\\.\\s*"), ".")
        return said.contains(to.lowercase())
    }
}
