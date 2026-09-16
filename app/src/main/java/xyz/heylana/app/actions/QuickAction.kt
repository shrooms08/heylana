package xyz.heylana.app.actions

/**
 * Something the phone's own apps can do, asked for in plain words: an alarm, a
 * timer, an app, a web page, directions, the dialer.
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

    companion object {
        const val TYPE = "intent"
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
            fun text(key: String): String? = (fields[key] as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
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
                else -> null
            }
        }

        /** Android's timer takes at most a day. */
        const val MAX_TIMER_SECONDS = 24 * 60 * 60
    }
}

/**
 * When the model is told about quick actions at all. The rules cost tokens on every
 * request they go with, so they go only when the question sounds like one.
 */
object QuickActions {

    private val WORDS = Regex(
        "(?<![\\p{L}])(alarm|wake me|timer|countdown|open|launch|go to|take me to|directions|navigate|" +
            "route to|get to|call|dial|ring|phone)(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )

    fun mentions(question: String): Boolean = WORDS.containsMatchIn(question)

    const val RULES: String =
        "Quick actions: only if the user asks you to set an alarm or a timer, open an app or a website, get " +
            "directions, or call someone, add \"action\" with type \"intent\" and one of: " +
            "{\"intent\":\"alarm\",\"hour\":0-23,\"minutes\":0-59,\"message\":\"...\"|null}, " +
            "{\"intent\":\"timer\",\"seconds\":n}, {\"intent\":\"open_app\",\"app\":\"name as said\"}, " +
            "{\"intent\":\"open_url\",\"url\":\"https://...\"}, {\"intent\":\"navigate\",\"query\":\"place as said\"}, " +
            "{\"intent\":\"dial\",\"number\":\"digits as said\"|null,\"name\":\"contact as said\"|null}. " +
            "Use only what the user said, never the screen. Keep say to a few words; the phone's own app does " +
            "it and you never tap. \"How do I open\" is a question, not an action."
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
    }
}
