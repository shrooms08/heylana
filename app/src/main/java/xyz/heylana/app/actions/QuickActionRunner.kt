package xyz.heylana.app.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import xyz.heylana.app.HeylanaLog
import java.time.LocalTime

/**
 * Hands an allowed action to the phone. The intent starts a new task, so the Clock,
 * the app, the browser, Maps or the dialer comes to the front, and the user does
 * anything that follows there themselves.
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

        val spec = QuickIntents.spec(action, launchPackage)
        val intent = toIntent(spec) ?: return Outcome(QuickText.NO_APP, fired = false).also {
            HeylanaLog.state("action: no launch intent for package=$launchPackage")
        }
        return try {
            HeylanaLog.state("action: firing intent ${action.intent} android=${spec.action}")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            HeylanaLog.state("action: fired intent=${action.intent}")
            Outcome(QuickText.line(action, LocalTime.now(), label), fired = true)
        } catch (_: ActivityNotFoundException) {
            HeylanaLog.state("action: no app handles intent=${action.intent}")
            Outcome(QuickText.NOTHING_HANDLES, fired = false)
        } catch (e: SecurityException) {
            HeylanaLog.state("action: refused by the system intent=${action.intent} error=${e::class.simpleName}")
            Outcome(QuickText.NOTHING_HANDLES, fired = false)
        }
    }

    fun toIntent(spec: IntentSpec): Intent? {
        spec.launchPackage?.let { return context.packageManager.getLaunchIntentForPackage(it) }
        val intent = Intent(spec.action)
        spec.data?.let { intent.data = Uri.parse(it) }
        spec.extras.forEach { (key, value) ->
            when (value) {
                is Int -> intent.putExtra(key, value)
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
