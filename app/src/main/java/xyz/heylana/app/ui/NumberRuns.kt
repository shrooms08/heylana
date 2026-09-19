package xyz.heylana.app.ui

/**
 * Where the numbers are in a line of text — balances, amounts, prices, percentages, counts,
 * times — so they can be drawn in the monospace face with tabular figures while every other
 * word stays Outfit. "$142.31", "0.05", "1,204", "12%", "30", "7:30" (as "7" and "30").
 * Digits inside a word or a shortened address ("Web3", "7c2y…SxSv") are left alone. Kept
 * free of Android so it is tested on the JVM.
 */
object NumberRuns {

    private val RUN = Regex(
        "(?<![\\p{L}\\p{N}_])[$€£₦]?\\d(?:[\\d,]*\\d)?(?:\\.\\d+)?%?(?![\\p{L}\\p{N}_])"
    )

    /** Each number's start and end (exclusive) in [text], in order. */
    fun ranges(text: String): List<IntRange> = RUN.findAll(text).map { it.range.first until it.range.last + 1 }.toList()
}

/**
 * [NumberRuns] for the overlay's TextViews: every number set in the platform monospace
 * face (its digits all one width), the words left in the view's Outfit.
 */
object NumberText {
    fun spanned(text: CharSequence): CharSequence {
        val ranges = NumberRuns.ranges(text.toString())
        if (ranges.isEmpty()) return text
        val out = android.text.SpannableString(text)
        for (r in ranges) {
            out.setSpan(android.text.style.TypefaceSpan("monospace"), r.first, r.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }
}
