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
 * One liquid-glass recipe, used by every glass surface: the message box, the
 * reply strip, the task HUD, their buttons and the buddy's disc.
 *
 * Bottom to top:
 *  1. a smoked base, then a vertical white fill brighter at the top where light
 *     would catch it,
 *  2. a soft purple refraction band at 122 degrees near the top-left,
 *  3. a border that runs bright at the top-left and almost vanishes bottom-right,
 *  4. a hairline of light just inside the top edge, fading out at the corners.
 *
 * There is deliberately no outer shadow. A background drawable is clipped to its
 * own bounds, so a shadow layer comes out as a hard rectangle, and hanging it on
 * the view's elevation instead paints a pale band across a translucent sheet on
 * a bright backdrop. Doing it properly needs a padded wrapper to draw into.
 *
 * [Kind] picks which of those a given surface wants; a question field is a
 * lighter sheet with no shadow, a primary button is the same glass with the
 * band at full strength.
 */
class GlassDrawable(
    context: Context,
    private val cornerRadiusDp: Float,
    private val blurBehind: Boolean,
    private val kind: Kind = Kind.PANEL,
    /** The refraction band. [HeylanaTokens.bandPrimary] makes a button primary. */
    private val bandColor: Int = HeylanaTokens.purpleBand
) : Drawable() {

    enum class Kind {
        /** A full sheet: gradient fill, gradient border, top highlight. */
        PANEL,

        /** A field sunk into a panel: lighter, flat, no shadow. */
        INPUT,

        /** A button or chip: panel glass with a highlight along the top. */
        PILL
    }

    private val radius = HeylanaTokens.dp(context, cornerRadiusDp)
    private val borderWidth = HeylanaTokens.dp(context, HeylanaTokens.GLASS_BORDER_DP)
    private val inputBorderWidth = HeylanaTokens.dp(context, HeylanaTokens.INPUT_BORDER_DP)
    private val highlightWidth = HeylanaTokens.dp(context, HeylanaTokens.GLASS_HIGHLIGHT_DP)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (kind == Kind.INPUT) HeylanaTokens.inputBase else HeylanaTokens.glassBase
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = highlightWidth
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

        fillPaint.shader = when (kind) {
            Kind.INPUT -> null
            else -> LinearGradient(
                body.left, body.top, body.left, body.bottom,
                HeylanaTokens.glassFillTop, HeylanaTokens.glassFillBottom,
                Shader.TileMode.CLAMP
            )
        }
        if (kind == Kind.INPUT) fillPaint.color = HeylanaTokens.inputFill

        // The refraction band: a soft diagonal wash whose peak sits about a
        // third of the way along, near the top-left corner.
        val angle = Math.toRadians(122.0)
        val span = hypot(body.width(), body.height())
        bandPaint.shader = LinearGradient(
            body.left, body.top,
            body.left + cos(angle).toFloat() * span, body.top + sin(angle).toFloat() * span,
            intArrayOf(
                HeylanaTokens.withAlpha(bandColor, 0f),
                bandColor,
                HeylanaTokens.withAlpha(bandColor, 0f)
            ),
            floatArrayOf(0f, 0.28f, 0.72f),
            Shader.TileMode.CLAMP
        )

        borderPaint.strokeWidth = if (kind == Kind.INPUT) inputBorderWidth else borderWidth
        borderPaint.shader = if (kind == Kind.INPUT) {
            null
        } else {
            LinearGradient(
                body.left, body.top, body.right, body.bottom,
                HeylanaTokens.glassBorderBright, HeylanaTokens.glassBorderDim,
                Shader.TileMode.CLAMP
            )
        }
        if (kind == Kind.INPUT) borderPaint.color = HeylanaTokens.inputBorder

        // The inner top line is brightest in the middle and gone by the corners,
        // so it reads as light catching the edge rather than a drawn stroke.
        highlightPaint.shader = LinearGradient(
            body.left, body.top, body.right, body.top,
            intArrayOf(
                Color.TRANSPARENT,
                HeylanaTokens.glassTopHighlight,
                HeylanaTokens.glassTopHighlight,
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.22f, 0.78f, 1f),
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

        // Smoked base first: the white fill on its own vanishes over a bright page.
        canvas.drawRoundRect(body, r, r, basePaint)
        canvas.drawRoundRect(body, r, r, fillPaint)

        if (kind != Kind.INPUT) {
            val save = canvas.save()
            canvas.clipPath(shape)
            canvas.drawRoundRect(body, r, r, bandPaint)
            canvas.restoreToCount(save)
        }

        val borderInset = borderPaint.strokeWidth / 2f
        canvas.drawRoundRect(
            body.left + borderInset, body.top + borderInset,
            body.right - borderInset, body.bottom - borderInset,
            r - borderInset, r - borderInset, borderPaint
        )

        if (kind != Kind.INPUT) {
            // A line just inside the top edge, inset past the corner curve so it
            // does not double up with the border where they would meet.
            val y = body.top + borderPaint.strokeWidth + highlightWidth
            val inset = r * 0.7f
            canvas.drawLine(
                body.left + inset, y, body.right - inset, y, highlightPaint
            )
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
