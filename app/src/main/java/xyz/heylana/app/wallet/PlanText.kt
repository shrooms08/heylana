package xyz.heylana.app.wallet

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The words the Plan card and the Go Pro sheet use, kept away from Compose so
 * they can be tested. Every number on those screens comes from the worker; this
 * only says it.
 */
object PlanText {

    const val GO_PRO = "Go Pro, \$15/month"

    fun name(plan: String): String = when (plan) {
        "pro" -> "Pro"
        "judge" -> "Judge"
        else -> "Free"
    }

    /** Free's allowance, as the plan says it (welcome talks are extra, once). */
    const val FREE_TALKS = 30

    /** What the plan gives: "30 talks a month", "Unlimited talks", "Unlimited until Nov 9". */
    fun summary(standing: Standing): String = when (standing.plan) {
        "judge" -> standing.judgeUntil?.let { "Unlimited until ${day(it)}" } ?: "Unlimited talks"
        "pro" -> "Unlimited talks"
        else -> "$FREE_TALKS talks a month"
    }

    /** "12 of 30 used this month" on a limited plan; null when there is no limit. */
    fun used(standing: Standing): String? =
        standing.limit?.let { "${standing.used} of $it used this month" }

    /** "Pro until Oct 15, 2026", "Judge until Nov 9, 2026", or null on Free. */
    fun until(standing: Standing): String? = when (standing.plan) {
        "judge" -> standing.judgeUntil?.let { "Judge until ${date(it)}" }
        "pro" -> standing.proUntil?.let { "Pro until ${date(it)}" }
        else -> null
    }

    /**
     * The day on the worker's calendar, which is UTC. A judge code good until
     * Nov 9 ends at 23:59:59 UTC that day — already Nov 10 on a phone east of
     * Greenwich — so the phone's own timezone would show the wrong day.
     */
    fun date(iso: String): String =
        runCatching { DATE.format(Instant.parse(iso).atZone(ZoneOffset.UTC)) }.getOrDefault(iso.take(DATE_ONLY))

    /**
     * A token amount from its base units, exactly: never fewer than two decimals,
     * never a trailing zero past them. 100000 at 6 decimals is "0.10".
     */
    fun amount(units: Long, decimals: Int): String {
        val value = BigDecimal.valueOf(units).movePointLeft(decimals).stripTrailingZeros()
        return (if (value.scale() < MIN_DECIMALS) value.setScale(MIN_DECIMALS) else value).toPlainString()
    }

    /** "SKR is not on devnet.", or null where SKR exists. */
    fun skrMissing(cluster: Cluster): String? = if (cluster.hasSkr) null else "SKR is not on ${cluster.id}."

    /** "You'll send 0.10 USDC". */
    fun send(quote: Quote): String =
        "You'll send ${amount(quote.amount, quote.decimals)} ${quote.currency.uppercase(Locale.US)}"

    /** "About $0.10 at today's SKR price", for a token whose dollar value moves. */
    fun worth(quote: Quote): String? {
        val usd = quote.priceUsd ?: return null
        if (quote.currency == "usdc") return null
        return "About \$$usd at today's ${quote.currency.uppercase(Locale.US)} price"
    }

    /** The day alone, on the worker's UTC calendar: "Nov 9". */
    fun day(iso: String): String =
        runCatching { DAY.format(Instant.parse(iso).atZone(ZoneOffset.UTC)) }.getOrDefault(iso.take(DATE_ONLY))

    private val DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private const val DATE_ONLY = 10
    private const val MIN_DECIMALS = 2
}
