package xyz.heylana.app.actions

import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * The Android intent for an allowed action, written as plain data so it can be
 * tested without a phone; [QuickActionRunner] turns it into a real Intent.
 *
 * Dialing is ACTION_DIAL, which only puts the number in the dialer. ACTION_CALL,
 * which would place the call, is never built.
 */
data class IntentSpec(
    val action: String,
    val data: String? = null,
    val extras: Map<String, Any> = emptyMap(),
    /** For open_app: the launcher intent of this package instead of [action]. */
    val launchPackage: String? = null,
    /** Only this app may take the intent (YouTube, Spotify). */
    val targetPackage: String? = null,
    /** Tried if nothing takes [action]: the browser, or another way of sending a message. */
    val fallback: IntentSpec? = null,
    /** The MIME type, where the intent needs one (a plain share). */
    val type: String? = null
)

/** What the phone does for an action: start an activity, press a media key, or switch the torch. */
sealed interface QuickEffect {
    data class Launch(val intent: IntentSpec) : QuickEffect
    /** A key press (down and up) to the media session that is playing, by its KeyEvent code. */
    data class MediaKey(val keyCode: Int) : QuickEffect
    data class Torch(val on: Boolean) : QuickEffect
}

object QuickIntents {

    // The values of the android.provider.AlarmClock and android.content.Intent constants.
    const val ACTION_SET_ALARM = "android.intent.action.SET_ALARM"
    const val ACTION_SET_TIMER = "android.intent.action.SET_TIMER"
    const val EXTRA_HOUR = "android.intent.extra.alarm.HOUR"
    const val EXTRA_MINUTES = "android.intent.extra.alarm.MINUTES"
    const val EXTRA_MESSAGE = "android.intent.extra.alarm.MESSAGE"
    const val EXTRA_LENGTH = "android.intent.extra.alarm.LENGTH"
    const val EXTRA_SKIP_UI = "android.intent.extra.alarm.SKIP_UI"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_DIAL = "android.intent.action.DIAL"
    const val ACTION_MAIN = "android.intent.action.MAIN"

    const val ACTION_SEARCH = "android.intent.action.SEARCH"
    const val YOUTUBE_RESULTS = "https://www.youtube.com/results?search_query="
    const val WEB_SEARCH_PAGE = "https://www.google.com/search?q="
    const val ACTION_SENDTO = "android.intent.action.SENDTO"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val EXTRA_TEXT = "android.intent.extra.TEXT"
    const val ACTION_INSERT = "android.intent.action.INSERT"
    const val ACTION_MEDIA_PLAY_FROM_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"
    const val ACTION_STILL_IMAGE_CAMERA = "android.media.action.STILL_IMAGE_CAMERA"
    const val EXTRA_QUERY = "query"
    const val EXTRA_MEDIA_FOCUS = "android.intent.extra.focus"
    const val MEDIA_FOCUS_ANY = "vnd.android.cursor.item/*"
    const val EXTRA_SMS_BODY = "sms_body"
    const val CALENDAR_EVENTS = "content://com.android.calendar/events"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BEGIN_TIME = "beginTime"
    const val EXTRA_END_TIME = "endTime"
    /** The extras camera apps read for the front camera; each app honours its own, if any. */
    val FRONT_CAMERA_EXTRAS: Map<String, Any> = mapOf(
        "android.intent.extras.CAMERA_FACING" to 1,
        "android.intent.extras.LENS_FACING_FRONT" to 1,
        "android.intent.extra.USE_FRONT_CAMERA" to true
    )
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val SPOTIFY_PACKAGE = "com.spotify.music"
    /** How long a reminder's calendar event lasts. */
    const val REMINDER_MINUTES = 30L

    /** android.view.KeyEvent's codes. */
    const val KEYCODE_MEDIA_PLAY = 126
    const val KEYCODE_MEDIA_PAUSE = 127
    const val KEYCODE_MEDIA_NEXT = 87
    const val KEYCODE_MEDIA_PREVIOUS = 88

    /** android.provider.Settings' actions for each page. */
    val SETTINGS_ACTIONS = mapOf(
        "wifi" to "android.settings.WIFI_SETTINGS",
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "display" to "android.settings.DISPLAY_SETTINGS",
        "sound" to "android.settings.SOUND_SETTINGS",
        "battery" to "android.intent.action.POWER_USAGE_SUMMARY",
        "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS"
    )

    /**
     * What the phone does. [launchPackage] is the matched app's package, for open_app
     * only; [now] and [zone] place a reminder.
     */
    fun effect(
        action: QuickAction,
        launchPackage: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): QuickEffect = when (action) {
        is QuickAction.MediaControl -> QuickEffect.MediaKey(
            when (action.command) {
                "play" -> KEYCODE_MEDIA_PLAY
                "pause" -> KEYCODE_MEDIA_PAUSE
                "next" -> KEYCODE_MEDIA_NEXT
                else -> KEYCODE_MEDIA_PREVIOUS
            }
        )
        is QuickAction.Flashlight -> QuickEffect.Torch(action.on)
        else -> QuickEffect.Launch(spec(action, launchPackage, now, zone))
    }

    /** The intent for an action that starts an activity. */
    fun spec(
        action: QuickAction,
        launchPackage: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): IntentSpec = when (action) {
        // SKIP_UI false: the Clock comes to the front showing what it just set.
        is QuickAction.Alarm -> IntentSpec(
            ACTION_SET_ALARM,
            extras = buildMap {
                put(EXTRA_HOUR, action.hour)
                put(EXTRA_MINUTES, action.minutes)
                put(EXTRA_SKIP_UI, false)
                action.message?.let { put(EXTRA_MESSAGE, it) }
            }
        )
        // SKIP_UI true: on the Seeker's Clock, showing the timer screen only created the
        // timer, paused at its full length; skipping the screen is what starts it running.
        is QuickAction.Timer -> IntentSpec(
            ACTION_SET_TIMER,
            extras = mapOf(EXTRA_LENGTH to action.seconds, EXTRA_SKIP_UI to true)
        )
        is QuickAction.OpenApp -> IntentSpec(ACTION_MAIN, launchPackage = requireNotNull(launchPackage))
        is QuickAction.OpenUrl -> IntentSpec(ACTION_VIEW, data = action.url)
        is QuickAction.Navigate -> IntentSpec(ACTION_VIEW, data = "geo:0,0?q=" + URLEncoder.encode(action.query, "UTF-8").replace("+", "%20"))
        is QuickAction.Dial -> IntentSpec(ACTION_DIAL, data = action.number?.let { "tel:" + it })
        // The results page, opened by YouTube itself. ACTION_SEARCH brought YouTube to the
        // front with nothing searched for.
        is QuickAction.YoutubeSearch -> IntentSpec(
            ACTION_VIEW,
            data = YOUTUBE_RESULTS + encode(action.query),
            targetPackage = YOUTUBE_PACKAGE,
            fallback = IntentSpec(ACTION_VIEW, data = YOUTUBE_RESULTS + encode(action.query))
        )
        is QuickAction.SpotifyPlay -> IntentSpec(
            ACTION_MEDIA_PLAY_FROM_SEARCH,
            extras = mapOf(EXTRA_QUERY to action.query, EXTRA_MEDIA_FOCUS to MEDIA_FOCUS_ANY),
            targetPackage = SPOTIFY_PACKAGE
        )
        // SENDTO fills in the message and waits: the user reads it and taps send themselves.
        is QuickAction.Message -> IntentSpec(
            ACTION_SENDTO,
            data = "smsto:" + (action.number ?: ""),
            extras = mapOf(EXTRA_SMS_BODY to action.text),
            // Not every messaging app answers smsto:; sms: and a plain share both do.
            fallback = IntentSpec(
                ACTION_VIEW,
                data = "sms:" + (action.number ?: ""),
                extras = mapOf(EXTRA_SMS_BODY to action.text),
                fallback = IntentSpec(
                    ACTION_SEND,
                    extras = mapOf(EXTRA_TEXT to action.text),
                    type = "text/plain"
                )
            )
        )
        // INSERT opens the calendar's new-event screen, filled in, for the user to save.
        is QuickAction.Reminder -> {
            val begin = reminderStart(action, now)
            val beginMs = begin.atZone(zone).toInstant().toEpochMilli()
            IntentSpec(
                ACTION_INSERT,
                data = CALENDAR_EVENTS,
                extras = mapOf(
                    EXTRA_TITLE to action.text,
                    EXTRA_BEGIN_TIME to beginMs,
                    EXTRA_END_TIME to beginMs + REMINDER_MINUTES * 60_000L
                )
            )
        }
        is QuickAction.Camera -> IntentSpec(
            ACTION_STILL_IMAGE_CAMERA,
            extras = if (action.selfie) FRONT_CAMERA_EXTRAS else emptyMap()
        )
        // The search page in the default browser. ACTION_WEB_SEARCH asks the user to choose
        // when a browser and the Google app both take it (they do on the Seeker).
        is QuickAction.WebSearch -> IntentSpec(ACTION_VIEW, data = WEB_SEARCH_PAGE + encode(action.query))
        is QuickAction.OpenSettings -> IntentSpec(SETTINGS_ACTIONS.getValue(action.page))
        is QuickAction.MediaControl, is QuickAction.Flashlight ->
            throw IllegalArgumentException("${action.intent} starts no activity")
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    /**
     * When a reminder starts. With a half of the day said, the hour as it is: today if
     * it is still to come, else tomorrow ("tomorrow" said: tomorrow). A bare hour ("at
     * 6") is the next time that clock reading comes round — 6 AM, else 6 PM, else 6 AM
     * tomorrow; with "tomorrow", the first from 7 AM on (so "tomorrow at 6" is 6 PM).
     */
    fun reminderStart(action: QuickAction.Reminder, now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate()
        fun at(date: java.time.LocalDate, hour: Int) = date.atTime(hour, action.minutes)
        if (!action.bareHour) {
            if (action.tomorrow) return at(today.plusDays(1), action.hour)
            val candidate = at(today, action.hour)
            return if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
        }
        val morning = action.hour % 12
        val evening = morning + 12
        if (action.tomorrow) {
            val tomorrow = today.plusDays(1)
            return when {
                morning == 0 -> at(tomorrow, 12)
                morning >= MORNING_STARTS -> at(tomorrow, morning)
                else -> at(tomorrow, evening)
            }
        }
        return listOf(at(today, morning), at(today, evening), at(today.plusDays(1), morning))
            .first { it.isAfter(now) }
    }

    private const val MORNING_STARTS = 7
}

/** What Heylana says as the phone's own app takes over. One short line. */
object QuickText {

    fun line(action: QuickAction, now: LocalTime, appLabel: String? = null): String = when (action) {
        is QuickAction.Alarm -> {
            val day = if (LocalTime.of(action.hour, action.minutes).isAfter(now)) "today" else "tomorrow"
            "Alarm set for ${clock(action.hour, action.minutes)} $day."
        }
        is QuickAction.Timer -> "Timer set for ${duration(action.seconds)}."
        is QuickAction.OpenApp -> "Opening ${appLabel ?: action.app}."
        is QuickAction.OpenUrl -> "Opening ${hostOf(action.url)}."
        is QuickAction.Navigate -> "Opening maps for ${action.query}."
        is QuickAction.Dial ->
            // Never the digits: a number read aloud is slow to hear and nobody's business nearby.
            if (action.number != null) DIALER else "$DIALER Search for ${action.name} there."
        is QuickAction.YoutubeSearch -> "Searching YouTube for ${action.query}."
        // Spotify does not start playing from an intent: it lands on the search. Say so.
        is QuickAction.SpotifyPlay -> "Opened Spotify for ${action.query}. Tap play."
        is QuickAction.MediaControl -> when (action.command) {
            "play" -> "Playing."
            "pause" -> "Paused."
            "next" -> "Next song."
            else -> "Previous song."
        }
        // Neither the number nor the words: the user reads the message on screen and sends it.
        is QuickAction.Message ->
            if (action.number != null) "Your message is ready. Check it and tap send."
            else "Your message is ready. Pick ${action.name} and tap send."
        is QuickAction.Reminder -> {
            val today = java.time.LocalDate.of(2000, 1, 1)
            reminderLine(QuickIntents.reminderStart(action, today.atTime(now)), today)
        }
        is QuickAction.Flashlight -> if (action.on) "Flashlight on." else "Flashlight off."
        is QuickAction.Camera -> if (action.selfie) "Opening the camera for a selfie." else "Opening the camera."
        is QuickAction.WebSearch -> "Searching the web for ${action.query}."
        is QuickAction.OpenSettings -> "Opening ${SETTINGS_NAMES.getValue(action.page)} settings."
    }

    /** "Reminder for 6 PM today. Check it and tap save." — the calendar's own screen. */
    fun reminderLine(start: LocalDateTime, today: java.time.LocalDate): String =
        "Reminder for ${dayAndTime(start, today)}. Check it and tap save."

    /** "Reminder saved for 6 PM today." — written straight into the calendar. */
    fun reminderSavedLine(start: LocalDateTime, today: java.time.LocalDate): String =
        "Reminder saved for ${dayAndTime(start, today)}."

    private fun dayAndTime(start: LocalDateTime, today: java.time.LocalDate): String {
        val day = if (start.toLocalDate() == today) "today" else "tomorrow"
        return "${clock(start.hour, start.minute)} $day"
    }

    val SETTINGS_NAMES = mapOf(
        "wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "display" to "display", "sound" to "sound",
        "battery" to "battery", "accessibility" to "accessibility"
    )

    /** An action whose app is not on the phone: which one, in one line. */
    fun missingApp(action: QuickAction): String = when (action) {
        is QuickAction.SpotifyPlay -> "Spotify isn't installed on this phone."
        is QuickAction.YoutubeSearch -> "YouTube isn't installed, and no browser could open it."
        is QuickAction.Message -> "There's no messaging app on this phone."
        is QuickAction.Reminder -> "There's no calendar app on this phone."
        is QuickAction.Camera -> "There's no camera app on this phone."
        is QuickAction.WebSearch -> "There's no browser on this phone."
        is QuickAction.Alarm, is QuickAction.Timer -> "There's no clock app on this phone."
        is QuickAction.Navigate -> "There's no maps app on this phone."
        is QuickAction.OpenUrl -> "There's no browser on this phone."
        else -> NOTHING_HANDLES
    }

    /** A text to a contact found by name: their name, never their number. */
    fun messageTo(name: String) = "Your message to $name is ready. Check it and tap send."

    const val NO_FLASHLIGHT = "This phone has no flashlight I can use."
    const val FLASHLIGHT_BUSY = "The flashlight is busy right now. Close the camera and try again."
    const val NO_MEDIA = "Nothing is playing to control."

    const val DIALER = "Opening the dialer."
    const val NO_APP = "I couldn't find an app with that name."
    const val NO_ACTION = "I didn't catch what to do. Try again, like \"set a timer for 5 minutes\"."
    fun ambiguous(first: String, second: String) = "I found $first and $second. Say which one."
    const val NOTHING_HANDLES = "No app on this phone can do that."

    /** "7 AM", "7:30 PM", "12 PM". */
    fun clock(hour: Int, minutes: Int): String {
        val h = if (hour % 12 == 0) 12 else hour % 12
        val half = if (hour < 12) "AM" else "PM"
        return if (minutes == 0) "$h $half" else String.format(Locale.US, "%d:%02d %s", h, minutes, half)
    }

    /** "5 minutes", "1 hour 30 minutes", "1 minute 30 seconds". */
    fun duration(seconds: Int): String {
        val parts = listOf(seconds / 3600 to "hour", seconds % 3600 / 60 to "minute", seconds % 60 to "second")
            .filter { it.first > 0 }
            .map { (n, unit) -> "$n $unit${if (n == 1) "" else "s"}" }
        return parts.joinToString(" ")
    }

    fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
}

/** An installed app the launcher can open. */
data class LauncherApp(val label: String, val packageName: String)

/**
 * Which installed app the user meant, from the name they said. Fuzzy on purpose —
 * "the wallet" should find "Seed Vault Wallet" — but never a guess between two
 * equally good matches.
 */
object AppMatcher {

    sealed interface Match {
        data class Found(val app: LauncherApp, val score: Int) : Match
        data class Ambiguous(val first: LauncherApp, val second: LauncherApp) : Match
        data object None : Match
    }

    private val FILLER = setOf("the", "a", "an", "my", "app", "application", "open", "launch", "please")

    fun best(query: String, apps: List<LauncherApp>): Match {
        val wanted = words(query)
        if (wanted.isEmpty()) return Match.None
        val scored = apps.map { it to score(wanted, words(it.label)) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        val top = scored.firstOrNull() ?: return Match.None
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && runnerUp.second == top.second && runnerUp.first.packageName != top.first.packageName) {
            return Match.Ambiguous(top.first, runnerUp.first)
        }
        return Match.Found(top.first, top.second)
    }

    fun words(text: String): List<String> =
        text.lowercase(Locale.US).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in FILLER }

    /**
     * 100 the same name; 80 when the name holds every word said ("wallet" in "Seed
     * Vault Wallet" — and in "Phantom Wallet", which is why two of those ask which);
     * 60 when what was said holds every word of the name; up to 50 for a near spelling.
     */
    fun score(wanted: List<String>, label: List<String>): Int {
        if (label.isEmpty()) return 0
        if (wanted == label) return 100
        if (label.containsAll(wanted)) return 80
        if (wanted.containsAll(label)) return 60
        val ratio = similarity(wanted.joinToString(" "), label.joinToString(" "))
        return if (ratio >= NEAR) (ratio * 50).toInt() else 0
    }

    private const val NEAR = 0.8

    private fun similarity(a: String, b: String): Double {
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - distance(a, b).toDouble() / longest
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
