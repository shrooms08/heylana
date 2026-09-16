package xyz.heylana.app.brain

/**
 * How long a spoken answer may be. A signing explanation is read while a finger is
 * over Approve: two sentences, under 40 words. Anything else keeps the prompt's 1
 * to 3 short sentences, with 60 words as the line the app enforces. An answer over
 * its line is sent back once to be said in fewer words; never twice.
 */
object AnswerLength {

    const val SIGNING_WORDS = 40
    const val GENERAL_WORDS = 60

    fun capFor(explainsSigning: Boolean): Int = if (explainsSigning) SIGNING_WORDS else GENERAL_WORDS

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
