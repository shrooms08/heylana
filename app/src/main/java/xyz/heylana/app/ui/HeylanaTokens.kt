package xyz.heylana.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import xyz.heylana.app.R

/**
 * The single source of truth for how Heylana looks.
 *
 * Nothing that draws should carry its own colour, radius, spacing or duration —
 * if a value is needed twice, or is a design decision at all, it belongs here.
 */
object HeylanaTokens {

    // ------------------------------------------------------------- colour

    val bg = Color.BLACK
    val textPrimary = Color.parseColor("#F4F3F8")
    val textSecondary = Color.parseColor("#8B89A0")
    val accent = Color.parseColor("#8F5BFF")
    val success = Color.parseColor("#37D9A6")
    val error = Color.parseColor("#FF5C71")

    /** The bloom behind an active buddy. */
    val glow = withAlpha(Color.parseColor("#B98BFF"), 0.55f)

    /** Liquid glass, when the platform is blurring what is behind it. */
    val glassFill = withAlpha(Color.WHITE, 0.06f)

    /** The same glass when blur is unavailable and the fill has to carry it alone. */
    val glassFillNoBlur = withAlpha(Color.WHITE, 0.14f)

    val glassBorder = withAlpha(Color.WHITE, 0.09f)

    /** Bevel: light down the top and left edges, dark up the bottom and right. */
    val bevelLight = withAlpha(Color.WHITE, 0.35f)
    val bevelDark = withAlpha(Color.BLACK, 0.55f)

    /** The refraction band that sits inside the glass near its top-left corner. */
    val purpleBand = withAlpha(accent, 0.20f)

    /** The same band at full strength: what makes a button read as primary. */
    val bandPrimary = withAlpha(accent, 0.50f)

    val discBorder = withAlpha(Color.WHITE, 0.12f)

    /** The dim laid over the app behind the message box. */
    val scrim = withAlpha(Color.BLACK, 0.45f)

    /** How far the mark is knocked back when the buddy is resting. */
    const val MARK_DULLED = 0.55f
    const val MARK_ACTIVE = 1.0f

    // -------------------------------------------------------------- shape

    /** Blur radius behind glass surfaces. */
    const val BLUR_DP = 24f

    const val RADIUS_SM_DP = 8f
    const val RADIUS_MD_DP = 14f
    const val RADIUS_STRIP_DP = 20f
    const val RADIUS_CARD_DP = 28f

    /** Chips and primary buttons: a full pill, whatever the height. */
    const val RADIUS_FULL_DP = 999f

    const val SPACE_1_DP = 4f
    const val SPACE_2_DP = 8f
    const val SPACE_3_DP = 12f
    const val SPACE_4_DP = 16f
    const val SPACE_5_DP = 24f
    const val SPACE_6_DP = 32f
    const val SPACE_7_DP = 48f

    /** The buddy disc itself. */
    const val DISC_DP = 88f

    /**
     * Room around the disc for its bloom to spill into. The buddy's view is this
     * much bigger than the disc on every side, since the window is wrapped
     * tightly around the view and would otherwise clip the glow off.
     */
    const val DISC_BLEED_DP = 20f

    /** How much of the disc the mark fills. */
    const val MARK_FRACTION = 0.62f

    /** How far the resting buddy is held off the screen edge. */
    const val DOCK_INSET_DP = 8f

    /** The bloom behind an active disc. */
    const val GLOW_BLUR_DP = 40f

    // ------------------------------------------------------------- motion

    /** The resting breath: 100 to 105.5 percent and back. */
    const val BREATHE_MS = 3200L
    const val BREATHE_SCALE = 1.055f

    const val RING_SPIN_MS = 1200L
    const val AURORA_DRIFT_MS = 6000L
    const val RIPPLE_MS = 900L

    /** The buddy's flight between its dock and the compose position. */
    const val SPRING_MS = 350L

    // --------------------------------------------------------------- type

    const val DISPLAY_SP = 32f
    const val TITLE_SP = 22f
    const val BODY_SP = 16f
    const val LABEL_SP = 13f

    const val WEIGHT_LIGHT = 300
    const val WEIGHT_REGULAR = 400
    const val WEIGHT_MEDIUM = 500

    /** Label tracking, as a fraction of the em, for TextView.letterSpacing. */
    const val LABEL_TRACKING_EM = 0.08f

    /**
     * Outfit at a given weight, or the platform's light sans if the font
     * resource cannot be loaded on this device.
     */
    fun typeface(context: Context, weight: Int): Typeface {
        val family = runCatching { context.resources.getFont(R.font.outfit) }.getOrNull()
            ?: return Typeface.create("sans-serif-light", Typeface.NORMAL)
        return Typeface.create(family, weight, false)
    }

    // ------------------------------------------------------------ helpers

    fun dp(context: Context, value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    )

    fun dpInt(context: Context, value: Float): Int = dp(context, value).toInt()

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255f).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
