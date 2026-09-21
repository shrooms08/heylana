package xyz.heylana.app.screen

/** The geometry of [ScreenSnapshot.clickTarget], on plain numbers so it is tested on the JVM. */
object ClickTarget {

    /** A button around a label, not the screen it sits on: at most this many times the label's area. */
    const val MAX_GROWTH = 40L

    /** Nor half the biggest thing on screen (on the Seeker's Wallet, the only clickable container was the whole page). */
    const val MAX_SHARE_OF_SCREEN = 0.5

    /**
     * What the element at [index] rings and takes the tap as: a label that takes no tap, the
     * smallest button-sized tappable box around it; a tappable label inside an unnamed
     * button-sized tappable box, that box (Jupiter's green Swap bar is a box with no words and
     * a tappable "Swap" inside it, and ringing the word missed the bar); else itself. A named
     * box around a tappable label (a row with its own words) is left alone.
     */
    fun target(index: Int, boxes: List<IntArray>, clickable: List<Boolean>, named: List<Boolean>): Int {
        val eligible = if (clickable[index]) {
            clickable.indices.map { it != index && clickable[it] && !named[it] }
        } else {
            clickable
        }
        return containing(boxes[index], boxes, eligible) ?: index
    }

    /**
     * The index of the smallest clickable box (left, top, right, bottom) that contains
     * [inner] and is still the size of a button — or null if none is.
     */
    fun containing(inner: IntArray, boxes: List<IntArray>, clickable: List<Boolean>): Int? {
        val innerArea = ((inner[2] - inner[0]).toLong() * (inner[3] - inner[1])).coerceAtLeast(1)
        // The screen is what every element spans together: Jupiter's read has no page-sized
        // element, and measured by its largest one the green Swap bar was "half the screen".
        val screenArea = if (boxes.isEmpty()) 0L else
            (boxes.maxOf { it[2] } - boxes.minOf { it[0] }).toLong() * (boxes.maxOf { it[3] } - boxes.minOf { it[1] })
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
