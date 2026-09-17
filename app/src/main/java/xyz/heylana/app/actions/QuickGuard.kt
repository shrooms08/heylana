package xyz.heylana.app.actions

import java.net.URI
import java.util.Locale

/**
 * The rule between "the model asked for an intent" and the phone doing it, the same
 * rule as a send: every argument has to be in the user's own words. A time they did
 * not say, a number they did not say, an app or a place they did not name, is
 * refused. An alarm's label that was not said is simply dropped.
 */
object QuickGuard {

    sealed interface Verdict {
        data class Allowed(val action: QuickAction) : Verdict
        data class Refused(val line: String, val reason: String) : Verdict
    }

    const val TIME_NOT_SAID = "I can only set the time you say. Tell me the time."
    const val DURATION_NOT_SAID = "I can only set a timer for the time you say. Tell me how long."
    const val APP_NOT_SAID = "Tell me which app to open."
    const val URL_NOT_SAID = "I can only open a secure web address you say."
    const val PLACE_NOT_SAID = "Tell me where you want to go."
    const val NUMBER_NOT_SAID = "I can only dial a number or a name you say."
    const val SEARCH_NOT_SAID = "Tell me what to search for."
    const val PLAY_NOT_SAID = "Tell me what to play."
    const val COMMAND_NOT_SAID = "Say play, pause, next or previous."
    const val RECIPIENT_NOT_SAID = "I can only text a number or a name you say."
    const val TEXT_NOT_SAID = "Tell me what the message should say."
    const val REMINDER_NOT_SAID = "Tell me what to remind you about, and when."
    const val STATE_NOT_SAID = "Say on or off."
    const val CAMERA_NOT_SAID = "Tell me whether you want the camera or a selfie."
    const val PAGE_NOT_SAID = "Tell me which settings to open."

    fun check(action: QuickAction, said: String): Verdict {
        val text = said.lowercase(Locale.US)
        return when (action) {
            is QuickAction.Alarm -> alarm(action, text)
            is QuickAction.Timer ->
                if (Durations.seconds(text) == action.seconds) Verdict.Allowed(action)
                else Verdict.Refused(DURATION_NOT_SAID, "duration_not_said")
            is QuickAction.OpenApp ->
                if (wordsSaid(action.app, text)) Verdict.Allowed(action) else Verdict.Refused(APP_NOT_SAID, "app_not_said")
            is QuickAction.OpenUrl -> url(action, text)
            is QuickAction.Navigate ->
                if (wordsSaid(action.query, text)) Verdict.Allowed(action) else Verdict.Refused(PLACE_NOT_SAID, "place_not_said")
            is QuickAction.Dial -> dial(action, text)
            is QuickAction.YoutubeSearch ->
                if (wordsSaid(action.query, text)) Verdict.Allowed(action) else Verdict.Refused(SEARCH_NOT_SAID, "query_not_said")
            is QuickAction.WebSearch ->
                if (wordsSaid(action.query, text)) Verdict.Allowed(action) else Verdict.Refused(SEARCH_NOT_SAID, "query_not_said")
            is QuickAction.SpotifyPlay ->
                if (wordsSaid(action.query, text)) Verdict.Allowed(action) else Verdict.Refused(PLAY_NOT_SAID, "query_not_said")
            is QuickAction.MediaControl ->
                if (anyWord(MEDIA_WORDS.getValue(action.command), text)) Verdict.Allowed(action)
                else Verdict.Refused(COMMAND_NOT_SAID, "command_not_said")
            is QuickAction.Message -> message(action, text)
            is QuickAction.Reminder -> reminder(action, text)
            is QuickAction.Flashlight ->
                if (anyWord(listOf(if (action.on) "on" else "off"), text)) Verdict.Allowed(action)
                else Verdict.Refused(STATE_NOT_SAID, "state_not_said")
            is QuickAction.Camera ->
                if (anyWord(if (action.selfie) SELFIE_WORDS else CAMERA_WORDS, text)) Verdict.Allowed(action)
                else Verdict.Refused(CAMERA_NOT_SAID, "camera_not_said")
            is QuickAction.OpenSettings ->
                if (anyWord(PAGE_WORDS.getValue(action.page), text.replace(Regex("wi[\\s-]fi"), "wifi"))) Verdict.Allowed(action)
                else Verdict.Refused(PAGE_NOT_SAID, "page_not_said")
        }
    }

    private val MEDIA_WORDS = mapOf(
        "play" to listOf("play", "resume", "unpause", "continue"),
        "pause" to listOf("pause", "stop"),
        "next" to listOf("next", "skip"),
        "previous" to listOf("previous", "last", "back", "rewind")
    )
    private val CAMERA_WORDS = listOf("camera", "photo", "picture", "pic", "selfie")
    private val SELFIE_WORDS = listOf("selfie", "front")
    private val PAGE_WORDS = mapOf(
        "wifi" to listOf("wifi"),
        "bluetooth" to listOf("bluetooth"),
        "display" to listOf("display", "brightness", "screen"),
        "sound" to listOf("sound", "sounds", "volume", "ringtone"),
        "battery" to listOf("battery"),
        "accessibility" to listOf("accessibility")
    )

    /** One of [words] was said as a word. */
    private fun anyWord(words: List<String>, text: String): Boolean {
        val said = text.split(Regex("[^\\p{L}\\p{N}]+")).toSet()
        return words.any { it in said }
    }

    private fun message(action: QuickAction.Message, text: String): Verdict {
        if (!wordsSaid(action.text, text)) return Verdict.Refused(TEXT_NOT_SAID, "text_not_said")
        if (action.number != null) {
            val number = normalNumber(action.number)
            val digits = number.filter(Char::isDigit)
            if (digits.length !in 3..15 || digits !in text.filter(Char::isDigit)) {
                return Verdict.Refused(RECIPIENT_NOT_SAID, "number_not_said")
            }
            return Verdict.Allowed(QuickAction.Message(number, null, action.text))
        }
        val name = action.name ?: return Verdict.Refused(RECIPIENT_NOT_SAID, "nobody_to_text")
        return if (wordsSaid(name, text)) Verdict.Allowed(QuickAction.Message(null, name, action.text))
        else Verdict.Refused(RECIPIENT_NOT_SAID, "name_not_said")
    }

    /**
     * What to be reminded of, and a time, both said. Unlike an alarm, a bare hour is
     * not assumed to be the morning: "call mum at 6" is the next 6 o'clock to come.
     */
    private fun reminder(action: QuickAction.Reminder, text: String): Verdict {
        if (!wordsSaid(action.text, text)) return Verdict.Refused(REMINDER_NOT_SAID, "text_not_said")
        val numbers = TimeWords.numbers(text)
        val hour12 = if (action.hour % 12 == 0) 12 else action.hour % 12
        val hourSaid = action.hour in numbers || hour12 in numbers ||
            (action.hour == 12 && "noon" in text) || (action.hour == 0 && "midnight" in text)
        val minutesSaid = action.minutes == 0 || action.minutes in numbers ||
            (action.minutes == 30 && Regex("\\bhalf\\b").containsMatchIn(text)) ||
            (action.minutes in setOf(15, 45) && Regex("\\bquarter\\b").containsMatchIn(text))
        if (!hourSaid || !minutesSaid) return Verdict.Refused(REMINDER_NOT_SAID, "time_not_said")
        val morning = Regex("\\d\\s*(am|a\\.m\\.?)(?![\\p{L}])|\\bmorning\\b").containsMatchIn(text)
        val evening = Regex("\\d\\s*(pm|p\\.m\\.?)(?![\\p{L}])|\\b(evening|afternoon|tonight|night)\\b").containsMatchIn(text)
        val saidTwentyFour = action.hour in 13..23 && action.hour in numbers || action.hour == 0 && "midnight" in text
        val bare = !morning && !evening && !saidTwentyFour && action.hour != 0
        val hour = when {
            evening && action.hour in 1..11 -> action.hour + 12
            morning && action.hour in 13..23 -> action.hour - 12
            bare -> hour12 % 12
            else -> action.hour
        }
        val tomorrow = Regex("\\btomorrow\\b").containsMatchIn(text)
        return Verdict.Allowed(QuickAction.Reminder(action.text, hour, action.minutes, bareHour = bare, tomorrow = tomorrow))
    }

    private fun alarm(action: QuickAction.Alarm, text: String): Verdict {
        val numbers = TimeWords.numbers(text)
        val hour = halfOfDay(action.hour, text, numbers)
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        val hourSaid = hour in numbers || hour12 in numbers ||
            (hour == 12 && "noon" in text) || (hour == 0 && "midnight" in text)
        val minutesSaid = action.minutes == 0 || action.minutes in numbers ||
            (action.minutes == 30 && Regex("\\bhalf\\b").containsMatchIn(text)) ||
            (action.minutes in setOf(15, 45) && Regex("\\bquarter\\b").containsMatchIn(text))
        if (!hourSaid || !minutesSaid) return Verdict.Refused(TIME_NOT_SAID, "time_not_said")
        val message = action.message?.takeIf { it.lowercase(Locale.US) in text }
        return Verdict.Allowed(action.copy(hour = hour, message = message))
    }

    /**
     * Which half of the day a said hour is in, whatever the model wrote. "pm",
     * "evening", "afternoon", "tonight" or "night" make 1 to 11 the afternoon or
     * evening; "am" or "morning" make 13 to 23 the morning; and a bare hour — "7
     * tomorrow" — is the morning unless the user said the 24-hour number itself.
     */
    fun halfOfDay(hour: Int, text: String, numbers: Set<Int> = TimeWords.numbers(text)): Int {
        // "am" only straight after a number: "7am", "7 a.m." — never the "am" in "I am".
        val morning = Regex("\\d\\s*(am|a\\.m\\.?)(?![\\p{L}])|\\bmorning\\b").containsMatchIn(text)
        val evening = Regex("\\d\\s*(pm|p\\.m\\.?)(?![\\p{L}])|\\b(evening|afternoon|tonight|night)\\b").containsMatchIn(text)
        return when {
            evening && hour in 1..11 -> hour + 12
            morning && hour in 13..23 -> hour - 12
            !evening && hour in 13..23 && hour !in numbers -> hour - 12
            else -> hour
        }
    }

    private fun url(action: QuickAction.OpenUrl, text: String): Verdict {
        val given = action.url.trim()
        if (given.startsWith("http://", ignoreCase = true)) return Verdict.Refused(URL_NOT_SAID, "not_https")
        val full = if (given.contains("://")) given else "https://$given"
        val uri = runCatching { URI(full) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.US)?.removePrefix("www.")
        if (uri == null || uri.scheme?.lowercase(Locale.US) != "https" || host.isNullOrEmpty() || !host.contains('.')) {
            return Verdict.Refused(URL_NOT_SAID, "not_https")
        }
        // Said out loud, "solana dot com" is solana.com.
        val spoken = text.replace(Regex("\\s+dot\\s+"), ".")
        if (host !in spoken) return Verdict.Refused(URL_NOT_SAID, "url_not_said")
        return Verdict.Allowed(QuickAction.OpenUrl(uri.toString()))
    }

    private fun dial(action: QuickAction.Dial, text: String): Verdict {
        val number = action.number?.let { normalNumber(it) }
        if (action.number != null) {
            val digits = number?.filter(Char::isDigit).orEmpty()
            val saidDigits = text.filter(Char::isDigit)
            if (digits.length !in 3..15 || digits !in saidDigits) return Verdict.Refused(NUMBER_NOT_SAID, "number_not_said")
            return Verdict.Allowed(QuickAction.Dial(number, null))
        }
        val name = action.name ?: return Verdict.Refused(NUMBER_NOT_SAID, "nothing_to_dial")
        return if (wordsSaid(name, text)) Verdict.Allowed(QuickAction.Dial(null, name))
        else Verdict.Refused(NUMBER_NOT_SAID, "name_not_said")
    }

    /** Digits, with a leading + kept for an international number. */
    fun normalNumber(given: String): String {
        val trimmed = given.trim()
        val digits = trimmed.filter(Char::isDigit)
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    private val FILLER = setOf("the", "a", "an", "my", "app", "to", "of", "in", "at", "for", "me", "please")

    /**
     * Every meaningful word the model wrote down appears in what the user said. Both
     * sides are read with contractions spelled out, since the model writes "I am on my
     * way" for "I'm on my way".
     */
    fun wordsSaid(given: String, text: String): Boolean {
        val words = expand(given).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in FILLER }
        if (words.isEmpty()) return false
        val said = expand(text).split(Regex("[^\\p{L}\\p{N}]+")).toSet()
        return words.all { it in said }
    }

    private val CONTRACTIONS = listOf(
        "can't" to "can not", "cannot" to "can not", "won't" to "will not", "n't" to " not", "'m" to " am",
        "'re" to " are", "'ll" to " will", "'ve" to " have", "'d" to " would", "let's" to "let us",
        "it's" to "it is", "that's" to "that is", "what's" to "what is", "there's" to "there is",
        "he's" to "he is", "she's" to "she is", "who's" to "who is", "where's" to "where is"
    )

    /** Lower case, curly apostrophes straightened, contractions spelled out. */
    fun expand(text: String): String {
        var out = text.lowercase(Locale.US).replace('\u2019', '\'')
        for ((short, long) in CONTRACTIONS) out = out.replace(short, long)
        return out
    }
}

/** Numbers as they are said or typed about a time: "7", "7:30", "0730", "seven thirty". */
object TimeWords {

    val WORDS: Map<String, Int> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90
    )

    fun numbers(text: String): Set<Int> {
        val out = HashSet<Int>()
        Regex("\\d+").findAll(text).forEach { match ->
            val value = match.value.toIntOrNull() ?: return@forEach
            out += value
            // "0730" or "730": an hour and minutes written together.
            if (match.value.length in 3..4) {
                out += value / 100
                out += value % 100
            }
        }
        val words = text.split(Regex("[^\\p{L}]+"))
        words.forEachIndexed { i, word ->
            val value = WORDS[word] ?: return@forEachIndexed
            out += value
            // "twenty five" is 25.
            val next = words.getOrNull(i + 1)?.let { WORDS[it] }
            if (value >= 20 && value % 10 == 0 && next != null && next in 1..9) out += value + next
        }
        return out
    }
}

/** How long a timer was asked for: "5 minutes", "an hour and 10 minutes", "half an hour", "90 seconds". */
object Durations {

    private val PART = Regex(
        "(\\d+(?:\\.\\d+)?|an?|half an?|${TimeWords.WORDS.keys.joinToString("|")})(?:[\\s-]+(${TimeWords.WORDS.keys.joinToString("|")}))?" +
            "\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)(?![\\p{L}])"
    )

    /** Total seconds, or null if no duration was said. */
    fun seconds(text: String): Int? {
        var total = 0.0
        var found = false
        PART.findAll(text.lowercase(Locale.US)).forEach { match ->
            val amount = amount(match.groupValues[1], match.groupValues[2]) ?: return@forEach
            val unit = when (match.groupValues[3].first()) {
                'h' -> 3600
                'm' -> 60
                else -> 1
            }
            total += amount * unit
            found = true
        }
        if (Regex("(?<![\\p{L}])and a half(?![\\p{L}])").containsMatchIn(text) && found) {
            // "an hour and a half": half of the unit said just before.
            val last = PART.findAll(text.lowercase(Locale.US)).lastOrNull()
            val unit = when (last?.groupValues?.get(3)?.first()) {
                'h' -> 3600
                'm' -> 60
                else -> 1
            }
            total += unit / 2.0
        }
        return if (found && total % 1.0 == 0.0) total.toInt() else null
    }

    private fun amount(first: String, second: String): Double? {
        val base = when {
            first.startsWith("half") -> 0.5
            first == "a" || first == "an" -> 1.0
            else -> first.toDoubleOrNull() ?: TimeWords.WORDS[first]?.toDouble()
        } ?: return null
        val extra = TimeWords.WORDS[second]?.toDouble() ?: 0.0
        return base + extra
    }
}
