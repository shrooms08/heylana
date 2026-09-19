package xyz.heylana.app.actions

/**
 * Something the phone's own apps can do, asked for in plain words: an alarm, a
 * timer, an app, a web page, directions, the dialer, a YouTube or web search, a song
 * on Spotify, the music's play and pause, a text to check and send, a reminder, the
 * flashlight, the camera, or a Settings page.
 *
 * Heylana never taps. It hands the phone a standard Android intent and the Clock,
 * the launcher, the browser, Maps or the dialer does the rest, in front of the
 * user. The model only writes down what was asked; [QuickGuard] decides whether
 * every part of it was in the user's own words.
 */
sealed interface QuickAction {
    val intent: String

    data class Alarm(val hour: Int, val minutes: Int, val message: String?) : QuickAction {
        override val intent get() = ALARM
    }

    data class Timer(val seconds: Int) : QuickAction {
        override val intent get() = TIMER
    }

    data class OpenApp(val app: String) : QuickAction {
        override val intent get() = OPEN_APP
    }

    data class OpenUrl(val url: String) : QuickAction {
        override val intent get() = OPEN_URL
    }

    data class Navigate(val query: String) : QuickAction {
        override val intent get() = NAVIGATE
    }

    /** A number, or a contact's name for the user to find in the dialer. Never both empty. */
    data class Dial(val number: String?, val name: String?) : QuickAction {
        override val intent get() = DIAL
    }

    /** A YouTube search, in the app or else on the web. */
    data class YoutubeSearch(val query: String) : QuickAction {
        override val intent get() = YOUTUBE_SEARCH
    }

    /** A song, an artist, a playlist or a genre, played by Spotify. */
    data class SpotifyPlay(val query: String) : QuickAction {
        override val intent get() = SPOTIFY_PLAY
    }

    /** play, pause, next or previous, to whatever is playing. */
    data class MediaControl(val command: String) : QuickAction {
        override val intent get() = MEDIA_CONTROL
    }

    /** A text message put in the messaging app, never sent by Heylana. A number or a name, never both empty. */
    data class Message(val number: String?, val name: String?, val text: String) : QuickAction {
        override val intent get() = MESSAGE
    }

    /**
     * A calendar event to save. [bareHour] is set by the guard when no half of the day
     * was said ("at 6"), [tomorrow] when "tomorrow" was; the start is worked out from both.
     */
    data class Reminder(
        val text: String,
        val hour: Int,
        val minutes: Int,
        val bareHour: Boolean = false,
        val tomorrow: Boolean = false
    ) : QuickAction {
        override val intent get() = REMINDER
    }

    data class Flashlight(val on: Boolean) : QuickAction {
        override val intent get() = FLASHLIGHT
    }

    /** The camera app; [selfie] asks it for the front camera. */
    data class Camera(val selfie: Boolean) : QuickAction {
        override val intent get() = if (selfie) SELFIE else CAMERA
    }

    data class WebSearch(val query: String) : QuickAction {
        override val intent get() = WEB_SEARCH
    }

    /** One of [SETTINGS_PAGES]. */
    data class OpenSettings(val page: String) : QuickAction {
        override val intent get() = SETTINGS
    }

    companion object {
        const val TYPE = "intent"
        const val YOUTUBE_SEARCH = "youtube_search"
        const val SPOTIFY_PLAY = "spotify_play"
        const val MEDIA_CONTROL = "media_control"
        const val MESSAGE = "message"
        const val REMINDER = "reminder"
        const val FLASHLIGHT = "flashlight"
        const val CAMERA = "camera"
        const val SELFIE = "selfie"
        const val WEB_SEARCH = "web_search"
        const val SETTINGS = "settings"

        val MEDIA_COMMANDS = listOf("play", "pause", "next", "previous")
        val SETTINGS_PAGES = listOf("wifi", "bluetooth", "display", "sound", "battery", "accessibility")
        const val ALARM = "alarm"
        const val TIMER = "timer"
        const val OPEN_APP = "open_app"
        const val OPEN_URL = "open_url"
        const val NAVIGATE = "navigate"
        const val DIAL = "dial"

        /**
         * A well-formed action from the reply's `action` object, read as plain
         * fields so this stays testable without Android's JSON. Anything missing,
         * out of range or of the wrong kind is null: nothing happens.
         */
        fun of(fields: Map<String, Any?>): QuickAction? {
            if (fields["type"] != TYPE) return null
            fun text(key: String): String? = (fields[key] as? String)?.trim()?.takeUnless { isPlaceholder(it) }
            fun int(key: String): Int? = when (val value = fields[key]) {
                is Number -> value.toDouble().takeIf { it % 1.0 == 0.0 }?.toInt()
                is String -> value.trim().toIntOrNull()
                else -> null
            }
            return when (fields["intent"]) {
                ALARM -> {
                    val hour = int("hour")?.takeIf { it in 0..23 } ?: return null
                    val minutes = (if (fields["minutes"] == null) 0 else int("minutes"))?.takeIf { it in 0..59 } ?: return null
                    Alarm(hour, minutes, text("message"))
                }
                TIMER -> int("seconds")?.takeIf { it in 1..MAX_TIMER_SECONDS }?.let { Timer(it) }
                OPEN_APP -> text("app")?.let { OpenApp(it) }
                OPEN_URL -> text("url")?.let { OpenUrl(it) }
                NAVIGATE -> text("query")?.let { Navigate(it) }
                DIAL -> {
                    val number = text("number")
                    val name = text("name")
                    if (number == null && name == null) null else Dial(number, name)
                }
                YOUTUBE_SEARCH -> text("query")?.let { YoutubeSearch(it) }
                SPOTIFY_PLAY -> text("query")?.let { SpotifyPlay(it) }
                MEDIA_CONTROL -> text("command")?.lowercase()?.takeIf { it in MEDIA_COMMANDS }?.let { MediaControl(it) }
                MESSAGE -> {
                    // The words often come back in "message" (the alarm label's field, named
                    // like the intent) rather than "text": either is the message.
                    val body = text("text") ?: text("message") ?: return null
                    // Some replies put the recipient in "to" instead of number or name, and
                    // a "name" that is only digits is a number.
                    val to = text("to")
                    val named = text("name")
                    val number = text("number")
                        ?: to?.takeIf { looksLikeNumber(it) }
                        ?: named?.takeIf { looksLikeNumber(it) }
                    val name = named?.takeIf { number == null || !looksLikeNumber(it) } ?: to?.takeIf { number == null }
                    if (number == null && name == null) null else Message(number, name?.takeIf { number == null }, body)
                }
                REMINDER -> {
                    val body = text("text") ?: return null
                    val hour = int("hour")?.takeIf { it in 0..23 } ?: return null
                    val minutes = (if (fields["minutes"] == null) 0 else int("minutes"))?.takeIf { it in 0..59 } ?: return null
                    Reminder(body, hour, minutes)
                }
                FLASHLIGHT -> when (text("state")?.lowercase()) {
                    "on" -> Flashlight(true)
                    "off" -> Flashlight(false)
                    else -> null
                }
                CAMERA -> Camera(selfie = false)
                SELFIE -> Camera(selfie = true)
                WEB_SEARCH -> text("query")?.let { WebSearch(it) }
                SETTINGS -> text("page")?.lowercase()?.replace("-", "")?.replace(" ", "")?.takeIf { it in SETTINGS_PAGES }
                    ?.let { OpenSettings(it) }
                else -> null
            }
        }

        /**
         * The one question to ask when the model named an action but left out a part it
         * needs — "Who should I text?" — instead of refusing. Null when nothing is missing
         * or the intent is not one of ours.
         */
        fun clarify(fields: Map<String, Any?>): String? {
            if (fields["type"] != TYPE) return null
            // A part is there only if it is real: the model sometimes fills a part it was not
            // given with "<UNKNOWN>" rather than leaving it empty. Numbers must be numbers.
            fun has(key: String) = (fields[key] as? String)?.trim()?.let { !isPlaceholder(it) } == true || fields[key] is Number
            fun hasNumber(key: String) = fields[key] is Number || (fields[key] as? String)?.trim()?.toDoubleOrNull() != null
            return when (fields["intent"]) {
                MESSAGE -> when {
                    !has("number") && !has("name") && !has("to") -> "Who should I text?"
                    !has("text") && !has("message") -> "What should the message say?"
                    else -> null
                }
                REMINDER -> when {
                    !has("text") -> "What should I remind you about?"
                    !hasNumber("hour") -> "What time should I remind you?"
                    else -> null
                }
                ALARM -> if (!hasNumber("hour")) "What time should the alarm be?" else null
                TIMER -> if (!hasNumber("seconds")) "How long should the timer be?" else null
                DIAL -> if (!has("number") && !has("name")) "Who should I call?" else null
                OPEN_APP -> if (!has("app")) "Which app should I open?" else null
                NAVIGATE -> if (!has("query")) "Where do you want to go?" else null
                YOUTUBE_SEARCH, WEB_SEARCH -> if (!has("query")) "What should I search for?" else null
                SPOTIFY_PLAY -> if (!has("query")) "What should I play?" else null
                FLASHLIGHT -> if (!has("state")) "On or off?" else null
                SETTINGS -> if (!has("page")) "Which settings should I open?" else null
                else -> null
            }
        }

        /** An empty part, or one the model filled with a stand-in: "null", "<UNKNOWN>", "unknown", "n/a". */
        fun isPlaceholder(value: String): Boolean {
            val v = value.trim()
            return v.isEmpty() || v.equals("null", true) || v.equals("none", true) || v.equals("unknown", true) ||
                v.equals("n/a", true) || (v.startsWith("<") && v.endsWith(">"))
        }

        /** Digits and phone punctuation only, at least three digits: a number, not a name. */
        private fun looksLikeNumber(value: String): Boolean =
            value.count(Char::isDigit) >= 3 && value.all { it.isDigit() || it in " +-().\u00a0" }

        /** Android's timer takes at most a day. */
        const val MAX_TIMER_SECONDS = 24 * 60 * 60
    }
}

/**
 * Which questions are quick actions. A question that is one goes to the worker with
 * `intent: "quick_action"`, which forces the model to write the action down and
 * nothing else — the same way a send works — so prose like "setting an alarm for
 * 7pm" can never stand in for doing it. [RULES] remain only for the hidden own-key
 * path, which has no worker to force a tool.
 */
object QuickActions {

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE)

    /** "How do I open settings" is a question about the screen, not an action. */
    private val ASKING_HOW = Regex("^\\s*(how|what|where|why|when|which|who|does|do|is|are)\\b", OPTIONS)

    /** Politeness that can come in front of an order. */
    private const val LEAD = "^\\s*(hey\\s+heylana[,\\s]*)?((please|can you|could you|would you|will you|i want you to|i need you to)\\s+)?(please\\s+)?"

    private val ALARM = Regex("\\b(set|make|create|add|put)\\b[^.?!]*\\balarm\\b|\\bwake me\\b|\\balarm (for|at)\\b", OPTIONS)
    private val TIMER = Regex("\\b(set|start|make|put)\\b[^.?!]*\\btimer\\b|\\btimer (for|of)\\b|\\bcount ?down\\b", OPTIONS)
    private val OPEN = Regex(LEAD + "(open|launch)\\s+\\S", OPTIONS)
    /** "Call me Minos" is what to call the user, never a phone call: "me" is not someone to ring. */
    private val DIAL = Regex(LEAD + "(call|dial|ring|phone)\\s+(?!me\\b)\\S", OPTIONS)
    private val NAVIGATE = Regex("\\b(directions|navigate|take me|route|drive me|walk me) to\\b|\\bhow do i get to\\b", OPTIONS)

    private val YOUTUBE = Regex("\\byoutube\\b|\\b(find|search|look up|show me|watch)\\b[^.?!]*\\bvideos?\\b", OPTIONS)
    private val PLAY = Regex(LEAD + "(play|put on)\\s+\\S", OPTIONS)
    private val MEDIA = Regex(
        LEAD + "(pause|resume|unpause|skip)\\b|\\b(pause|resume|stop|skip)\\b[^.?!]*\\b(music|song|track|audio|podcast|playback)\\b|" +
            LEAD + "(next|previous|last)\\s+(song|track)\\b|\\b(go back|back) a (song|track)\\b",
        OPTIONS
    )
    private val MESSAGE = Regex(LEAD + "(text|message|sms|whatsapp)\\s+\\S|\\bsend (a |an )?(text|message|sms)\\b", OPTIONS)
    private val REMINDER = Regex("\\bremind me\\b|\\b(set|add|create|make)\\b[^.?!]*\\breminder\\b", OPTIONS)
    private val FLASHLIGHT = Regex("\\b(flash ?light|torch)\\b", OPTIONS)
    private val CAMERA = Regex(LEAD + "(open (the )?camera|take (a |me a )?(selfie|photo|picture|pic))\\b|\\bselfie\\b", OPTIONS)
    private val WEB = Regex(LEAD + "(search|google|look up)\\s+\\S", OPTIONS)
    private val SETTINGS = Regex("\\b(wi-?fi|wi fi|bluetooth|display|sound|battery|accessibility)\\s+settings?\\b", OPTIONS)

    /** "turn it off" straight after the flashlight was switched: the pronoun can only mean the torch. */
    private val PRONOUN_SWITCH = Regex("^\\s*(please\\s+)?(turn|switch|put)\\s+(it|that|the light)\\s+(on|off)\\b|^\\s*(please\\s+)?(turn|switch)\\s+(on|off)\\s+(it|that)\\b", OPTIONS)

    /**
     * "Turn on the flashlight", "torch off", "switch the flashlight off": said outright, the
     * state is in the words, so the phone does it with nothing asked of the model — at once.
     */
    private val FLASHLIGHT_COMMAND = Regex(
        "^\\s*(hey\\s+heylana[,\\s]*)?(please\\s+|can you\\s+|could you\\s+)?" +
            "(?:(turn|switch|put|flip)\\s+)?(?:(on|off)\\s+)?(?:the\\s+|my\\s+)?(flash ?light|torch)(?:\\s+(on|off))?" +
            "(\\s+please)?[\\s.!?]*$",
        OPTIONS
    )

    /** The flashlight switch said outright, or null (no state, or both, or anything more). */
    fun flashlightCommand(question: String): QuickAction.Flashlight? {
        val match = FLASHLIGHT_COMMAND.find(question) ?: return null
        val states = listOfNotNull(match.groupValues[4].ifEmpty { null }, match.groupValues[6].ifEmpty { null })
        if (states.size != 1) return null
        return QuickAction.Flashlight(states.single().equals("on", ignoreCase = true))
    }

    /** The follow-up [PRONOUN_SWITCH] allows, or null. */
    fun flashlightFollowUp(question: String): QuickAction.Flashlight? {
        val match = PRONOUN_SWITCH.find(question) ?: return null
        val on = Regex("\\bon\\b", OPTIONS).containsMatchIn(match.value)
        return QuickAction.Flashlight(on)
    }

    fun isQuickAction(question: String): Boolean {
        if (NAVIGATE.containsMatchIn(question)) return true
        if (ASKING_HOW.containsMatchIn(question)) return false
        return ALARM.containsMatchIn(question) || TIMER.containsMatchIn(question) ||
            OPEN.containsMatchIn(question) || DIAL.containsMatchIn(question) ||
            YOUTUBE.containsMatchIn(question) || PLAY.containsMatchIn(question) || MEDIA.containsMatchIn(question) ||
            MESSAGE.containsMatchIn(question) || REMINDER.containsMatchIn(question) ||
            FLASHLIGHT.containsMatchIn(question) || CAMERA.containsMatchIn(question) ||
            WEB.containsMatchIn(question) || SETTINGS.containsMatchIn(question)
    }

    /** A text message is not a Solana send, though "send a text" has the word in it. */
    fun isMessage(question: String): Boolean = !ASKING_HOW.containsMatchIn(question) && MESSAGE.containsMatchIn(question)

    const val RULES: String =
        "Quick actions: only if the user asks the phone to do something, add \"action\" with type \"intent\" and one of: " +
            "{\"intent\":\"alarm\",\"hour\":0-23,\"minutes\":0-59,\"message\":\"...\"|null}, " +
            "{\"intent\":\"timer\",\"seconds\":n}, {\"intent\":\"open_app\",\"app\":\"name as said\"}, " +
            "{\"intent\":\"open_url\",\"url\":\"https://...\"}, {\"intent\":\"navigate\",\"query\":\"place as said\"}, " +
            "{\"intent\":\"dial\",\"number\":\"digits as said\"|null,\"name\":\"contact as said\"|null}, " +
            "{\"intent\":\"youtube_search\"|\"spotify_play\"|\"web_search\",\"query\":\"as said\"}, " +
            "{\"intent\":\"media_control\",\"command\":\"play|pause|next|previous\"}, " +
            "{\"intent\":\"message\",\"number\"|\"name\":\"as said\",\"text\":\"as said\"}, " +
            "{\"intent\":\"reminder\",\"text\":\"as said\",\"hour\":0-23,\"minutes\":0-59}, " +
            "{\"intent\":\"flashlight\",\"state\":\"on|off\"}, {\"intent\":\"camera\"|\"selfie\"}, " +
            "{\"intent\":\"settings\",\"page\":\"wifi|bluetooth|display|sound|battery|accessibility\"}. " +
            "A bare hour is the morning: \"7 tomorrow\" is hour 7. Use only what the user said, never the screen. " +
            "Keep say empty; the phone's own app does it and you never tap."
}

/**
 * One log line per action. Times and lengths are logged; what could identify
 * someone is not: a web address by its host, a place and a name by their length, a
 * number by how many digits it has.
 */
object QuickLog {
    fun describe(action: QuickAction): String = "intent=${action.intent} " + when (action) {
        is QuickAction.Alarm -> "hour=${action.hour} minutes=${action.minutes} message=${action.message != null}"
        is QuickAction.Timer -> "seconds=${action.seconds}"
        is QuickAction.OpenApp -> "app_chars=${action.app.length}"
        is QuickAction.OpenUrl -> "host=${QuickText.hostOf(action.url)}"
        is QuickAction.Navigate -> "query_chars=${action.query.length}"
        is QuickAction.Dial -> "digits=${action.number?.count(Char::isDigit) ?: 0} name=${action.name != null}"
        is QuickAction.YoutubeSearch -> "query_chars=${action.query.length}"
        is QuickAction.SpotifyPlay -> "query_chars=${action.query.length}"
        is QuickAction.MediaControl -> "command=${action.command}"
        is QuickAction.Message -> "digits=${action.number?.count(Char::isDigit) ?: 0} name=${action.name != null} text_chars=${action.text.length}"
        is QuickAction.Reminder -> "hour=${action.hour} minutes=${action.minutes} bare_hour=${action.bareHour} tomorrow=${action.tomorrow} text_chars=${action.text.length}"
        is QuickAction.Flashlight -> "state=${if (action.on) "on" else "off"}"
        is QuickAction.Camera -> "selfie=${action.selfie}"
        is QuickAction.WebSearch -> "query_chars=${action.query.length}"
        is QuickAction.OpenSettings -> "page=${action.page}"
    }
}
