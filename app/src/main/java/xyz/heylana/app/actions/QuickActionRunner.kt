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
import java.time.LocalTime

/**
 * Hands an allowed action to the phone. The intent starts a new task, so the Clock,
 * the app, the browser, Maps, the dialer, Messages, the calendar, the camera or
 * Settings comes to the front, and the user does anything that follows there
 * themselves — sending a message, saving a reminder. The two that open nothing, the
 * media keys and the flashlight, are pressed and switched here.
 */
class QuickActionRunner(private val context: Context) {

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
