package xyz.heylana.app.brain

/**
 * How long a spoken answer may be. A signing explanation is read while a finger is
 * over Approve: two sentences, under 40 words. Anything else keeps the prompt's 1
 * to 3 short sentences, with 60 words as the line the app enforces; a task step is
 * kept under 25, its reason included. An answer over
 * its line is sent back once to be said in fewer words; never twice.
 */
object AnswerLength {

    const val SIGNING_WORDS = 40
    const val GENERAL_WORDS = 60

    /** A spoken task step, reason included when teaching. */
    const val STEP_WORDS = 25

    /**
     * The cap for a reply to a question, on every route alike — chat included: 40 for a
     * signing explanation, 25 for a reply that starts a task (it is a spoken step), else 60.
     */
    fun capFor(explainsSigning: Boolean, startsTask: Boolean = false): Int = when {
        explainsSigning -> SIGNING_WORDS
        startsTask -> STEP_WORDS
        else -> GENERAL_WORDS
    }

    fun words(text: String): Int = text.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }

    fun tooLong(text: String, cap: Int): Boolean = words(text) > cap

    /**
     * The shorter version, if it really is shorter and still says something;
     * otherwise the original stands.
     */
    fun better(original: String, shortened: String?): String {
        val candidate = shortened?.trim()?.trim('"')?.trim().orEmpty()
        return if (candidate.isNotEmpty() && words(candidate) < words(original)) candidate else original
    }
}
