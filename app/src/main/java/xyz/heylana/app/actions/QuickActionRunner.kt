package xyz.heylana.app.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import xyz.heylana.app.HeylanaLog
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Hands an allowed action to the phone. The intent starts a new task, so the Clock,
 * the app, the browser, Maps, the dialer, Messages, the calendar, the camera or
 * Settings comes to the front, and the user does anything that follows there
 * themselves — sending a message, saving a reminder. The two that open nothing, the
 * media keys and the flashlight, are pressed and switched here.
 */
class QuickActionRunner(private val context: Context) {

    /** How long before a saved reminder the phone gives its nudge. */
    private val REMINDER_ALERT_MINUTES = 10

    /** What to say, and whether an app was actually opened. */
    data class Outcome(val line: String, val fired: Boolean)

    fun run(action: QuickAction): Outcome {
        var label: String? = null
        var launchPackage: String? = null
        if (action is QuickAction.OpenApp) {
            when (val match = AppMatcher.best(action.app, launcherApps())) {
                is AppMatcher.Match.Found -> {
                    label = match.app.label
                    launchPackage = match.app.packageName
                    HeylanaLog.state("action: open_app match=\"${match.app.label}\" package=${match.app.packageName} score=${match.score}")
                }
                is AppMatcher.Match.Ambiguous -> {
                    HeylanaLog.state("action: open_app ambiguous first=${match.first.packageName} second=${match.second.packageName}")
                    return Outcome(QuickText.ambiguous(match.first.label, match.second.label), fired = false)
                }
                AppMatcher.Match.None -> {
                    HeylanaLog.state("action: open_app no match")
                    return Outcome(QuickText.NO_APP, fired = false)
                }
            }
        }

        // A text to a name goes to that contact's number, found on the phone, once that is allowed.
        if (action is QuickAction.Message && action.number == null && action.name != null) {
            textContact(action, action.name)?.let { return it }
        }

        // A reminder is saved straight into the calendar once that is allowed; the
        // calendar's own screen is the fallback, and what is asked for the first time.
        if (action is QuickAction.Reminder) {
            saveReminder(action)?.let { return it }
        }
        return when (val effect = QuickIntents.effect(action, launchPackage)) {
            is QuickEffect.Launch -> launch(action, effect.intent, label)
            is QuickEffect.MediaKey -> mediaKey(action, effect.keyCode)
            is QuickEffect.Torch -> torch(action, effect.on)
        }
    }

    private fun launch(action: QuickAction, spec: IntentSpec, label: String?): Outcome {
        val intent = toIntent(spec) ?: return Outcome(QuickText.NO_APP, fired = false).also {
            HeylanaLog.state("action: no launch intent for package=${spec.launchPackage}")
        }
        return try {
            HeylanaLog.state("action: firing intent ${action.intent} android=${spec.action} package=${spec.targetPackage ?: "any"}")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            HeylanaLog.state("action: fired intent=${action.intent}")
            Outcome(QuickText.line(action, LocalTime.now(), label), fired = true)
        } catch (_: ActivityNotFoundException) {
            val fallback = spec.fallback
            if (fallback != null) {
                HeylanaLog.state("action: no app for intent=${action.intent}, trying fallback android=${fallback.action}")
                val web = launch(action, fallback, label)
                // In the browser instead of the app: say so.
                return if (web.fired && action is QuickAction.YoutubeSearch) web.copy(line = "YouTube isn't installed, so searching it in the browser.")
                else web
            }
            HeylanaLog.state("action: no app handles intent=${action.intent} package=${spec.targetPackage ?: "any"}")
            Outcome(QuickText.missingApp(action), fired = false)
        } catch (e: SecurityException) {
            HeylanaLog.state("action: refused by the system intent=${action.intent} error=${e::class.simpleName}")
            Outcome(QuickText.NOTHING_HANDLES, fired = false)
        }
    }

    /**
     * Writes the reminder into the calendar itself, so nothing is left for the user to
     * save. Null means it was not written — no permission yet (which is asked for, once),
     * no calendar to write to, or the write failed — and the calendar's own new-event
     * screen opens instead.
     */
    private fun saveReminder(action: QuickAction.Reminder): Outcome? {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_CALENDAR
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            HeylanaLog.state("action: reminder needs the calendar, asking")
            askForCalendar()
            return null
        }
        val now = LocalDateTime.now()
        val start = QuickIntents.reminderStart(action, now)
        val zone = java.time.ZoneId.systemDefault()
        val beginMs = start.atZone(zone).toInstant().toEpochMilli()
        return runCatching {
            val calendarId = writableCalendarId() ?: return null.also { HeylanaLog.state("action: no calendar to write to") }
            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Events.CALENDAR_ID, calendarId)
                put(android.provider.CalendarContract.Events.TITLE, action.text)
                put(android.provider.CalendarContract.Events.DTSTART, beginMs)
                put(android.provider.CalendarContract.Events.DTEND, beginMs + QuickIntents.REMINDER_MINUTES * 60_000L)
                put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            }
            val uri = context.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
                ?: return null.also { HeylanaLog.state("action: calendar refused the event") }
            val eventId = uri.lastPathSegment?.toLongOrNull()
            if (eventId != null) {
                // A reminder that does not remind is no use: ten minutes before, as a notification.
                runCatching {
                    context.contentResolver.insert(
                        android.provider.CalendarContract.Reminders.CONTENT_URI,
                        android.content.ContentValues().apply {
                            put(android.provider.CalendarContract.Reminders.EVENT_ID, eventId)
                            put(android.provider.CalendarContract.Reminders.MINUTES, REMINDER_ALERT_MINUTES)
                            put(android.provider.CalendarContract.Reminders.METHOD, android.provider.CalendarContract.Reminders.METHOD_ALERT)
                        }
                    )
                }
            }
            HeylanaLog.state("action: fired intent=reminder saved=true")
            Outcome(QuickText.reminderSavedLine(start, now.toLocalDate()), fired = true)
        }.getOrElse { error ->
            HeylanaLog.state("action: calendar write failed error=${error::class.simpleName}")
            null
        }
    }

    /**
     * "Text Ada": Ada's number from the phone's contacts, and Messages opened on her with the
     * words written. Null means carry on as before — Messages with the words, for the user to
     * pick her — because contacts are not allowed yet (asked for now, once), or nobody fits.
     * Two people who fit equally is a question, not a guess. Only counts are logged.
     */
    private fun textContact(action: QuickAction.Message, name: String): Outcome? {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            HeylanaLog.state("action: message to a name needs contacts, asking")
            runCatching {
                context.startActivity(
                    Intent(context, ContactsPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return null
        }
        val contacts = runCatching { phoneContacts() }.getOrElse {
            HeylanaLog.state("action: contacts read failed error=${it::class.simpleName}")
            return null
        }
        return when (val match = ContactMatcher.best(name, contacts)) {
            is ContactMatcher.Match.Found -> {
                HeylanaLog.state("action: message contact found of=${contacts.size} digits=${match.contact.number.count(Char::isDigit)}")
                val resolved = action.copy(number = match.contact.number, name = null)
                val outcome = launch(resolved, QuickIntents.spec(resolved), null)
                if (outcome.fired) outcome.copy(line = QuickText.messageTo(match.contact.name)) else outcome
            }
            is ContactMatcher.Match.Ambiguous -> {
                HeylanaLog.state("action: message contact ambiguous of=${contacts.size}")
                Outcome(QuickText.ambiguous(match.first, match.second), fired = false)
            }
            ContactMatcher.Match.None -> {
                HeylanaLog.state("action: message contact not found of=${contacts.size}")
                null
            }
        }
    }

    /** Every contact with a phone number: name and number, read here and kept nowhere. */
    private fun phoneContacts(): List<Contact> {
        val phone = android.provider.ContactsContract.CommonDataKinds.Phone::class.java
        val columns = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val out = ArrayList<Contact>()
        context.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns, null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)?.trim().orEmpty()
                val number = cursor.getString(1)?.trim().orEmpty()
                if (name.isNotEmpty() && number.any(Char::isDigit)) out += Contact(name, number)
            }
        }
        return out
    }

    /** The first visible calendar the phone will let Heylana write to, preferring the primary one. */
    private fun writableCalendarId(): Long? {
        val columns = arrayOf(
            android.provider.CalendarContract.Calendars._ID,
            android.provider.CalendarContract.Calendars.IS_PRIMARY,
            android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        context.contentResolver.query(
            android.provider.CalendarContract.Calendars.CONTENT_URI, columns,
            "${android.provider.CalendarContract.Calendars.VISIBLE} = 1", null, null
        )?.use { cursor ->
            var fallback: Long? = null
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val access = cursor.getInt(2)
                if (access < android.provider.CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                if (cursor.getInt(1) == 1) return id
                if (fallback == null) fallback = id
            }
            return fallback
        }
        return null
    }

    /** The one-shot prompt, the same way the microphone is asked for. */
    private fun askForCalendar() {
        runCatching {
            context.startActivity(
                Intent(context, CalendarPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** A press and release of the key, to whichever media session is playing. */
    private fun mediaKey(action: QuickAction, keyCode: Int): Outcome {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return Outcome(QuickText.NOTHING_HANDLES, fired = false)
        val playing = audio.isMusicActive
        // Nothing to pause: say so rather than "Paused." over silence.
        if (keyCode == QuickIntents.KEYCODE_MEDIA_PAUSE && !playing) {
            HeylanaLog.state("action: nothing playing to pause")
            return Outcome(QuickText.NO_MEDIA, fired = false)
        }
        HeylanaLog.state("action: firing intent ${action.intent} key=$keyCode music_active=$playing")
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        HeylanaLog.state("action: fired intent=${action.intent}")
        return Outcome(QuickText.line(action, LocalTime.now()), fired = true)
    }

    /** The back camera's torch, or the first camera that has one. */
    private fun torch(action: QuickAction, on: Boolean): Outcome {
        val cameras = context.getSystemService(CameraManager::class.java)
            ?: return Outcome(QuickText.NO_FLASHLIGHT, fired = false)
        return try {
            val id = cameras.cameraIdList.firstOrNull { id ->
                cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Outcome(QuickText.NO_FLASHLIGHT, fired = false).also {
                HeylanaLog.state("action: no camera with a flash")
            }
            HeylanaLog.state("action: firing intent ${action.intent} torch=${if (on) "on" else "off"}")
            cameras.setTorchMode(id, on)
            HeylanaLog.state("action: fired intent=${action.intent}")
            Outcome(QuickText.line(action, LocalTime.now()), fired = true)
        } catch (e: CameraAccessException) {
            HeylanaLog.state("action: torch refused reason=${e.reason}")
            Outcome(QuickText.FLASHLIGHT_BUSY, fired = false)
        } catch (e: IllegalArgumentException) {
            HeylanaLog.state("action: torch refused error=${e::class.simpleName}")
            Outcome(QuickText.NO_FLASHLIGHT, fired = false)
        }
    }

    fun toIntent(spec: IntentSpec): Intent? {
        spec.launchPackage?.let { return context.packageManager.getLaunchIntentForPackage(it) }
        val intent = Intent(spec.action)
        spec.data?.let { intent.data = Uri.parse(it) }
        spec.targetPackage?.let { intent.setPackage(it) }
        spec.type?.let { intent.type = it }
        spec.extras.forEach { (key, value) ->
            when (value) {
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
            }
        }
        return intent
    }

    /** Apps with a launcher icon; visible through the manifest's launcher query. */
    private fun launcherApps(): List<LauncherApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { LauncherApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
    }
}
