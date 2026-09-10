package xyz.heylana.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.hypot

/**
 * One liquid-glass recipe, used by every glass surface: the message box, the
 * reply strip, the task HUD, their buttons and the buddy's disc.
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
 * a translucent sheet — the platform paints a pale rectangle across it.
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
     * not clipped. The view must carry [shadowInsetPx] of padding on every side
     * and be rendered in software, since a blur mask needs it.
     */
    private val withShadow: Boolean = false,
    /**
     * Whether this surface sweeps a sheen. Off for small buttons, where the band
     * is narrower than its own rotation pivot and leaves a hard corner.
     */
    private val withSheen: Boolean = kind == Kind.PANEL
) : Drawable() {

    enum class Kind {
        /** A full sheet: every layer. */
        PANEL,

        /** A field sunk into a panel: base, fill, rim and lens line only. */
        INPUT,

        /** A button or chip: the full recipe at its own radius. */
        PILL
    }

    private val radius = HeylanaTokens.dp(context, cornerRadiusDp)
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

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (kind == Kind.INPUT) HeylanaTokens.inputBase else HeylanaTokens.glassBase
    }
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

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HeylanaTokens.glassShadow
        maskFilter = android.graphics.BlurMaskFilter(
            HeylanaTokens.dp(context, HeylanaTokens.GLASS_SHADOW_DP) / 2f,
            android.graphics.BlurMaskFilter.Blur.NORMAL
        )
    }
    private val shadowInset = HeylanaTokens.dp(context, HeylanaTokens.GLASS_SHADOW_DP)
    private val shadowDy = HeylanaTokens.dp(context, HeylanaTokens.GLASS_SHADOW_DY_DP)
    private val shadowRect = RectF()

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
        val r = effectiveRadius(b.width().toFloat(), b.height().toFloat())

        body.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        if (withShadow) body.inset(shadowInset, shadowInset)
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

        // The refraction band follows the top-left corner rather than cutting
        // straight across, so it reads as light bending round the edge.
        bandPaint.shader = RadialGradient(
            body.left + r * 0.9f, body.top + r * 0.9f,
            maxOf(r * 2.6f, hypot(body.width(), body.height()) * 0.34f),
            intArrayOf(bandColor, HeylanaTokens.withAlpha(bandColor, 0f)),
            floatArrayOf(0.35f, 1f),
            Shader.TileMode.CLAMP
        )

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
        if (cornerRadiusDp >= HeylanaTokens.RADIUS_FULL_DP) {
            minOf(body.width(), body.height()) / 2f
        } else {
            radius
        }

    /** How much padding a view needs so this drawable's shadow is not clipped. */
    fun shadowPadding(): Int = if (withShadow) shadowInset.toInt() else 0

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        if (!built) build()
        val r = effectiveRadius(bounds.width().toFloat(), bounds.height().toFloat())

        if (withShadow) {
            shadowRect.set(body)
            shadowRect.offset(0f, shadowDy)
            canvas.drawRoundRect(shadowRect, r, r, shadowPaint)
        }

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
            val save = canvas.save()
            canvas.clipRect(body.left, body.top, body.right, body.top + r)
            rimPaint.strokeWidth = rimTop
            val topInset = rimTop / 2f
            canvas.drawRoundRect(
                body.left + topInset, body.top + topInset,
                body.right - topInset, body.bottom - topInset,
                (r - topInset).coerceAtLeast(0f), (r - topInset).coerceAtLeast(0f), rimPaint
            )
            canvas.restoreToCount(save)
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
