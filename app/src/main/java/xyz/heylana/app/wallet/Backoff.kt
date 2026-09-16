package xyz.heylana.app.wallet

/**
 * How long to wait between looks at the chain: 2s, 3s, 5s, 8s, 13s… each the sum
 * of the two before, until [totalMs] is used up — the last wait is cut short so
 * the whole thing never runs past it. Early looks are close together, because
 * most transfers land within seconds; later ones spread out.
 */
object Backoff {

    const val FIRST_MS = 2_000L
    const val SECOND_MS = 3_000L

    fun delays(totalMs: Long): List<Long> {
        val out = ArrayList<Long>()
        var spent = 0L
        var current = FIRST_MS
        var next = SECOND_MS
        while (spent < totalMs) {
            val wait = minOf(current, totalMs - spent)
            out += wait
            spent += wait
            val following = current + next
            current = next
            next = following
        }
        return out
    }
}
