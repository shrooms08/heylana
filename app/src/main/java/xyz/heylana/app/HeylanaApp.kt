package xyz.heylana.app

import android.app.Application
import xyz.heylana.app.ops.CrashReports

/** The process: the only thing it does before anything else is start crash reports, if they are on. */
class HeylanaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReports.start(this)
    }
}
