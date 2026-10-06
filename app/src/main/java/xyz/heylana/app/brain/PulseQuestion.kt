package xyz.heylana.app.brain

/**
 * A question about what is happening on Solana **now**.
 *
 * "What hackathon is going on on Solana right now?" has no answer in a model's memory: a model
 * has no dates and does not know what closed last week. A question like that gets the worker's
 * `solana_pulse` cache instead — real items with the day they were published and a link —
 * while "what is a PDA?" stays a knowledge-base question and never touches it.
 *
 * The test is the words alone, before anything is read or asked: a time word ("right now",
 * "this week", "latest", "upcoming"), or a thing that only exists on a calendar (a hackathon,
 * a bounty, a grant, a deadline). Anything about the user's own money, the screen in front, or
 * how Solana works is left alone.
 */
object PulseQuestion {

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE)

    /** Words that mean "as of today". */
    private val NOW_WORDS = Regex(
        "\\b(right now|just now|currently|at the moment|today|this (week|month)|these days|lately|recently|" +
            "latest|newest|most recent|up to date|upcoming|coming up|happening|going on|what's on|whats on|" +
            "what's new|whats new|any news|new on solana|this year|still (open|running|live))\\b",
        OPTIONS
    )

    /** Things that live on a calendar: they are looked up, never remembered. */
    private val EVENT_WORDS = Regex(
        "\\b(hackathon|hackathons|bounty|bounties|grant|grants|deadline|deadlines|submission|submissions|" +
            "release|releases|released|announcement|announcements|news|event|events|cohort|accelerator)\\b",
        OPTIONS
    )

    /** Asked as a question, or asked for: "any hackathons", "show me what's new". */
    private val ASKING = Regex(
        "\\b(what|which|when|any|are there|is there|anything|show me|tell me|find|list|got any)\\b",
        OPTIONS
    )

    /** Their own money, the screen in front, or how something works: none of these is the pulse. */
    private val NOT_PULSE = Regex(
        "\\b(my balance|my wallet|my sol|i have|do i have|send|swap|stake|sign|signing|approve|" +
            "this screen|this button|this page|on screen|what does this|how do i|how to|what is a|what's a|" +
            "what are|explain|teach me|how does .* work)\\b",
        OPTIONS
    )

    /**
     * Whether this question is answered from the pulse cache rather than from memory or the
     * docs. Both halves are needed when the words are only time words: "what's new on Solana"
     * is the pulse, "what is a PDA" is not, and "how do I enter a hackathon" is a how-to.
     */
    fun isTimeSensitive(question: String): Boolean {
        val text = question.trim()
        if (text.isEmpty()) return false
        if (NOT_PULSE.containsMatchIn(text)) return false
        val now = NOW_WORDS.containsMatchIn(text)
        val event = EVENT_WORDS.containsMatchIn(text)
        if (!now && !event) return false
        // A calendar thing on its own still needs to be a question about one ("any hackathons",
        // "when is the deadline"), so a passing mention is not a lookup.
        return now || ASKING.containsMatchIn(text)
    }

    /** Which slice the question wants, when it names one; null for everything. */
    fun categoryOf(question: String): String? = when {
        Regex("\\b(hackathon|hackathons|bounty|bounties|grant|grants|deadline|submission)\\b", OPTIONS).containsMatchIn(question) -> "hackathon"
        Regex("\\b(release|releases|released|version|changelog)\\b", OPTIONS).containsMatchIn(question) -> "release"
        else -> null
    }
}
