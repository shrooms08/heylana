package xyz.heylana.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Liquid glass for Heylana's own surfaces: the lens and lighting of the reference
 * shader (design/refs/liquid_glass.glsl, Shadertoy WftXD2), ported to AGSL.
 *
 * The reference refracts the picture behind the glass. Heylana never samples the
 * screen: the lens is applied to a layer the surface paints itself — the disc's
 * aurora, a panel's fill and band — so the glass bends its own light. Blur behind
 * a panel stays the system's FLAG_BLUR_BEHIND.
 *
 * The one change to the maths is the mask's input. The reference's
 * `roundedBox * 10000` is a superellipse that reaches 1 at the edge of a small box
 * in a full-screen image; here that same quantity, [LiquidGlassMath.rb], is built
 * from the rounded-rect distance so it is 1 at this surface's edge and falls
 * inward over [rim] pixels. Every band and the lens then use the reference's own
 * constants (9500 and 11000 become 0.95 and 1.1, 5000 becomes 0.5), which
 * `LiquidGlassMathTest` checks against the original formulas.
 *
 * API 33 and up, and only on a hardware canvas. Below that, or in software, the
 * surface keeps its [GlassDrawable] recipe untouched.
 */
object LiquidGlass {

    fun available(canvas: Canvas): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canvas.isHardwareAccelerated

    private const val AGSL = """
uniform shader layer;
uniform float2 origin;
uniform float2 size;
uniform float corner;
uniform float rim;
uniform float layerAlpha;
uniform float light;
uniform float edgeOnly;
uniform float topBand;

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 fragCoord) {
    float2 c = size * 0.5;
    float2 p = fragCoord - origin - c;
    // 1 at the edge, falling inward: the reference's roundedBox * 10000.
    float rb = 1.0 + sdRoundBox(p, c, corner) / rim;

    // Panels keep only their edges: the rim band and the top strip.
    float mask = 1.0;
    if (edgeOnly > 0.5) {
        float rimMask = smoothstep(0.35, 0.85, rb);
        float top = 1.0 - smoothstep(topBand - 2.0, topBand, p.y + c.y);
        mask = max(rimMask, top);
        if (mask <= 0.0) return half4(0.0);
    }

    float rb1 = clamp((1.0 - rb) * 8.0, 0.0, 1.0);
    float rb2 = clamp((0.95 - rb * 0.95) * 16.0, 0.0, 1.0) - clamp((0.9 - rb * 0.95) * 16.0, 0.0, 1.0);
    float rb3 = clamp((1.5 - rb * 1.1) * 2.0, 0.0, 1.0) - clamp((1.0 - rb * 1.1) * 2.0, 0.0, 1.0);
    float transition = smoothstep(0.0, 1.0, rb1 + rb2);
    if (transition <= 0.0) return half4(0.0);

    // Lens: pull toward the centre, stronger near the edge, then a small box blur.
    float2 lens = origin + c + p * (1.0 - clamp(rb, 0.0, 1.0) * 0.5);
    half4 col = half4(0.0);
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            col += layer.eval(lens + float2(float(x), float(y)));
        }
    }
    col /= 9.0;

    // Lighting: top edge lit, bottom edge lit by rb3, the rim band lifts.
    // Canvas y runs down, the reference's up, so the sign flips.
    float my = -p.y / c.y * 0.1;
    float gradient = clamp((clamp(my, 0.0, 0.2) + 0.1) / 2.0, 0.0, 1.0) +
        clamp((clamp(-my, -1000.0, 0.2) * rb3 + 0.1) / 2.0, 0.0, 1.0);
    half4 lit = clamp(
        col * layerAlpha + half4(rb1 * gradient * light) + half4(rb2 * 0.3 * light),
        half4(0.0), half4(1.0)
    );
    return lit * transition * mask;
}
"""

    /** One surface's lens: a shader and the paint that carries it. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    class Lens {
        private val shader = RuntimeShader(AGSL)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = this@Lens.shader }

        /**
         * [bounds] is the surface in canvas coordinates, [corner] its radius, [rim]
         * how far in from the edge the lens reaches. [layer] is the surface's own
         * light to bend, in the same coordinates, shown at [layerAlpha]; [light] scales
         * the white lighting bands. [edgeOnly] keeps the rim and a [topBand]-tall
         * strip only.
         */
        fun set(
            bounds: RectF,
            corner: Float,
            rim: Float,
            layer: Shader,
            layerAlpha: Float,
            light: Float,
            edgeOnly: Boolean,
            topBand: Float = 0f
        ) {
            shader.setInputShader("layer", layer)
            shader.setFloatUniform("origin", bounds.left, bounds.top)
            shader.setFloatUniform("size", bounds.width(), bounds.height())
            shader.setFloatUniform("corner", corner)
            shader.setFloatUniform("rim", rim.coerceAtLeast(1f))
            shader.setFloatUniform("layerAlpha", layerAlpha)
            shader.setFloatUniform("light", light)
            shader.setFloatUniform("edgeOnly", if (edgeOnly) 1f else 0f)
            shader.setFloatUniform("topBand", topBand)
        }
    }

    private val FRINGE = intArrayOf(HeylanaTokens.fringeRed, HeylanaTokens.fringeGreen, HeylanaTokens.fringeBlue)

    /** Where each colour sits, as a direction: red up-left, green down, blue up-right. */
    private val FRINGE_DIRECTIONS = floatArrayOf(-0.87f, -0.5f, 0f, 1f, 0.87f, -0.5f)

    /**
     * The chromatic rim: three thin strokes, red, green and blue at low alpha, each
     * nudged a little off the edge in its own direction, plus [splitX], [splitY]
     * pulling red and blue apart along the direction of motion.
     */
    fun drawFringe(
        canvas: Canvas,
        context: Context,
        bounds: RectF,
        corner: Float,
        paint: Paint,
        splitX: Float = 0f,
        splitY: Float = 0f
    ) {
        val offset = HeylanaTokens.dp(context, HeylanaTokens.FRINGE_OFFSET_DP)
        val stroke = HeylanaTokens.dp(context, HeylanaTokens.FRINGE_STROKE_DP)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.shader = null
        val inset = stroke / 2f + offset
        for (i in 0 until 3) {
            val motion = i - 1 // red back, green still, blue forward
            val dx = FRINGE_DIRECTIONS[i * 2] * offset + splitX * motion
            val dy = FRINGE_DIRECTIONS[i * 2 + 1] * offset + splitY * motion
            paint.color = FRINGE[i]
            val r = (corner - inset).coerceAtLeast(0f)
            canvas.drawRoundRect(
                bounds.left + inset + dx, bounds.top + inset + dy,
                bounds.right - inset + dx, bounds.bottom - inset + dy,
                r, r, paint
            )
        }
    }
}

/**
 * The lens maths from the shader, in Kotlin, so the port can be checked against the
 * reference's formulas on the JVM. The AGSL above is the same expressions.
 */
object LiquidGlassMath {

    /** 1 at the edge of a rounded rect of half-size [halfW], [halfH], falling inward over [rim]. */
    fun rb(px: Float, py: Float, halfW: Float, halfH: Float, corner: Float, rim: Float): Float {
        val qx = kotlin.math.abs(px) - halfW + corner
        val qy = kotlin.math.abs(py) - halfH + corner
        val outside = kotlin.math.hypot(maxOf(qx, 0f), maxOf(qy, 0f))
        val sd = outside + minOf(maxOf(qx, qy), 0f) - corner
        return 1f + sd / rim
    }

    fun rb1(rb: Float) = ((1f - rb) * 8f).coerceIn(0f, 1f)
    fun rb2(rb: Float) = ((0.95f - rb * 0.95f) * 16f).coerceIn(0f, 1f) - ((0.9f - rb * 0.95f) * 16f).coerceIn(0f, 1f)
    fun rb3(rb: Float) = ((1.5f - rb * 1.1f) * 2f).coerceIn(0f, 1f) - ((1f - rb * 1.1f) * 2f).coerceIn(0f, 1f)

    /** How far a point's sample is pulled toward the centre: 1 is not at all, 0.5 at the edge. */
    fun lensFactor(rb: Float) = 1f - rb.coerceIn(0f, 1f) * 0.5f
}
