package xyz.heylana.app.lessons

/**
 * What the user's words mean for a lesson, decided on the phone with nothing asked of the
 * model: which topic "teach me PDAs" names, and whether words said during a lesson are a
 * command (skip, slower, example, why, stop) or an answer to the check question.
 */
object LessonWords {

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE)

    /**
     * "teach me PDAs", "teach me about staking", "a lesson on RPC", "learn Sealevel",
     * "explain Turbine to me like a lesson". "Teach me how to swap" is a walk-through, not a
     * lesson, and never matches.
     */
    private val START = Regex(
        "^\\s*(?:hey\\s+|ok(?:ay)?,?\\s+|please\\s+|can you\\s+|could you\\s+)*" +
            "(?:teach me(?:\\s+about)?|give me a lesson (?:on|about)|(?:start\\s+)?(?:a\\s+)?lesson (?:on|about)|" +
            "i want to learn(?:\\s+about)?|learn(?:\\s+about)?|let'?s learn(?:\\s+about)?)\\s+(?!how\\b|to\\b)(.+?)[.!?\\s]*$",
        OPTIONS
    )

    /** "Learn Solana", with no topic: the topic list, or which topic to pick. */
    private val PICK = Regex("^\\s*(?:teach me|learn|i want to learn|let'?s learn)\\s+solana[.!?\\s]*$", OPTIONS)

    /** The topic a request to be taught names, or null when it names none of [notes]. */
    fun topic(text: String, notes: List<LessonNote>): LessonNote? {
        val named = START.find(text)?.groupValues?.get(1)?.let(::plain) ?: return null
        // The longest alias that fits wins, so "token-2022" is not taken for "tokens".
        return notes.flatMap { note -> (note.aliases + plain(note.title) + plain(note.short)).map { it to note } }
            .filter { (alias, _) -> named == alias || Regex("\\b${Regex.escape(alias)}\\b").containsMatchIn(named) }
            .maxByOrNull { it.first.length }?.second
    }

    fun wantsTopicList(text: String): Boolean = PICK.containsMatchIn(text)

    enum class Command { SKIP, SLOWER, EXAMPLE, DEEPER, STOP }

    private val COMMANDS = listOf(
        Command.STOP to Regex(
            "^\\s*(?:ok(?:ay)?,?\\s+|please\\s+)?(?:stop|end|quit|exit|cancel|that'?s enough|enough|i'?m done|never ?mind|stop the lesson|end the lesson)" +
                "(?:\\s+(?:it|now|please|the lesson|here|there))*[\\s.!]*$",
            OPTIONS
        ),
        Command.SKIP to Regex("^\\s*(?:ok(?:ay)?,?\\s+|please\\s+)?(?:skip|next|skip (?:it|this|that|this one|ahead)|move on|next one|go on)(?:\\s+please)?[\\s.!]*$", OPTIONS),
        Command.SLOWER to Regex("^\\s*(?:ok(?:ay)?,?\\s+|please\\s+|can you\\s+|could you\\s+)?(?:slower|slow down|go slower|more slowly|say (?:it|that) slower|simpler|say (?:it|that) again|again|repeat(?: that| it)?)(?:\\s+please)?[\\s.!?]*$", OPTIONS),
        Command.EXAMPLE to Regex("^\\s*(?:ok(?:ay)?,?\\s+|please\\s+|can you\\s+|could you\\s+)?(?:give me |show me )?(?:an |another )?example(?:\\s+please)?[\\s.!?]*$", OPTIONS),
        Command.DEEPER to Regex("^\\s*(?:but |and |ok,? |okay,? |hmm,? )?(?:why|why is that|why\\?|how come|go deeper|deeper|tell me more)[\\s.!?]*$", OPTIONS)
    )

    /** One of the lesson's own words, or null when the words are an answer to the check. */
    fun command(text: String): Command? = COMMANDS.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.first

    // ------------------------------------------------------------ docs in the browser

    /** "Explain this", "what does this code do", "explain this paragraph". */
    private val EXPLAIN_THIS = Regex(
        "^\\s*(?:please\\s+|can you\\s+|could you\\s+)?(?:explain|what does|what's|what is)\\s+(?:this|that|it)" +
            "(?:\\s+(?:paragraph|code|bit|part|section|line|lines|block|snippet|function|page))?" +
            "(?:\\s+(?:mean|do|say|doing|to me|for me))*[\\s.!?]*$",
        OPTIONS
    )

    fun isExplainThis(text: String): Boolean = EXPLAIN_THIS.containsMatchIn(text)

    /** Browsers the docs are read in. */
    val BROWSERS = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "org.chromium.chrome",
        "com.brave.browser", "org.mozilla.firefox", "com.opera.browser", "com.microsoft.emmx",
        "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android"
    )

    /** Where Solana's docs and code live, as a browser's address bar shows them. */
    private val DOCS = Regex(
        "\\b(?:solana\\.com/(?:docs|developers)|docs\\.solana\\.com|beta\\.solpg\\.io|solpg\\.io|anchor-lang\\.com|" +
            "docs\\.solanamobile\\.com|spl\\.solana\\.com|docs\\.anza\\.xyz|solana\\.stackexchange\\.com)",
        OPTIONS
    )

    /** A browser in front showing Solana docs or Solana Playground. */
    fun isSolanaDocs(packageName: String?, screenText: String): Boolean =
        packageName in BROWSERS && DOCS.containsMatchIn(screenText)

    private fun plain(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9 -]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("the ").removePrefix("solana ")
}
