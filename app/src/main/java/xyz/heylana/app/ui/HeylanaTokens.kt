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

    /**
     * Smoked glass. Light text on a white-tinted pane disappears over a bright
     * page, so every sheet starts with a dark base and the white fill goes on
     * top of that.
     *
     * 68 percent, not the 40 the design called for, and the specular and sheen
     * are dimmer than asked too. The two halves of the brief cannot both hold:
     * a white 25 percent specular over a 40 percent base puts the body text at
     * 2.46:1 over a white page, well under the 4.5:1 the same brief requires.
     * These are the brightest values that still clear it, worst case, with the
     * specular and the resting sheen stacked on the same pixel. The debug
     * preview measures it on every run.
     */
    val glassBase = withAlpha(Color.BLACK, 0.68f)

    /**
     * The white fill above the base is a vertical gradient, brighter at the top
     * where light would catch it.
     */
    val glassFillTop = withAlpha(Color.WHITE, 0.14f)
    val glassFillBottom = withAlpha(Color.WHITE, 0.04f)

    /** The rim runs bright at the top-left down to almost nothing bottom-right. */
    val glassRimBright = withAlpha(Color.WHITE, 0.55f)
    val glassRimDim = withAlpha(Color.WHITE, 0.08f)

    /** The faked refraction edge: a dark line just inside the rim. */
    val glassLensLine = withAlpha(Color.BLACK, 0.25f)

    /** The soft blob of light on the pane, as if a lamp were off to one side. */
    val glassSpecular = withAlpha(Color.WHITE, 0.10f)

    /** The sweep that crosses a pane once as it appears. */
    val glassSheen = withAlpha(Color.WHITE, 0.08f)

    /** What lifts the pane off the screen behind it. */
    val glassShadow = withAlpha(Color.BLACK, 0.35f)

    /** The question field: its own smoked base, then a lighter fill on top. */
    val inputBase = withAlpha(Color.BLACK, 0.30f)
    val inputFill = withAlpha(Color.WHITE, 0.08f)
    val inputBorder = withAlpha(Color.WHITE, 0.14f)

    /** The highlight along the top edge of a primary button. */
    val pillHighlight = withAlpha(Color.WHITE, 0.35f)

    /** The refraction band that sits inside the glass near its top-left corner. */
    val purpleBand = withAlpha(accent, 0.20f)

    /** The same band at full strength: what makes a button read as primary. */
    val bandPrimary = withAlpha(accent, 0.50f)

    val discBorder = withAlpha(Color.WHITE, 0.12f)

    /** The aurora that drifts inside the thinking capsule. */
    val auroraStops = intArrayOf(
        Color.parseColor("#8F5BFF"),
        Color.parseColor("#6B3BFF"),
        Color.parseColor("#35E0E8"),
        Color.parseColor("#FF9A4D")
    )

    /**
     * The dim over the app behind the message box. Enough to hold the pane away
     * from a bright page without reading as a modal.
     */
    val scrim = withAlpha(Color.BLACK, 0.35f)

    /** How far the mark is knocked back when the buddy is resting. */
    const val MARK_DULLED = 0.55f
    const val MARK_ACTIVE = 1.0f

    // -------------------------------------------------------------- shape

    /** Blur radius behind glass surfaces. */
    const val BLUR_DP = 40f

    /** Rim thickness: heavier along the top, tapering everywhere else. */
    const val GLASS_RIM_TOP_DP = 2f
    const val GLASS_RIM_DP = 1f
    const val GLASS_LENS_DP = 1f
    const val INPUT_BORDER_DP = 1f

    /** The task HUD's progress rail. */
    const val RAIL_DP = 2f

    /** The specular blob, as fractions of the pane. */
    const val SPECULAR_WIDTH = 0.60f
    const val SPECULAR_HEIGHT = 0.35f
    const val SPECULAR_X = 0.30f
    const val SPECULAR_Y = 0.10f

    /** The sheen band, as a fraction of the pane, and where it comes to rest. */
    const val SHEEN_WIDTH = 0.40f
    const val SHEEN_ANGLE_DEG = 20f
    const val SHEEN_REST = 0.35f
    const val SHEEN_SWEEP_MS = 600L

    /** Shadow under a pane. */
    const val GLASS_SHADOW_DP = 24f
    const val GLASS_SHADOW_DY_DP = 8f

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

    /** How long the mark takes to come apart into the ring, and back. */
    const val UNWIND_MS = 260L

    /** How far the mark leans toward what it is pointing at. */
    const val POINT_LEAN = 0.18f
    const val AURORA_DRIFT_MS = 6000L
    const val RIPPLE_MS = 900L

    /**
     * The buddy's flight between its dock and the compose position, as a spring.
     * Medium-low stiffness with a little bounce left in, so it overshoots once
     * and settles rather than arriving dead.
     */
    const val SPRING_STIFFNESS = 380f
    const val SPRING_DAMPING = 0.72f

    /** The box growing out of the disc. */
    const val GROW_MS = 360L

    /** Glass fading in behind the buddy, or out ahead of it. */
    const val FADE_MS = 180L

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
