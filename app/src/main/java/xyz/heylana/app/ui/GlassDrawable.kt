package xyz.heylana.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.hypot

/**
 * One liquid-glass recipe, used by every glass surface: the message box, the
 * reply strip, the task HUD, their buttons and the buddy's disc.
 *
 * **Clear glass** ([GlassSpec], design-2d) is what draws: no colour anywhere, a 12%
 * black smoke fill (30% more black with "Darker glass"), the spec's band, gradient,
 * rim, lens line, inner shadow, specular and hairline — per pixel through [ClearGlass]
 * on API 33+ hardware canvases, as gradients below that — and a 7dp shadow cast
 * outside the shape only. Chips are flat: black 27%, or white 94% when selected.
 *
 * The tinted recipe below (design-2c) is kept behind [GlassSpec.TINTED_EXTRAS], off:
 *
 * Bottom to top:
 *  1. a smoked base, so light text has somewhere to sit over a bright page,
 *  2. a vertical white fill, brighter at the top where light would catch it,
 *  3. a purple refraction band bent along the top-left corner curve,
 *  4. a specular blob, as if a lamp sat off to the upper left,
 *  5. a sheen band that sweeps across once when the pane appears and then rests,
 *  6. a rim, heavier along the top and running bright at the top-left to almost
 *     nothing at the bottom-right,
 *  7. a lens line just inside the rim — the faked refraction edge.
 *
 * The outer shadow is drawn here too, but only when the view has left room for
 * it: a drawable is clipped to its view, so the pane is inset by the blur width
 * and the shadow is painted into the margin that leaves. Elevation is no use on
 * a translucent sheet — the platform paints a pale rectangle across it. It is a
 * shadow layer on the base, which renders on the GPU, so the pane can too.
 *
 * On API 33+ hardware canvases the edges are liquid ([LiquidGlass]): the rim, the
 * lens line and a 12dp band along the top bend the surface's own fill and band
 * through the lens and catch the light, with a chromatic fringe on the rim. The
 * screen behind is never sampled; blur behind stays FLAG_BLUR_BEHIND.
 *
 * [Kind] picks which of those a surface wants. A question field takes only the
 * base, fill, rim and lens line; a primary button is the same glass with the
 * band at full strength.
 */
class GlassDrawable(
    context: Context,
    private val cornerRadiusDp: Float,
    private val blurBehind: Boolean,
    private val kind: Kind = Kind.PANEL,
    /** The refraction band. [HeylanaTokens.bandPrimary] makes a button primary. */
    private val bandColor: Int = HeylanaTokens.purpleBand,
    /**
     * Draws a shadow under the pane, inset far enough into the view that it is
     * not clipped. The view must carry [shadowPadding] on every side. The shadow is
     * a GPU shadow layer, so the view stays hardware rendered.
     */
    private val withShadow: Boolean = false,
    /**
     * Whether this surface sweeps a sheen. Off for small buttons, where the band
     * is narrower than its own rotation pivot and leaves a hard corner.
     */
    private val withSheen: Boolean = kind == Kind.PANEL,
    /** The smoked base. The disc's face is a lens, so it smokes less than a panel. */
    private val baseColor: Int = if (kind == Kind.INPUT) HeylanaTokens.inputBase else HeylanaTokens.glassBase,
    /** Liquid edges on the rim and top band. Off for fields, and for the disc, whose whole face is a lens. */
    private val liquidEdges: Boolean = kind != Kind.INPUT,
    /** A chip that is the thing to press: white 94% with dark text, instead of black 27%. */
    private val selected: Boolean = false,
    /**
     * Whether the view left [shadowPadding] around the pane for its shadow. The disc
     * draws into its own bloom room instead, so its body is the whole bounds.
     */
    private val shadowInView: Boolean = true
) : Drawable() {

    /** Where the purple streak is, 0 to 1 through a pass. Set every frame by the owner. */
    var streakPhase: Float = 0f

    /** How strong the purple streak is: 0 is off. Pills never draw one. */
    var streakStrength: Float = 0f

    /** The streak's width and breath in dp: 30 and 8 on panels; the disc scales 18 with its size. */
    var streakWidthDp: Float = GlassSpec.STREAK_WIDTH_DP
    var streakBreathDp: Float = GlassSpec.STREAK_BREATH_DP

    /** The rim beam: 0 to 1 through a lap, and how strong it is (0 off). Set every frame by the owner. */
    var beamPhase: Float = 0f
    var beamStrength: Float = 0f

    /** How wide the beam's glow is across the rim, dp. */
    var beamGlowDp: Float = BorderBeam.PANEL_GLOW_DP

    private val beamShader: Any? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) BeamShader() else null
    }
    private val beamRect = RectF()

    /** The clear-glass numbers for this surface. The disc updates it as it swells. */
    var surface: GlassSpec.Surface = GlassSpec.PANEL
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    enum class Kind {
        /** A full sheet: every layer. */
        PANEL,

        /** A field sunk into a panel: base, fill, rim and lens line only. */
        INPUT,

        /** A button or chip: the full recipe at its own radius. */
        PILL
    }

    private val density = context

    /**
     * Overrides the corner radius while a surface is morphing from one shape
     * into another, so the box and the strip are one shape rather than two.
     */
    var radiusOverrideDp: Float? = null
        set(value) {
            field = value
            built = false
            invalidateSelf()
        }

    private val radius: Float
        get() = HeylanaTokens.dp(density, radiusOverrideDp ?: cornerRadiusDp)
    private val rimTop = HeylanaTokens.dp(context, HeylanaTokens.GLASS_RIM_TOP_DP)
    private val rimEdge = HeylanaTokens.dp(context, HeylanaTokens.GLASS_RIM_DP)
    private val lensWidth = HeylanaTokens.dp(context, HeylanaTokens.GLASS_LENS_DP)
    private val inputBorderWidth = HeylanaTokens.dp(context, HeylanaTokens.INPUT_BORDER_DP)

    /**
     * How far the sheen has travelled, 0 off the left to 1 off the right.
     * [GlassPane] sweeps it once when a pane appears and leaves it at rest.
     */
    var sheenProgress: Float = HeylanaTokens.SHEEN_REST
        set(value) {
            field = value
            invalidateSelf()
        }

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseColor }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = lensWidth
        color = HeylanaTokens.glassLensLine
    }

    private val shadowInset = HeylanaTokens.dp(context, HeylanaTokens.GLASS_SHADOW_DP)
    private val scale = context.resources.displayMetrics.density
    private val shadowDy = HeylanaTokens.dp(context, HeylanaTokens.GLASS_SHADOW_DY_DP)

    init {
        // The shadow rides on the base itself as a shadow layer: GPU-drawn, so the pane
        // needs no software layer and its edges can be liquid.
        if (withShadow && GlassSpec.TINTED_EXTRAS) {
            basePaint.setShadowLayer(shadowInset / 2f, 0f, shadowDy, HeylanaTokens.glassShadow)
        }
    }

    private val lens: Any? by lazy {
        if (liquidEdges && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) LiquidGlass.Lens() else null
    }
    private var edgeLayer: Shader? = null
    private val fringePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lensRim = HeylanaTokens.dp(context, HeylanaTokens.EDGE_LENS_RIM_DP)
    private val lensTop = HeylanaTokens.dp(context, HeylanaTokens.EDGE_LENS_TOP_DP)

    private val shape = Path()
    private val body = RectF()
    private val specularOval = RectF()
    private val sheenMatrix = Matrix()
    private var built = false

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        built = false
    }

    private fun build() {
        val b = bounds
        if (b.isEmpty) return

        body.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        if (withShadow && shadowInView) body.inset(shadowInset, shadowInset)
        // Only now that body is the new bounds, since a full-round radius is
        // measured from it. Reading it first clipped the disc to a square.
        val r = effectiveRadius(body.width(), body.height())
        shape.reset()
        shape.addRoundRect(body, r, r, Path.Direction.CW)

        fillPaint.shader = if (kind == Kind.INPUT) {
            null
        } else {
            LinearGradient(
                body.left, body.top, body.left, body.bottom,
                HeylanaTokens.glassFillTop, HeylanaTokens.glassFillBottom,
                Shader.TileMode.CLAMP
            )
        }
        if (kind == Kind.INPUT) fillPaint.color = HeylanaTokens.inputFill

        bandPaint.shader = if (kind == Kind.PANEL) {
            // On a sheet the band follows the top-left corner, so it reads as
            // light bending round the edge.
            RadialGradient(
                body.left + r * 0.9f, body.top + r * 0.9f,
                maxOf(r * 2.6f, hypot(body.width(), body.height()) * 0.34f),
                intArrayOf(bandColor, HeylanaTokens.withAlpha(bandColor, 0f)),
                floatArrayOf(0.35f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            // On a pill there is no corner to bend around, and a corner-only
            // wash left a primary button reading as an ordinary one. Straight
            // across, at full strength.
            LinearGradient(
                body.left, body.top, body.right, body.bottom,
                bandColor, bandColor, Shader.TileMode.CLAMP
            )
        }

        // What the liquid edge bends: this surface's own fill and band, nothing behind it.
        edgeLayer = fillPaint.shader?.let { fill ->
            bandPaint.shader?.let { band -> ComposeShader(fill, band, PorterDuff.Mode.SRC_OVER) } ?: fill
        }

        val sw = body.width() * HeylanaTokens.SPECULAR_WIDTH
        val sh = body.height() * HeylanaTokens.SPECULAR_HEIGHT
        val cx = body.left + body.width() * HeylanaTokens.SPECULAR_X
        val cy = body.top + body.height() * HeylanaTokens.SPECULAR_Y
        specularOval.set(cx - sw / 2f, cy - sh / 2f, cx + sw / 2f, cy + sh / 2f)
        specularPaint.shader = RadialGradient(
            cx, cy, (sw / 2f).coerceAtLeast(1f),
            intArrayOf(HeylanaTokens.glassSpecular, Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        built = true
    }

    private fun effectiveRadius(w: Float, h: Float): Float =
        if ((radiusOverrideDp ?: cornerRadiusDp) >= HeylanaTokens.RADIUS_FULL_DP) {
            minOf(body.width(), body.height()) / 2f
        } else {
            radius
        }

    /** How much padding a view needs so this drawable's shadow is not clipped. */
    fun shadowPadding(): Int = if (withShadow) shadowInset.toInt() else 0

    /** While a gooey merge carries this surface's shape, the glass itself steps aside. */
    var hidden: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || hidden) return
        if (!built) build()
        if (GlassSpec.TINTED_EXTRAS) drawTinted(canvas) else drawClear(canvas)
    }

    // ------------------------------------------------------------ clear glass

    private val clearFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clearStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clearShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val clearGlass: Any? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) ClearGlass() else null
    }

    /** The corner a clear surface uses: chips are 14dp, everything else as asked. */
    private fun clearRadius(): Float = when {
        kind == Kind.PILL && radiusOverrideDp == null ->
            minOf(GlassSpec.CHIP_RADIUS_DP * scale, minOf(body.width(), body.height()) / 2f)
        else -> effectiveRadius(body.width(), body.height())
    }

    private fun drawClear(canvas: Canvas) {
        val r = clearRadius()
        shape.reset()
        shape.addRoundRect(body, r, r, Path.Direction.CW)

        if (withShadow) {
            // A 7dp shadow, black 22%, 8dp down, cast only outside the shape so the
            // glass stays clear. A GPU shadow layer: the view stays hardware rendered.
            val save = canvas.save()
            canvas.clipOutPath(shape)
            clearShadow.setShadowLayer(
                GlassSpec.SHADOW_BLUR_DP * scale, 0f, GlassSpec.SHADOW_DY_DP * scale,
                HeylanaTokens.withAlpha(Color.BLACK, GlassSpec.SHADOW_ALPHA)
            )
            canvas.drawRoundRect(body, r, r, clearShadow)
            canvas.restoreToCount(save)
        }

        when (kind) {
            Kind.INPUT -> {
                clearFill.shader = null
                clearFill.color = HeylanaTokens.inputFill
                canvas.drawRoundRect(body, r, r, clearFill)
                clearStroke.shader = null
                clearStroke.color = HeylanaTokens.inputBorder
                clearStroke.strokeWidth = inputBorderWidth
                val i = inputBorderWidth / 2f
                canvas.drawRoundRect(body.left + i, body.top + i, body.right - i, body.bottom - i, r - i, r - i, clearStroke)
            }
            Kind.PILL -> {
                clearFill.shader = null
                clearFill.color = if (selected) {
                    HeylanaTokens.withAlpha(Color.WHITE, GlassSpec.CHIP_SELECTED_WHITE)
                } else {
                    HeylanaTokens.withAlpha(Color.BLACK, GlassSpec.CHIP_UNSELECTED_BLACK)
                }
                canvas.drawRoundRect(body, r, r, clearFill)
                if (selected) {
                    // A white chip over a white app still holds its shape.
                    clearStroke.shader = null
                    clearStroke.color = HeylanaTokens.withAlpha(Color.BLACK, GlassSpec.CHIP_SELECTED_OUTLINE_BLACK)
                    clearStroke.strokeWidth = GlassSpec.CHIP_SELECTED_OUTLINE_DP * scale
                    insetRoundRect(canvas, r, clearStroke.strokeWidth / 2f, clearStroke)
                } else {
                    drawHairline(canvas, r)
                }
            }
            Kind.PANEL -> {
                if (GlassSpec.darkerGlass) {
                    clearFill.shader = null
                    clearFill.color = HeylanaTokens.withAlpha(Color.BLACK, GlassSpec.DARKER_BASE_BLACK)
                    canvas.drawRoundRect(body, r, r, clearFill)
                }
                val glass = clearGlass
                if (glass != null && canvas.isHardwareAccelerated) drawSpecShader(canvas, glass, r) else drawSpecGradients(canvas, r)
                if (beamStrength > 0f && canvas.isHardwareAccelerated) drawBeam(canvas, r)
            }
        }
    }

    /** The surface's own backing, which the lens refracts: the 12% black smoke. Built once per size. */
    private var smokeLayer: Shader? = null
    private val smokeBounds = RectF()

    /** API 33+: the spec per pixel. The layer it refracts is this surface's own 12% black smoke. */
    @android.annotation.SuppressLint("NewApi")
    private fun drawSpecShader(canvas: Canvas, glassAny: Any, r: Float) {
        val glass = glassAny as ClearGlass
        if (smokeLayer == null || smokeBounds != body) {
            val smoke = HeylanaTokens.withAlpha(Color.BLACK, GlassSpec.FILL_BLACK)
            smokeLayer = LinearGradient(body.left, body.top, body.left, body.bottom, smoke, smoke, Shader.TileMode.CLAMP)
            smokeBounds.set(body)
        }
        glass.set(body.left, body.top, body.width(), body.height(), surface, r, smokeLayer!!, scale)
        if (streakStrength > 0f) {
            // Narrowed so its widest breath covers at most a third of this pane; the trail
            // and its distance scale with the width, so the disc's streak keeps its shape.
            val extent = GlassSpec.streakExtent(body.width(), body.height())
            val baseWidth = streakWidthDp * scale
            val breath = streakBreathDp * scale
            val fit = GlassSpec.streakFit(extent, baseWidth, breath)
            val ratio = streakWidthDp / GlassSpec.STREAK_WIDTH_DP * fit
            val width = GlassSpec.streakWidth(streakPhase, baseWidth, breath) * fit
            val trailBehind = GlassSpec.STREAK_TRAIL_BEHIND_DP * scale * ratio
            val trailWidth = GlassSpec.STREAK_TRAIL_WIDTH_DP * scale * ratio
            val centre = GlassSpec.streakCentre(streakPhase, extent, (baseWidth + breath) * fit, trailBehind, trailWidth)
            glass.setStreak(centre, width, trailBehind, trailWidth, streakStrength)
        } else {
            glass.setStreak(0f, 1f, 0f, 1f, 0f)
        }
        canvas.drawRect(body, glass.paint)
    }

    /** The aurora glow riding the rim (thinking, listening, speaking). Uniforms only per frame. */
    @android.annotation.SuppressLint("NewApi")
    private fun drawBeam(canvas: Canvas, r: Float) {
        val beam = beamShader as? BeamShader ?: return
        val glow = beamGlowDp * scale
        beam.set(body.left, body.top, body.width(), body.height(), r, beamPhase, beamStrength, glow)
        // The glow spills a little outside the rim, into the shadow's margin.
        beamRect.set(body.left - 2f * glow, body.top - 2f * glow, body.right + 2f * glow, body.bottom + 2f * glow)
        canvas.drawRect(beamRect, beam.paint)
    }

    /**
     * Below API 33, or on a software canvas: the same numbers as gradients, without
     * the refraction — fill, the top-to-bottom gradient, the rim lit toward the
     * bottom-right, the lens line, and the hairline.
     */
    private fun drawSpecGradients(canvas: Canvas, r: Float) {
        val edge = surface.edgeDp * scale
        clearFill.shader = null
        clearFill.color = HeylanaTokens.withAlpha(Color.BLACK, GlassSpec.FILL_BLACK)
        canvas.drawRoundRect(body, r, r, clearFill)

        clearFill.shader = LinearGradient(
            body.left, body.top, body.left, body.bottom,
            intArrayOf(
                HeylanaTokens.withAlpha(Color.WHITE, GlassSpec.GRADIENT_TOP + GlassSpec.BAND_BRIGHTNESS),
                Color.TRANSPARENT,
                HeylanaTokens.withAlpha(Color.BLACK, -GlassSpec.GRADIENT_BOTTOM)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(body, r, r, clearFill)
        clearFill.shader = null

        // Rim: up to 0.10 + 0.32 on the top-left, facing the light, down to 0.10 - 0.10 at the bottom-right.
        val rimWidth = (GlassSpec.RIM_WIDTH * edge).coerceAtLeast(1f)
        clearStroke.strokeWidth = rimWidth
        clearStroke.shader = LinearGradient(
            body.left, body.top, body.right, body.bottom,
            HeylanaTokens.withAlpha(Color.WHITE, GlassSpec.RIM_BASE + GlassSpec.RIM_LIGHT),
            HeylanaTokens.withAlpha(Color.WHITE, GlassSpec.RIM_BASE - GlassSpec.RIM_SHADE),
            Shader.TileMode.CLAMP
        )
        insetRoundRect(canvas, r, rimWidth / 2f, clearStroke)

        clearStroke.shader = null
        clearStroke.color = HeylanaTokens.withAlpha(Color.BLACK, -GlassSpec.LENS_LINE)
        clearStroke.strokeWidth = (GlassSpec.LENS_LINE_WIDTH * edge).coerceAtLeast(1f)
        insetRoundRect(canvas, r, GlassSpec.LENS_LINE_AT * edge, clearStroke)

        drawHairline(canvas, r)
    }

    /** 1px at the boundary, white at 0.30 × (0.3 + 0.7 ndotl): full top-left, dim bottom-right. */
    private fun drawHairline(canvas: Canvas, r: Float) {
        clearStroke.strokeWidth = GlassSpec.HAIRLINE_PX
        clearStroke.shader = LinearGradient(
            body.left, body.top, body.right, body.bottom,
            HeylanaTokens.withAlpha(Color.WHITE, GlassSpec.HAIRLINE),
            HeylanaTokens.withAlpha(Color.WHITE, GlassSpec.HAIRLINE * 0.3f),
            Shader.TileMode.CLAMP
        )
        insetRoundRect(canvas, r, GlassSpec.HAIRLINE_PX / 2f, clearStroke)
        clearStroke.shader = null
    }

    private fun insetRoundRect(canvas: Canvas, r: Float, inset: Float, paint: Paint) {
        val ri = (r - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(body.left + inset, body.top + inset, body.right - inset, body.bottom - inset, ri, ri, paint)
    }

    // ----------------------------------------------------------- tinted (off)

    private fun drawTinted(canvas: Canvas) {
        val r = effectiveRadius(bounds.width().toFloat(), bounds.height().toFloat())

        canvas.drawRoundRect(body, r, r, basePaint)
        canvas.drawRoundRect(body, r, r, fillPaint)

        if (kind != Kind.INPUT) {
            val save = canvas.save()
            canvas.clipPath(shape)
            canvas.drawRoundRect(body, r, r, bandPaint)

            // Squashed into an ellipse rather than drawn as a circle.
            val squash = canvas.save()
            canvas.scale(
                1f, specularOval.height() / specularOval.width(),
                specularOval.centerX(), specularOval.centerY()
            )
            canvas.drawCircle(
                specularOval.centerX(), specularOval.centerY(),
                specularOval.width() / 2f, specularPaint
            )
            canvas.restoreToCount(squash)

            if (withSheen) drawSheen(canvas)
            canvas.restoreToCount(save)
        }

        drawRim(canvas, r)
        if (liquidEdges && kind != Kind.INPUT && LiquidGlass.available(canvas)) drawLiquidEdges(canvas, r)
    }

    /** The lens and lighting on the rim and the top band, then the chromatic fringe. */
    @android.annotation.SuppressLint("NewApi")
    private fun drawLiquidEdges(canvas: Canvas, r: Float) {
        val edgeLens = lens as? LiquidGlass.Lens ?: return
        val layer = edgeLayer ?: return
        val rim = minOf(lensRim, minOf(body.width(), body.height()) / 2f)
        edgeLens.set(
            bounds = body,
            corner = r,
            rim = rim,
            layer = layer,
            layerAlpha = HeylanaTokens.EDGE_LAYER_ALPHA,
            light = HeylanaTokens.EDGE_LIGHT,
            edgeOnly = true,
            topBand = lensTop
        )
        canvas.drawRoundRect(body, r, r, edgeLens.paint)
        LiquidGlass.drawFringe(canvas, density, body, r, fringePaint)
    }

    /** A soft band of light on a slant, wherever [sheenProgress] has it. */
    private fun drawSheen(canvas: Canvas) {
        val bandWidth = (body.width() * HeylanaTokens.SHEEN_WIDTH).coerceAtLeast(1f)
        // Travel far enough that the band starts and ends fully off the pane.
        val travel = body.width() + bandWidth * 2f
        val centre = body.left - bandWidth + travel * sheenProgress

        val shader = LinearGradient(
            centre - bandWidth / 2f, 0f, centre + bandWidth / 2f, 0f,
            intArrayOf(Color.TRANSPARENT, HeylanaTokens.glassSheen, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        sheenMatrix.reset()
        sheenMatrix.setRotate(HeylanaTokens.SHEEN_ANGLE_DEG, centre, body.centerY())
        shader.setLocalMatrix(sheenMatrix)
        sheenPaint.shader = shader
        canvas.drawRect(body, sheenPaint)
    }

    /**
     * The rim is heavier along the top than round the rest, so the pane reads as
     * catching light from above; the lens line just inside it is the refraction.
     */
    private fun drawRim(canvas: Canvas, r: Float) {
        if (kind == Kind.INPUT) {
            rimPaint.shader = null
            rimPaint.color = HeylanaTokens.inputBorder
            rimPaint.strokeWidth = inputBorderWidth
        } else {
            rimPaint.color = Color.WHITE
            rimPaint.shader = LinearGradient(
                body.left, body.top, body.right, body.bottom,
                HeylanaTokens.glassRimBright, HeylanaTokens.glassRimDim,
                Shader.TileMode.CLAMP
            )
            rimPaint.strokeWidth = rimEdge
        }

        val edgeInset = rimPaint.strokeWidth / 2f
        canvas.drawRoundRect(
            body.left + edgeInset, body.top + edgeInset,
            body.right - edgeInset, body.bottom - edgeInset,
            (r - edgeInset).coerceAtLeast(0f), (r - edgeInset).coerceAtLeast(0f), rimPaint
        )

        if (kind != Kind.INPUT) {
            // The heavier top: the same rounded path, clipped to the upper band,
            // so the thickness tapers into the sides rather than stopping dead.
            rimPaint.shader = ComposeShader(
                LinearGradient(
                    body.left, body.top, body.right, body.bottom,
                    HeylanaTokens.glassRimBright, HeylanaTokens.glassRimDim,
                    Shader.TileMode.CLAMP
                ),
                LinearGradient(
                    0f, body.top, 0f, body.top + r,
                    intArrayOf(Color.WHITE, Color.TRANSPARENT),
                    floatArrayOf(HeylanaTokens.RIM_TOP_HOLD, 1f),
                    Shader.TileMode.CLAMP
                ),
                PorterDuff.Mode.DST_IN
            )
            rimPaint.strokeWidth = rimTop
            val topInset = rimTop / 2f
            canvas.drawRoundRect(
                body.left + topInset, body.top + topInset,
                body.right - topInset, body.bottom - topInset,
                (r - topInset).coerceAtLeast(0f), (r - topInset).coerceAtLeast(0f), rimPaint
            )
        }

        val lensInset = rimEdge + lensWidth
        canvas.drawRoundRect(
            body.left + lensInset, body.top + lensInset,
            body.right - lensInset, body.bottom - lensInset,
            (r - lensInset).coerceAtLeast(0f), (r - lensInset).coerceAtLeast(0f), lensPaint
        )
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
