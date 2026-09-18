package xyz.heylana.app.screen

/** The geometry of [ScreenSnapshot.clickTarget], on plain numbers so it is tested on the JVM. */
object ClickTarget {

    /** A button around a label, not the screen it sits on: at most this many times the label's area. */
    const val MAX_GROWTH = 40L

    /** Nor half the biggest thing on screen (on the Seeker's Wallet, the only clickable container was the whole page). */
    const val MAX_SHARE_OF_SCREEN = 0.5

    /**
     * The index of the smallest clickable box (left, top, right, bottom) that contains
     * [inner] and is still the size of a button — or null if none is.
     */
    fun containing(inner: IntArray, boxes: List<IntArray>, clickable: List<Boolean>): Int? {
        val innerArea = ((inner[2] - inner[0]).toLong() * (inner[3] - inner[1])).coerceAtLeast(1)
        val screenArea = boxes.maxOfOrNull { (it[2] - it[0]).toLong() * (it[3] - it[1]) } ?: 0L
        var best: Int? = null
        var bestArea = Long.MAX_VALUE
        boxes.forEachIndexed { i, box ->
            if (!clickable[i]) return@forEachIndexed
            val boxArea = (box[2] - box[0]).toLong() * (box[3] - box[1])
            if (boxArea > innerArea * MAX_GROWTH || boxArea >= screenArea * MAX_SHARE_OF_SCREEN) return@forEachIndexed
            val contains = box[0] <= inner[0] && box[1] <= inner[1] && box[2] >= inner[2] && box[3] >= inner[3]
            if (!contains) return@forEachIndexed
            val area = (box[2] - box[0]).toLong() * (box[3] - box[1])
            if (area < bestArea) {
                bestArea = area
                best = i
            }
        }
        return best
    }
}
