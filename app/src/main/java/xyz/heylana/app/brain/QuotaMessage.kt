package xyz.heylana.app.brain

/**
 * What the buddy says when the worker turns a question away for a reason the
 * user can act on. Anything else is an ordinary error and gets no special words.
 */
object QuotaMessage {

    const val TALKS_CAP =
        "That was your last free talk this month. Go Pro in Settings for unlimited."

    const val DAILY_CAP = "That is all Heylana can do today. Try again tomorrow."

    const val SESSION_ENDED = "Your wallet session ended. Connect your wallet again in Settings."

    /** "Use my own key": Anthropic said no to it, or it isn't shaped like one. */
    const val OWN_KEY_REFUSED = "Anthropic refused your own key. Check it in Menu, Advanced."
    const val OWN_KEY_MALFORMED = "Your own key doesn't look right. Check it in Menu, Advanced."

    /** The line for this refusal, or null when it is not one of these. */
    fun forReason(reason: String): String? = when (reason) {
        "talks_cap" -> TALKS_CAP
        "daily_cap" -> DAILY_CAP
        "bad_session" -> SESSION_ENDED
        "own_key_refused" -> OWN_KEY_REFUSED
        "bad_key" -> OWN_KEY_MALFORMED
        else -> null
    }
}
