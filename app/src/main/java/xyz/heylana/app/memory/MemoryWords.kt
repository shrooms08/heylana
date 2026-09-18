package xyz.heylana.app.memory

/**
 * What in the user's words is about memory, decided from the words alone. Kept away from
 * Android so it is tested on the JVM.
 *
 * - "Remember that I'm new to Solana" is an explicit record: saved at once, in the user's
 *   own words, and confirmed in four words.
 * - "Shorter answers please", "slow down", "stop explaining" are preferences Heylana may
 *   offer to keep — once each, and only kept if the user says yes.
 */
object MemoryWords {

    /** Four words. */
    const val SAVED = "Got it, I'll remember."
    const val OFFER = "Sure. Want me to remember that?"
    const val DECLINED = "Okay, I won't."
    const val OFF = "Memory is off. Turn it on in Menu, Memory."
    const val NO_WALLET = "Connect your wallet in Heylana to use memory."
    const val REFUSED = "That isn't something I keep. I don't remember addresses or amounts."
    const val FAILED = "I couldn't save that just now."

    /** The preferences, exactly as the worker allows them. */
    const val SHORTER = "Prefers shorter answers"
    const val SLOWER = "Prefers slower explanations"
    const val NO_EXPLANATIONS = "Prefers answers without explanations"

    private val EXPLICIT = Regex(
        "^\\s*(?:hey\\s+)?(?:heylana[,\\s]+)?(?:please\\s+)?(?:can you\\s+|could you\\s+)?remember(?:\\s+that)?[,:\\s]+(.+?)[.!\\s]*$",
        RegexOption.IGNORE_CASE
    )

    /** "do you remember…", "remember when…", "remember to…" (a reminder) are not records. */
    private val NOT_A_RECORD = Regex(
        "^\\s*(?:do you|did you|you)\\s+remember\\b|\\bremember\\s+(?:when|what|who|where|how|why|to|me)\\b",
        RegexOption.IGNORE_CASE
    )

    /** The line to keep, in the user's own words, or null when this is not "remember that…". */
    fun explicit(text: String): String? {
        if (NOT_A_RECORD.containsMatchIn(text)) return null
        val content = EXPLICIT.find(text)?.groupValues?.get(1)?.trim() ?: return null
        return content.takeIf { it.length >= 2 }
    }

    private val PREFERENCES = listOf(
        SHORTER to Regex("\\b(shorter|be (?:more )?brief|too long|keep it short|less words|fewer words)\\b", RegexOption.IGNORE_CASE),
        SLOWER to Regex("\\b(slower|slow down|too fast)\\b", RegexOption.IGNORE_CASE),
        NO_EXPLANATIONS to Regex("\\b(stop explaining|no explanations?|just (?:the answer|tell me))\\b", RegexOption.IGNORE_CASE),
    )

    /** A preference the user just expressed, in a short remark (eight words at most), or null. */
    fun inferred(text: String): String? {
        if (text.trim().split(Regex("\\s+")).size > MAX_REMARK_WORDS) return null
        return PREFERENCES.firstOrNull { (_, pattern) -> pattern.containsMatchIn(text) }?.first
    }

    private val YES = Regex("^\\s*(yes|yeah|yep|yup|sure|ok(ay)?|please( do)?|do it|go ahead|remember it|of course)\\b", RegexOption.IGNORE_CASE)
    private val NO = Regex("^\\s*(no|nope|nah|don't|do not|never mind|not now)\\b", RegexOption.IGNORE_CASE)

    fun isYes(text: String): Boolean = YES.containsMatchIn(text)
    fun isNo(text: String): Boolean = NO.containsMatchIn(text)

    private const val MAX_REMARK_WORDS = 8
}
