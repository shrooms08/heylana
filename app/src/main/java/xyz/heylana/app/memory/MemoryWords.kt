package xyz.heylana.app.memory

/**
 * What in the user's words is about memory, decided from the words alone. Kept away from
 * Android so it is tested on the JVM.
 *
 * - "Remember that I'm new to Solana" is an explicit record: saved at once, in the user's
 *   own words, and confirmed in four words.
 * - "Shorter answers please", "slow down", "stop explaining" are preferences Heylana may
 *   offer to keep — once each, and only kept if the user says yes.
 * - "I'm new to Solana", "I use Jupiter for swaps", "call me Minos" are facts the user
 *   states about themselves: kept without being asked, while memory is on ([aboutUser]).
 * - "Forget that" takes back the line just kept.
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
    const val FORGOTTEN = "Okay, forgotten."
    const val NOTHING_TO_FORGET = "I haven't just saved anything to forget."
    /** The chip on the strip for two seconds when a line was kept without being asked. */
    const val REMEMBERED = "Remembered"

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

    /**
     * The start of a sentence about the user themselves that is worth keeping: who they are,
     * what they use, what to call them. Passing moods ("I'm bored", "I'm a bit lost") and
     * reactions ("I love it") are not facts.
     */
    private val SELF = Regex(
        "^(?:(?:and|also|oh|btw|by the way|fyi)[,\\s]+)*(?:" +
            "i(?:'m| am) (?:new to|still new to|learning|from|based in|building|into|a big fan of|" +
            "not (?:very )?(?:technical|a developer)|colou?r ?blind|left[- ]handed|vegan|vegetarian|" +
            "(?:an? )(?!bit\\b|little\\b|lot\\b|sec\\b|moment\\b|question\\b|problem\\b))|" +
            "i (?:use|mostly use|usually use|always use|prefer|trade on|swap on|stake with|stake on|live in|" +
            "work (?:as|at|in|on)|build (?:on|with)|speak|don'?t use|never use|like|love|hate|dislike)\\b|" +
            "my (?:name is|name'?s|favou?rite \\w+ is|job is|pronouns are|birthday is|language is|first language is)\\b|" +
            "call me\\b" +
            ")",
        RegexOption.IGNORE_CASE
    )

    /** "I love it", "I like this": the object is a pronoun, so there is nothing to keep. */
    private val ONLY_A_REACTION = Regex("\\b(it|this|that|these|those|them|you|here|there)$", RegexOption.IGNORE_CASE)

    /** A question tail after a comma: "I'm new to Solana, what's a PDA?" keeps only the first part. */
    private val QUESTION_TAIL = Regex(
        ",\\s*(?:so\\s+|and\\s+|but\\s+)?(?:what|how|why|when|where|who|which|can|could|would|should|do|does|is|are|will|tell|explain|show)\\b.*$",
        RegexOption.IGNORE_CASE
    )

    private val ADDRESS = Regex("[1-9A-HJ-NP-Za-km-z]{32,44}|[1-9A-HJ-NP-Za-km-z]{3,6}(?:…|\\.{2,3})[1-9A-HJ-NP-Za-km-z]{3,6}")
    private val MONEY = Regex(
        "[$€£¥₦]\\s?\\d|\\d[\\d,.]*\\s?(?:sol|usdc|usdt|skr|bonk|jup|btc|eth|lamports?|dollars?|usd|euros?|naira|pounds?)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * The fact about the user in [text], in their own words and one line, or null: the first
     * sentence that states one, without a question tail. Never an address or an amount.
     */
    fun aboutUser(text: String): String? {
        if (explicit(text) != null || inferred(text) != null || isForget(text)) return null
        for (raw in text.split(Regex("(?<=[.!?;])\\s+|\\n+"))) {
            val untailed = raw.trim().replace('’', '\'').replace(QUESTION_TAIL, "").trim()
            if (untailed.endsWith("?")) continue
            val sentence = untailed.trimEnd('.', '!', ';', ',', ' ')
            if (!SELF.containsMatchIn(sentence)) continue
            if (ONLY_A_REACTION.containsMatchIn(sentence)) continue
            if (sentence.split(Regex("\\s+")).size < MIN_FACT_WORDS || sentence.length > MAX_FACT_CHARS) continue
            if (ADDRESS.containsMatchIn(sentence) || MONEY.containsMatchIn(sentence)) return null
            return sentence
        }
        return null
    }

    private val FORGET = Regex(
        "^\\s*(?:ok(?:ay)?,?\\s+|please\\s+|actually,?\\s+)?(?:forget that|forget what i (?:just )?said|don'?t remember that|" +
            "unremember that|delete that(?: memory| note)?|scratch that)(?:\\s+please)?[\\s.!]*$",
        RegexOption.IGNORE_CASE
    )

    fun isForget(text: String): Boolean = FORGET.containsMatchIn(text)

    private val YES = Regex("^\\s*(yes|yeah|yep|yup|sure|ok(ay)?|please( do)?|do it|go ahead|remember it|of course)\\b", RegexOption.IGNORE_CASE)
    private val NO = Regex("^\\s*(no|nope|nah|don't|do not|never mind|not now)\\b", RegexOption.IGNORE_CASE)

    fun isYes(text: String): Boolean = YES.containsMatchIn(text)
    fun isNo(text: String): Boolean = NO.containsMatchIn(text)

    private const val MAX_REMARK_WORDS = 8
    private const val MIN_FACT_WORDS = 3
    /** The worker keeps one line of at most 140 characters. */
    private const val MAX_FACT_CHARS = 140
}
