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

    /**
     * The palette (polish-4, after the sibling product Glance's colour rules): one blue
     * accent, three greys for words, and green, orange and red for status only. The
     * Compose screens read the same values through `ui/theme/Theme.kt`.
     */
    val bg = Color.parseColor("#0A0A0E")
    val textPrimary = Color.parseColor("#F4F4F6")
    /** Second-rank words: summaries, chip labels. */
    val text2 = Color.parseColor("#C8C8D2")
    /** Labels, hints, detail lines. */
    val textSecondary = Color.parseColor("#8B8B96")
    val accent = Color.parseColor("#5B8CFF")
    val accentHover = Color.parseColor("#7BA3FF")
    /** Words on an accent fill: never white. */
    val onAccent = Color.parseColor("#08122C")
    /** Links and accent words on dark. */
    val accentText = Color.parseColor("#93B3FF")
    /** Soft accent backgrounds: a selected row. */
    val accentSoft = withAlpha(accent, 0.14f)
    val success = Color.parseColor("#6FE39F")
    val warn = Color.parseColor("#FF9F45")
    /** The badge fill behind a warning word: "Devnet" on Home and on a send's card. */
    val warnSoft = withAlpha(warn, 0.14f)
    val error = Color.parseColor("#FF7E6E")

    /** Hairlines and edges, glass buttons, and the solid border of chips; the unselected chip's fill. */
    val borderHairline = withAlpha(Color.WHITE, 0.09f)
    val borderStrong = withAlpha(Color.WHITE, 0.13f)
    val borderSolid = Color.parseColor("#2F2F3A")
    val chipFill = Color.parseColor("#1D1D25")

    /** The bloom behind an active buddy. */
    val glow = withAlpha(accentHover, 0.55f)

    /**
     * The orb's dots: the ink, shaded by depth as the library does, with a faint accent
     * cast on the outermost ring only ([ORB_CAST], from [ORB_CAST_FROM] of the way out).
     */
    val orbInk = textPrimary
    const val ORB_CAST = 0.30f
    const val ORB_CAST_FROM = 0.78f

    /**
     * The two fixed glows under the app's screens (never the overlay): the accent at 8%
     * centred on the top-right corner, reaching 60% of the width and 40% of the height,
     * and at 5% on the bottom-left, each fading out so gently the page reads blue-black.
     */
    const val APP_GLOW_TOP_ALPHA = 0.08f
    const val APP_GLOW_BOTTOM_ALPHA = 0.05f
    const val APP_GLOW_TOP_RX = 0.60f
    const val APP_GLOW_TOP_RY = 0.40f
    const val APP_GLOW_BOTTOM_RX = 0.60f
    const val APP_GLOW_BOTTOM_RY = 0.35f

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
    val accentBand = withAlpha(accent, 0.20f)

    /** The same band at full strength: what makes a button read as primary. */
    val bandPrimary = withAlpha(accent, 0.50f)

    val discBorder = withAlpha(Color.WHITE, 0.12f)

    /** The aurora that drifts inside the thinking capsule. */
    val auroraStops = intArrayOf(accent, accentHover, accentText, accent)

    /**
     * The dim over the app behind the message box. Enough to hold the pane away
     * from a bright page without reading as a modal.
     */
    /** The pointer turns this colour for a moment when the user does the thing. */
    val ack = success

    val scrim = withAlpha(Color.BLACK, 0.35f)

    /**
     * The disc is a lens rather than a sheet: less smoke than a panel, so a bright
     * page reads through it, and a fainter band, so it is glass and not a purple button.
     */
    val discBase = withAlpha(Color.BLACK, 0.5f)
    val discBand = withAlpha(accent, 0.10f)

    /** The chromatic rim: three thin strokes at low alpha. */
    val fringeRed = withAlpha(Color.parseColor("#FF4D6D"), 0.32f)
    val fringeGreen = withAlpha(Color.parseColor("#4DFFB0"), 0.26f)
    val fringeBlue = withAlpha(Color.parseColor("#4D8DFF"), 0.32f)

    /** How far the mark is knocked back when the buddy is resting. */
    const val MARK_DULLED = 0.55f
    const val MARK_ACTIVE = 1.0f

    // -------------------------------------------------------------- shape

    /** Blur radius behind glass surfaces. */
    const val BLUR_DP = 40f

    /** Rim thickness: heavier along the top, tapering everywhere else. */
    /** How far down the top rim stays at full strength before it fades out. */
    const val RIM_TOP_HOLD = 0.35f
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

    /** The buddy disc, docked at the screen edge. */
    const val DISC_DP = 64f

    /** The disc once it has flown to the top centre and the box is open. */
    const val DISC_OPEN_DP = 80f

    /**
     * Room around the disc for its bloom, as a share of the disc: the buddy's view
     * is this much bigger on every side, since the window is wrapped tightly around
     * the view and would otherwise clip the glow off. (20dp around the old 88dp.)
     */
    const val DISC_BLEED_RATIO = 20f / 88f

    /** How much of the disc the mark fills. */
    const val MARK_FRACTION = 0.62f

    /** How far the visible resting disc is held off the screen edge, as a share of the disc: 6dp at 64. */
    const val DOCK_INSET_RATIO = 6f / 64f

    /** The bloom behind an active disc, as a share of the disc. (40dp at 88.) */
    const val GLOW_BLUR_RATIO = 40f / 88f

    /** The bleed around a disc of [discDp]. */
    fun discBleedDp(discDp: Float): Float = discDp * DISC_BLEED_RATIO

    /** The whole buddy view for a disc of [discDp]: the disc and its bleed on both sides. */
    fun discViewDp(discDp: Float): Float = discDp + 2 * discBleedDp(discDp)

    // ------------------------------------------------------------- motion

    /** The resting breath: 100 to 105.5 percent and back. */
    const val BREATHE_MS = 3200L
    const val BREATHE_SCALE = 1.055f


    /** How far each fringe stroke sits off the rim, and how thick it is. */
    const val FRINGE_OFFSET_DP = 0.6f
    const val FRINGE_STROKE_DP = 1f

    /** The RGB split on the mark at full speed, and the speed that counts as full. */
    const val MOTION_SPLIT_MAX_DP = 3f
    const val MOTION_SPLIT_FULL_DP_PER_S = 2500f

    /** The disc face's own aurora under the lens: resting, and awake. */
    const val FACE_AURORA_IDLE = 0.35f
    const val FACE_AURORA_ACTIVE = 0.5f

    /** How much of the face, from the centre out, the aurora leaves clear. */
    const val FACE_AURORA_CLEAR = 0.55f

    /** How bright the face's lighting bands are. */
    const val FACE_LIGHT = 1f

    /** Liquid edges on panels and pills: how far in the lens reaches, and the lit top band. */
    const val EDGE_LENS_RIM_DP = 24f
    const val EDGE_LENS_TOP_DP = 12f

    /** How strongly a panel's own fill and band show through its edge lens, and its light. */
    const val EDGE_LAYER_ALPHA = 0.9f
    const val EDGE_LIGHT = 0.6f

    /** How long the face's aurora takes to turn once. */
    const val FACE_AURORA_TURN_MS = 12_000L

    /** How long the mark takes to dissolve into the orb, and to reassemble from it. */
    const val ORB_DISSOLVE_MS = 300L

    /** Start buddy: the disc pops out from its edge — this long, overshooting this much, from this small. */
    const val POP_IN_MS = 420L
    const val POP_IN_OVERSHOOT = 1.6f
    const val POP_IN_FROM_SCALE = 0.4f

    /** How much of the disc the orb fills. */
    const val ORB_FRACTION = 0.8f

    /** How far a loud voice, heard or spoken, swells the orb. */
    const val ORB_LEVEL_SWELL = 0.12f

    /** How far the mark leans toward what it is pointing at. */
    const val POINT_LEAN = 0.18f
    const val AURORA_DRIFT_MS = 6000L

    /**
     * The buddy's flight between its dock and the compose position, as a spring.
     * Medium-low stiffness with a little bounce left in, so it overshoots once
     * and settles rather than arriving dead.
     */
    const val SPRING_STIFFNESS = 380f
    const val SPRING_DAMPING = 0.72f

    /**
     * The glide: the snap to an edge after a drag, and the flight home when the box
     * closes. A soft spring that barely overshoots, so the disc glides to rest; a fling
     * carries its speed in (capped at [GLIDE_MAX_START_DP_PER_S]). The flight to the top
     * centre keeps [SPRING_STIFFNESS].
     */
    const val GLIDE_STIFFNESS = 150f
    const val GLIDE_DAMPING = 0.85f
    const val GLIDE_MAX_START_DP_PER_S = 3000f

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

    /** After a source chip's title: it opens a page outside Heylana. */
    const val SOURCE_ARROW = "↗"

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
