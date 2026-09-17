package xyz.heylana.app

import android.app.Application
import xyz.heylana.app.ops.CrashReports
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.ui.GlassSpec

/** The process: the only thing it does before anything else is start crash reports, if they are on. */
class HeylanaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReports.start(this)
        // Every glass surface reads this when it draws, the overlay included.
        GlassSpec.darkerGlass = HeylanaSettings.get(this).darkerGlass
    }
}
