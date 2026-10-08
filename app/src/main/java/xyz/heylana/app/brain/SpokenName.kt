package xyz.heylana.app.brain

/**
 * What a name is spelled like, and what it sounds like, kept apart.
 *
 * The Seeker hands Heylana the owner's .skr name and the voice often mangles it. So the
 * identity carries two things: the **display name**, which is what is written on screen and
 * is never touched by this, and the **spoken name**, a respelling that only ever goes to the
 * voice ("Og-heh-neh-roo-KEV-weh"). Every line she says passes through [forSpeech] on its way
 * to the voice; nothing shown on screen does.
 *
 * With no spoken name set, every line is exactly what it was.
 */
object SpokenName {

    /**
     * [text] with the display name swapped for the spoken one, matched whole and whatever the
     * case. A name that is only punctuation, or the same as the display name, changes nothing.
     */
    fun forSpeech(text: String, displayName: String, spokenName: String): String {
        val display = displayName.trim()
        val spoken = spokenName.trim()
        if (text.isBlank() || display.isBlank() || spoken.isBlank()) return text
        if (spoken.equals(display, ignoreCase = true)) return text
        if (display.none { it.isLetterOrDigit() }) return text
        return Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(display) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            .replace(text) { Regex.escapeReplacement(spoken) }
    }

    /**
     * A respelling, cleaned: one plain line of letters, hyphens and spaces. It is never shown
     * as their name, only spoken, so anything that would read oddly aloud is dropped.
     */
    fun cleanRespelling(raw: String): String =
        raw.filter { it.isLetter() || it == '-' || it == ' ' || it == '\'' }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_SPOKEN)

    const val MAX_SPOKEN = 60
}
