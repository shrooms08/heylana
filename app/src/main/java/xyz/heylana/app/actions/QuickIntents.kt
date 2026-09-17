package xyz.heylana.app.actions

import java.net.URLEncoder
import java.time.LocalTime
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
    val launchPackage: String? = null
)

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

    /** [launchPackage] is the matched app's package, for open_app only. */
    fun spec(action: QuickAction, launchPackage: String? = null): IntentSpec = when (action) {
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
        is QuickAction.Timer -> IntentSpec(
            ACTION_SET_TIMER,
            extras = mapOf(EXTRA_LENGTH to action.seconds, EXTRA_SKIP_UI to false)
        )
        is QuickAction.OpenApp -> IntentSpec(ACTION_MAIN, launchPackage = requireNotNull(launchPackage))
        is QuickAction.OpenUrl -> IntentSpec(ACTION_VIEW, data = action.url)
        is QuickAction.Navigate -> IntentSpec(ACTION_VIEW, data = "geo:0,0?q=" + URLEncoder.encode(action.query, "UTF-8").replace("+", "%20"))
        is QuickAction.Dial -> IntentSpec(ACTION_DIAL, data = action.number?.let { "tel:" + it })
    }
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
    }

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
