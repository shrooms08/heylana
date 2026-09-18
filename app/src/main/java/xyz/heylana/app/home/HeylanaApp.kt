package xyz.heylana.app.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.screen.HeylanaAccessibilityService
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.ui.app.rememberBackdrop
import xyz.heylana.app.ui.theme.GlassMode
import xyz.heylana.app.ui.theme.HeylanaTheme
import xyz.heylana.app.wallet.SeedVault

/**
 * The whole app in one activity: first run (sign in, name, permissions) or, for a
 * returning user, Home — and from Home the voice screen, the menu and the screens it opens.
 */
@Composable
fun HeylanaApp(activity: ComponentActivity, seedVault: SeedVault, forcedScreen: String? = null) {
    val settings = remember { HeylanaSettings.get(activity) }
    var mode by remember { mutableStateOf(glassModeOf(settings)) }
    var screen by rememberSaveable {
        mutableStateOf(
            forcedScreen?.let { name -> Screen.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                ?: AppRoute.start(settings.firstRunDone, settings.callMe.isNotBlank())
        )
    }
    val backdrop = rememberBackdrop()

    // The system's answer, read again every time the app comes back to the front.
    val permissions = remember { PermissionsModel { checkPermissions(activity) } }
    var permissionState by remember { mutableStateOf(permissions.state) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionState = permissions.refresh()
                HeylanaLog.state("app: permissions ${PermissionLog.describe(permissionState)}")
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionState = permissions.refresh()
        HeylanaLog.state("app: permission answered granted=$granted")
    }

    BackHandler(enabled = AppRoute.back(screen, settings.firstRunDone) != null) {
        AppRoute.back(screen, settings.firstRunDone)?.let { screen = it }
    }

    // Dark status and navigation icons on the light page, white ones on black.
    val view = LocalView.current
    SideEffect {
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = mode == GlassMode.LIGHT
            isAppearanceLightNavigationBars = mode == GlassMode.LIGHT
        }
    }

    HeylanaTheme(mode) {
        when (screen) {
            Screen.SIGN_IN -> SignInScreen(backdrop, settings, seedVault) { screen = AppRoute.afterName() }
            Screen.PERMISSIONS -> PermissionsScreen(
                backdrop,
                PermissionsModel.rowsFor(permissionState),
                permissions.required,
                onRow = { target -> openPermission(activity, target, permissionState) { askPermission.launch(it) } },
                onDone = {
                    settings.firstRunDone = true
                    screen = AppRoute.afterPermissions()
                }
            )
            else -> AppScreens(
                screen = screen,
                backdrop = backdrop,
                settings = settings,
                activity = activity,
                seedVault = seedVault,
                onScreen = { screen = it },
                onGlassMode = { next ->
                    mode = next
                    settings.glassMode = if (next == GlassMode.LIGHT) HeylanaSettings.GLASS_LIGHT else HeylanaSettings.GLASS_DARK
                }
            )
        }
    }
}

fun glassModeOf(settings: HeylanaSettings): GlassMode =
    if (settings.glassMode == HeylanaSettings.GLASS_LIGHT) GlassMode.LIGHT else GlassMode.DARK

/** Counts and flags only, for the log. */
object PermissionLog {
    fun describe(state: PermissionState): String =
        "overlay=${state.overlay} screen_reading=${state.screenReading} notifications=${state.notifications} microphone=${state.microphone}"
}

fun checkPermissions(context: Context): PermissionState = PermissionState(
    overlay = Settings.canDrawOverlays(context),
    // Only when the service is really running, not merely listed: after a crash Android
    // keeps it in the list and stops binding it.
    screenReading = HeylanaAccessibilityService.isRunning(context),
    notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
    microphone = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
)

/**
 * The right system page for a row. Overlay and screen reading are Settings pages; the
 * notification and microphone prompts come first, and once Android will no longer ask
 * (refused twice), the app's own page in Settings instead.
 */
fun openPermission(activity: ComponentActivity, target: PermissionTarget, state: PermissionState, ask: (String) -> Unit) {
    HeylanaLog.state("app: permission row target=${target.name.lowercase()}")
    when (target) {
        PermissionTarget.OVERLAY -> activity.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
        )
        PermissionTarget.SCREEN_READING -> activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        PermissionTarget.NOTIFICATIONS -> when {
            state.notifications || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> activity.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            )
            else -> askOrOpen(activity, Manifest.permission.POST_NOTIFICATIONS, ask)
        }
        PermissionTarget.MICROPHONE -> if (state.microphone) appDetails(activity) else askOrOpen(activity, Manifest.permission.RECORD_AUDIO, ask)
    }
}

private fun askOrOpen(activity: ComponentActivity, permission: String, ask: (String) -> Unit) {
    val prefs = activity.getSharedPreferences("app_permissions_asked", Context.MODE_PRIVATE)
    val askedBefore = prefs.getBoolean(permission, false)
    if (askedBefore && !activity.shouldShowRequestPermissionRationale(permission)) {
        appDetails(activity)
    } else {
        prefs.edit().putBoolean(permission, true).apply()
        ask(permission)
    }
}

private fun appDetails(activity: ComponentActivity) = activity.startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
)
