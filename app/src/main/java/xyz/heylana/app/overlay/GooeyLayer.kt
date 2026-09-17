package xyz.heylana.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import xyz.heylana.app.ui.HeylanaTokens

/*
 * Gooey merges, after liquid-gooey by Jakub Antalik, MIT licence
 * (https://github.com/Jakubantalik/Libraries.dev, packages/liquid-gooey).
 * Copyright (c) 2026 Jakub Antalik. Licence text: app/src/main/assets/licenses/liquid-gooey.txt.
 *
 * The technique, from its filter.tsx: the shapes that merge are drawn into one group,
 * blurred (stdDeviation `blur`, 6), then an alpha colour matrix with slope `contrast`
 * (18) and intercept `0.5 - contrast × 5/12` (−7) turns the soft blur back into a hard
 * edge — so shapes that touch bridge and merge like goo. As in the library, only the
 * silhouette is filtered: the text and the mark ride crisp in their own layers above.
 */
class GooeyLayer(context: Context) : View(context) {

    /** One shape of the silhouette: a rounded rect in this layer's coordinates (a circle when r is half the size). */
    class Blob {
        val rect = RectF()
        var radius = 0f
        var visible = false
    }

    val blobs = Array(MAX_BLOBS) { Blob() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GooeySpec.SILHOUETTE }

    init {
        visibility = GONE
        alpha = GooeySpec.SILHOUETTE_ALPHA
        if (available) {
            val blur = HeylanaTokens.dp(context, GooeySpec.BLUR_DP)
            setRenderEffect(
                RenderEffect.createColorFilterEffect(
                    ColorMatrixColorFilter(GooeySpec.alphaContrast()),
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL)
                )
            )
        }
    }

    fun clear() {
        blobs.forEach { it.visible = false }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (blob in blobs) {
            if (!blob.visible) continue
            canvas.drawRoundRect(blob.rect, blob.radius, blob.radius, paint)
        }
    }

    /** Where [view] is in this layer's coordinates, into [out]. */
    fun rectOf(view: View, out: RectF) {
        view.getLocationOnScreen(tmp)
        getLocationOnScreen(own)
        val left = (tmp[0] - own[0]).toFloat()
        val top = (tmp[1] - own[1]).toFloat()
        out.set(left, top, left + view.width, top + view.height)
    }

    private val tmp = IntArray(2)
    private val own = IntArray(2)

    /**
     * Runs one merge on the shared spring (0 to 1), calling [frame] with the progress
     * each step, then [done]. Returns the animation so it can be cancelled.
     */
    fun spring(frame: (Float) -> Unit, done: () -> Unit): SpringAnimation {
        visibility = VISIBLE
        alpha = GooeySpec.SILHOUETTE_ALPHA
        return SpringAnimation(FloatValueHolder(0f)).apply {
            spring = SpringForce(1f).apply {
                stiffness = HeylanaTokens.SPRING_STIFFNESS
                dampingRatio = HeylanaTokens.SPRING_DAMPING
            }
            setMinimumVisibleChange(0.001f)
            addUpdateListener { _, value, _ ->
                frame(value)
                invalidate()
            }
            addEndListener { _, _, _, _ ->
                frame(1f)
                done()
            }
            start()
        }
    }

    /** The silhouette fades as the real glass comes back, then goes. */
    fun fadeAway() {
        animate().alpha(0f).setDuration(HeylanaTokens.FADE_MS).withEndAction {
            visibility = GONE
            clear()
        }.start()
    }

    companion object {
        const val MAX_BLOBS = 6

        /** RenderEffect arrived in API 31; below it the merges are plain scale and fade. */
        val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}

/** The goo's numbers, and the geometry of each merge, kept free of Android views so they can be tested. */
object GooeySpec {

    /** liquid-gooey's default pairing. */
    const val BLUR_DP = 6f
    const val CONTRAST = 18f

    /** The silhouette: a neutral grey, so the goo reads over black and over white. */
    const val SILHOUETTE = 0xFF8A8A8A.toInt()
    const val SILHOUETTE_ALPHA = 0.45f

    /** liquid-gooey's intercept: `0.5 - contrast × 5/12`, rounded to hundredths. */
    fun intercept(contrast: Float = CONTRAST): Float = kotlin.math.round((0.5f - contrast * (5f / 12f)) * 100f) / 100f

    /** RGB untouched; alpha × contrast + intercept (the intercept in 0–255 units, as Android's matrix wants). */
    fun alphaContrast(contrast: Float = CONTRAST): ColorMatrix = ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, contrast, intercept(contrast) * 255f
        )
    )

    /** What the matrix does to one alpha value, 0 to 1. */
    fun contrastAlpha(alpha: Float, contrast: Float = CONTRAST): Float =
        (alpha * contrast + intercept(contrast)).coerceIn(0f, 1f)

    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * The box growing out of the disc: at 0 a circle a little smaller than the disc,
     * centred just below the disc's centre so the two overlap and read as one blob; at 1
     * the box itself. [out] gets left, top, right, bottom, radius.
     */
    fun growFromDisc(disc: RectF, box: RectF, boxRadius: Float, t: Float, out: FloatArray) =
        growFromDisc(disc.centerX(), disc.centerY(), disc.width(), box.left, box.top, box.right, box.bottom, boxRadius, t, out)

    /** [growFromDisc] in plain numbers: the disc's centre and diameter, the box's edges. */
    fun growFromDisc(
        discCx: Float, discCy: Float, discD: Float,
        boxLeft: Float, boxTop: Float, boxRight: Float, boxBottom: Float,
        boxRadius: Float, t: Float, out: FloatArray
    ) {
        val d = discD * SEED_FRACTION
        val cy = discCy + discD * SEED_DROP
        out[0] = lerp(discCx - d / 2f, boxLeft, t)
        out[1] = lerp(cy - d / 2f, boxTop, t)
        out[2] = lerp(discCx + d / 2f, boxRight, t)
        out[3] = lerp(cy + d / 2f, boxBottom, t)
        out[4] = lerp(d / 2f, boxRadius, t.coerceIn(0f, 1f))
    }

    /**
     * The box pinching into the strip: the body springs from [from] to [to], and a
     * droplet left behind at the old bottom shrinks back up into it.
     */
    fun pinch(from: RectF, to: RectF, radius: Float, t: Float, body: FloatArray, droplet: FloatArray) {
        body[0] = lerp(from.left, to.left, t)
        body[1] = lerp(from.top, to.top, t)
        body[2] = lerp(from.right, to.right, t)
        body[3] = lerp(from.bottom, to.bottom, t)
        body[4] = radius
        val size = (from.height() - to.height()).coerceAtLeast(radius) * DROPLET_FRACTION * (1f - t).coerceIn(0f, 1f)
        val cx = lerp(from.centerX(), to.centerX(), t)
        val cy = lerp(from.bottom - size / 2f, body[3], t)
        droplet[0] = cx - size / 2f
        droplet[1] = cy - size / 2f
        droplet[2] = cx + size / 2f
        droplet[3] = cy + size / 2f
        droplet[4] = size / 2f
    }

    /** A chip emerging from the pane's bottom edge: from a sliver on the edge to its own place. */
    fun emerge(edgeY: Float, chip: RectF, chipRadius: Float, t: Float, out: FloatArray) {
        val h = chip.height()
        val w = chip.width()
        val cx = chip.centerX()
        out[0] = lerp(cx - w * 0.3f, chip.left, t)
        out[1] = lerp(edgeY - h * 0.3f, chip.top, t)
        out[2] = lerp(cx + w * 0.3f, chip.right, t)
        out[3] = lerp(edgeY + h * 0.1f, chip.bottom, t)
        out[4] = chipRadius
    }

    private const val SEED_FRACTION = 0.7f
    private const val SEED_DROP = 0.45f
    private const val DROPLET_FRACTION = 0.6f
}
