package xyz.heylana.app.ops

import android.app.Application
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.android.core.SentryAndroid
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.HeylanaLog

/**
 * Crash reports to Sentry, and nothing else about the user.
 *
 * On only when a DSN was built in, and only in release builds unless the build
 * says otherwise. No personal data, no screenshots, no view hierarchy (it would
 * hold the words on screen), no tap breadcrumbs, no network or system breadcrumbs,
 * no performance tracing. Every message, exception text and breadcrumb that does
 * go is passed through [ReportScrub], which takes out Solana addresses and anything
 * that looks like a key.
 */
object CrashReports {

    fun enabled(dsn: String, debugBuild: Boolean, inDebug: Boolean): Boolean =
        dsn.isNotBlank() && (!debugBuild || inDebug)

    /** The breadcrumbs kept: which screen came and went, and the app's own lifecycle. Nothing read or typed. */
    private val KEPT_BREADCRUMBS = setOf("navigation", "ui.lifecycle", "app.lifecycle")

    fun start(app: Application) {
        if (!enabled(BuildConfig.SENTRY_DSN, BuildConfig.DEBUG, BuildConfig.SENTRY_IN_DEBUG)) return
        SentryAndroid.init(app) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.release = "xyz.heylana.app@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
            options.isSendDefaultPii = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableUserInteractionTracing = false
            options.isEnableNetworkEventBreadcrumbs = false
            options.isEnableSystemEventBreadcrumbs = false
            options.isEnableAutoActivityLifecycleTracing = false
            options.tracesSampleRate = null
            options.setBeforeBreadcrumb { breadcrumb, _ -> cleanBreadcrumb(breadcrumb) }
            options.setBeforeSend { event, _ -> cleanEvent(event) }
        }
        HeylanaLog.state("sentry: started environment=${if (BuildConfig.DEBUG) "debug" else "release"}")
    }

    private fun cleanBreadcrumb(breadcrumb: Breadcrumb): Breadcrumb? {
        if (breadcrumb.category !in KEPT_BREADCRUMBS) return null
        breadcrumb.message = ReportScrub.text(breadcrumb.message)
        return breadcrumb
    }

    private fun cleanEvent(event: SentryEvent): SentryEvent {
        event.user = null
        event.serverName = null
        event.request = null
        event.message?.let { message ->
            message.message = ReportScrub.text(message.message)
            message.formatted = ReportScrub.text(message.formatted)
            message.params = message.params?.map { ReportScrub.text(it) ?: "" }
        }
        event.exceptions?.forEach { it.value = ReportScrub.text(it.value) }
        event.breadcrumbs = event.breadcrumbs
            ?.filter { it.category in KEPT_BREADCRUMBS }
            ?.onEach { it.message = ReportScrub.text(it.message) }
        event.extras?.keys?.toList()?.forEach { key -> event.removeExtra(key) }
        return event
    }
}

/** What is taken out of every report before it leaves the phone. */
object ReportScrub {

    /** A Solana address (32 to 44 base58 characters) or signature (87 or 88). */
    private val BASE58_RUN = Regex("(?<![1-9A-HJ-NP-Za-km-z])[1-9A-HJ-NP-Za-km-z]{32,88}(?![1-9A-HJ-NP-Za-km-z])")

    /** An Anthropic, Deepgram or similar key, or a session token. */
    private val KEY = Regex("(sk-ant-[A-Za-z0-9_-]+|Bearer\\s+\\S+)")

    fun text(value: String?): String? = value
        ?.replace(KEY, "[key]")
        ?.replace(BASE58_RUN, "[address]")
}
