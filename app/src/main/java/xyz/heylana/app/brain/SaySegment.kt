package xyz.heylana.app.brain

/**
 * One piece of a spoken answer, and the element (if any) it is about.
 *
 * `say` is either a plain string — one segment, pointing wherever `point_at` says —
 * or up to [MAX_SEGMENTS] of these, so an explanation can walk the screen: the disc
 * flies to each named element as its sentence is spoken.
 */
data class SaySegment(val text: String, val pointAt: Int? = null) {

    companion object {
        /** Four at most: a spoken answer is 1 to 3 short sentences. */
        const val MAX_SEGMENTS = 4

        /** Reads the `say` field, string or array, into segments. Empty when there is nothing to say. */
        fun of(say: Any?, fallbackPointAt: Int? = null): List<SaySegment> = when (say) {
            is String -> listOfNotNull(clean(say, fallbackPointAt))
            is List<*> -> say.mapNotNull { segment ->
                val fields = segment as? Map<*, *> ?: return@mapNotNull null
                clean(fields["text"] as? String, pointOf(fields["point_at"]))
            }.take(MAX_SEGMENTS)
            else -> emptyList()
        }

        private fun clean(text: String?, pointAt: Int?): SaySegment? =
            text?.trim()?.takeIf { it.isNotEmpty() }?.let { SaySegment(it, pointAt) }

        private fun pointOf(value: Any?): Int? = when (value) {
            is Number -> value.toInt().takeIf { it >= 0 }
            is String -> value.trim().toIntOrNull()?.takeIf { it >= 0 }
            else -> null
        }

        /** Everything the segments say, as one line — for the word cap, the log and the memory. */
        fun joined(segments: List<SaySegment>): String = segments.joinToString(" ") { it.text }.trim()
    }
}
