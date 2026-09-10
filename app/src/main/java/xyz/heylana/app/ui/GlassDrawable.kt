package xyz.heylana.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * One liquid-glass recipe, used by every glass surface: the message box, its
 * buttons, and the buddy's disc.
 *
 * In order, inside a rounded rectangle:
 *  1. the fill — thin when the platform is blurring what is behind the window,
 *     heavier when it is not and the fill has to carry the surface alone,
 *  2. a soft purple refraction band running at 122 degrees near the top-left,
 *  3. a bevel: light along the top and left edges, dark along the bottom and
 *     right, both fading out toward the middle,
 *  4. a hairline border.
 *
 * [blurBehind] should be whatever the window actually got, not what was asked
 * for — see [GlassBlur.isAvailable].
 */
class GlassDrawable(
    context: Context,
    private val cornerRadiusDp: Float,
    private val blurBehind: Boolean,
    /** The refraction band. [HeylanaTokens.bandPrimary] makes a button read as primary. */
    private val bandColor: Int = HeylanaTokens.purpleBand
) : Drawable() {

    private val radius = HeylanaTokens.dp(context, cornerRadiusDp)
    private val borderWidth = HeylanaTokens.dp(context, 1f)
    private val bevelWidth = HeylanaTokens.dp(context, 1.5f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (blurBehind) HeylanaTokens.glassFill else HeylanaTokens.glassFillNoBlur
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = bevelWidth
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = HeylanaTokens.glassBorder
    }

    private val shape = Path()
    private val body = RectF()
    private var built = false

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        built = false
    }

    private fun build() {
        val b = bounds
        if (b.isEmpty) return
        val r = effectiveRadius(b.width().toFloat(), b.height().toFloat())

        body.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        shape.reset()
        shape.addRoundRect(body, r, r, Path.Direction.CW)

        // The refraction band: a soft diagonal wash whose peak sits about a
        // third of the way along, near the top-left corner.
        val angle = Math.toRadians(122.0)
        val span = hypot(body.width(), body.height())
        val dx = cos(angle).toFloat() * span
        val dy = sin(angle).toFloat() * span
        bandPaint.shader = LinearGradient(
            body.left, body.top,
            body.left + dx, body.top + dy,
            intArrayOf(
                HeylanaTokens.withAlpha(bandColor, 0f),
                bandColor,
                HeylanaTokens.withAlpha(bandColor, 0f)
            ),
            floatArrayOf(0f, 0.28f, 0.72f),
            Shader.TileMode.CLAMP
        )

        // The bevel is one stroke shaded from light at the top-left corner to
        // dark at the bottom-right, transparent through the middle.
        bevelPaint.shader = LinearGradient(
            body.left, body.top,
            body.right, body.bottom,
            intArrayOf(
                HeylanaTokens.bevelLight,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                HeylanaTokens.bevelDark
            ),
            floatArrayOf(0f, 0.35f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        built = true
    }

    private fun effectiveRadius(w: Float, h: Float): Float =
        if (cornerRadiusDp >= HeylanaTokens.RADIUS_FULL_DP) minOf(w, h) / 2f else radius

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        if (!built) build()
        val r = effectiveRadius(bounds.width().toFloat(), bounds.height().toFloat())

        canvas.drawRoundRect(body, r, r, fillPaint)

        val save = canvas.save()
        canvas.clipPath(shape)
        canvas.drawRoundRect(body, r, r, bandPaint)
        canvas.restoreToCount(save)

        // Both the bevel and the border ride just inside the edge so neither is
        // clipped in half by the shape.
        val bevelInset = bevelWidth / 2f
        canvas.drawRoundRect(
            body.left + bevelInset, body.top + bevelInset,
            body.right - bevelInset, body.bottom - bevelInset,
            r - bevelInset, r - bevelInset, bevelPaint
        )

        val borderInset = borderWidth / 2f
        canvas.drawRoundRect(
            body.left + borderInset, body.top + borderInset,
            body.right - borderInset, body.bottom - borderInset,
            r - borderInset, r - borderInset, borderPaint
        )
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
