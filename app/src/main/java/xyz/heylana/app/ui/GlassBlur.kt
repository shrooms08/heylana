package xyz.heylana.app.ui

import android.content.Context
import android.os.Build
import android.view.WindowManager

/**
 * Cross-window blur, where the platform offers it.
 *
 * Blurring what is behind an overlay window arrived in Android 12, and even
 * there the user or the device can switch it off — a battery saver, a low-end
 * device, or the developer option. So this is asked at the moment a window is
 * shown, never assumed, and the answer decides which glass fill is used.
 */
object GlassBlur {

    /** True when this device is actually willing to blur right now. */
    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        return wm.isCrossWindowBlurEnabled
    }

    /**
     * Asks the window to blur what is behind it, and reports whether it could.
     * The caller uses the answer to pick the matching glass fill.
     */
    fun apply(context: Context, params: WindowManager.LayoutParams): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (!isAvailable(context)) return false
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        params.blurBehindRadius = HeylanaTokens.dpInt(
            context, if (GlassSpec.TINTED_EXTRAS) HeylanaTokens.BLUR_DP else GlassSpec.BLUR_BEHIND_DP
        )
        return true
    }

    /** Drops the blur again, for when the window goes back to being passive. */
    fun clear(params: WindowManager.LayoutParams) {
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.blurBehindRadius = 0
    }
}
