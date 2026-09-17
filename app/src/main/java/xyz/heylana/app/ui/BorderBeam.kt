package xyz.heylana.app.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/*
 * The rim beam: a port of the geometry of border-beam by Jakub Antalik, MIT licence
 * (https://github.com/Jakubantalik/Libraries.dev, packages/border-beam; its Metal port
 * in ports/ios/BorderBeamKit/Sources/BorderBeamKit/BeamShaders.metal).
 * Copyright (c) 2026 Jakub Antalik. Licence text: app/src/main/assets/licenses/border-beam.txt.
 *
 * What is ported: the rounded-rect border path parametrisation (`borderPathCoord`:
 * arc length clockwise from the top centre, distance inside the border, perimeter),
 * the piecewise stop lookup (`stopsAlpha`) and the rotate family's beam window
 * (`rotate.beamMaskStops` in spec/beam-spec.json). The library paints its beam from
 * stacks of blurred gradient blobs; here the lit window instead carries Heylana's four
 * aurora tokens along its length, with a soft gaussian glow across the rim.
 */
object BorderBeam {

    /** One lap of the rim. */
    const val LAP_MS = 1_600L

    /** The glow's gaussian width across the rim: panels, and the disc. */
    const val PANEL_GLOW_DP = 6f
    const val DISC_GLOW_DP = 4f

    /** Speaking: the beam's brightness rides the voice, never quite out. */
    const val SPEAKING_FLOOR = 0.35f

    /** The rotate family's beam window, [position %, alpha]: fully lit from 52% to 80% of a lap. */
    val BEAM_MASK_STOPS: Array<FloatArray> = arrayOf(
        floatArrayOf(0f, 0f), floatArrayOf(30f, 0f), floatArrayOf(36f, 0.1f), floatArrayOf(44f, 0.35f),
        floatArrayOf(52f, 1f), floatArrayOf(80f, 1f), floatArrayOf(86f, 0.35f), floatArrayOf(92f, 0.1f),
        floatArrayOf(95f, 0f), floatArrayOf(100f, 0f)
    )

    /** The metal port's `stopsAlpha`, with positions as fractions (0 to 1). */
    fun stopsAlpha(t: Float): Float {
        val stops = BEAM_MASK_STOPS
        if (t <= stops[0][0] / 100f) return stops[0][1]
        for (i in 1 until stops.size) {
            val p0 = stops[i - 1][0] / 100f
            val a0 = stops[i - 1][1]
            val p1 = stops[i][0] / 100f
            val a1 = stops[i][1]
            if (t <= p1) {
                val f = if (p1 > p0) (t - p0) / (p1 - p0) else 0f
                return a0 + (a1 - a0) * f
            }
        }
        return stops.last()[1]
    }

    /** Result of [borderPathCoord]: arc length, distance inside the border, perimeter. */
    data class PathCoord(val s: Float, val d: Float, val perimeter: Float)

    /** The metal port's `borderPathCoord`, for a point [x], [y] relative to the centre. */
    fun borderPathCoord(x: Float, y: Float, halfW: Float, halfH: Float, r: Float): PathCoord {
        val ex = max(halfW - r, 0f)
        val ey = max(halfH - r, 0f)
        val arc = 0.5f * PI.toFloat() * r
        val perimeter = 4f * ex + 4f * ey + 4f * arc
        val halfPi = 0.5f * PI.toFloat()
        val ax = abs(x)
        val ay = abs(y)
        if (ax > ex && ay > ey) {
            val kx = if (x >= 0f) ex else -ex
            val ky = if (y >= 0f) ey else -ey
            val vx = x - kx
            val vy = y - ky
            val d = r - hypot(vx, vy)
            val (a, base) = when {
                x >= 0f && y < 0f -> atan2(vx, -vy) to ex
                x >= 0f -> atan2(vy, vx) to ex + arc + 2f * ey
                y >= 0f -> atan2(-vx, vy) to 3f * ex + 2f * arc + 2f * ey
                else -> atan2(-vy, -vx) to 3f * ex + 3f * arc + 4f * ey
            }
            return PathCoord(base + r * a.coerceIn(0f, halfPi), d, perimeter)
        }
        val vertBand = when {
            ax > ex -> true
            ay > ey -> false
            else -> (halfW - ax) < (halfH - ay)
        }
        if (vertBand) {
            return if (x >= 0f) PathCoord(ex + arc + (y + ey), halfW - x, perimeter)
            else PathCoord(3f * ex + 3f * arc + 2f * ey + (ey - y), x + halfW, perimeter)
        }
        if (y < 0f) return PathCoord(if (x >= 0f) x else perimeter + x, y + halfH, perimeter)
        return PathCoord(ex + 2f * arc + 2f * ey + (ex - x), halfH - y, perimeter)
    }

    /** How lit a point [s] along a rim of [perimeter] is at [phase] (0 to 1 through a lap). */
    fun window(s: Float, perimeter: Float, phase: Float): Float {
        val f = ((s / perimeter - phase) % 1f + 1f) % 1f
        return stopsAlpha(f)
    }

    /** The whole beam at a point: window along the rim times a gaussian across it. */
    fun intensity(x: Float, y: Float, halfW: Float, halfH: Float, r: Float, phase: Float, glow: Float): Float {
        val c = borderPathCoord(x, y, halfW, halfH, r)
        val across = c.d / glow
        return window(c.s, c.perimeter, phase) * exp(-across * across)
    }

    const val AGSL = """
uniform float2 origin;
uniform float2 size;
uniform float corner;
uniform float phase;
uniform float strength;
uniform float glow;
uniform half4 c0;
uniform half4 c1;
uniform half4 c2;
uniform half4 c3;

const float PI = 3.14159265;

float stopsAlpha(float t) {
    if (t <= 0.30) return 0.0;
    if (t <= 0.36) return mix(0.0, 0.1, (t - 0.30) / 0.06);
    if (t <= 0.44) return mix(0.1, 0.35, (t - 0.36) / 0.08);
    if (t <= 0.52) return mix(0.35, 1.0, (t - 0.44) / 0.08);
    if (t <= 0.80) return 1.0;
    if (t <= 0.86) return mix(1.0, 0.35, (t - 0.80) / 0.06);
    if (t <= 0.92) return mix(0.35, 0.1, (t - 0.86) / 0.06);
    if (t <= 0.95) return mix(0.1, 0.0, (t - 0.92) / 0.03);
    return 0.0;
}

float3 borderPathCoord(float2 rel, float2 halfSize, float r) {
    float ex = max(halfSize.x - r, 0.0);
    float ey = max(halfSize.y - r, 0.0);
    float arc = 0.5 * PI * r;
    float P = 4.0 * ex + 4.0 * ey + 4.0 * arc;
    float halfPi = 0.5 * PI;
    float x = rel.x;
    float y = rel.y;
    float ax = abs(x);
    float ay = abs(y);
    if (ax > ex && ay > ey) {
        float2 k = float2(x >= 0.0 ? ex : -ex, y >= 0.0 ? ey : -ey);
        float2 v = rel - k;
        float d = r - length(v);
        float a;
        float base;
        if (x >= 0.0 && y < 0.0) { a = atan(v.x, -v.y); base = ex; }
        else if (x >= 0.0) { a = atan(v.y, v.x); base = ex + arc + 2.0 * ey; }
        else if (y >= 0.0) { a = atan(-v.x, v.y); base = 3.0 * ex + 2.0 * arc + 2.0 * ey; }
        else { a = atan(-v.y, -v.x); base = 3.0 * ex + 3.0 * arc + 4.0 * ey; }
        return float3(base + r * clamp(a, 0.0, halfPi), d, P);
    }
    bool vertBand;
    if (ax > ex) vertBand = true;
    else if (ay > ey) vertBand = false;
    else vertBand = (halfSize.x - ax) < (halfSize.y - ay);
    if (vertBand) {
        if (x >= 0.0) return float3(ex + arc + (y + ey), halfSize.x - x, P);
        return float3(3.0 * ex + 3.0 * arc + 2.0 * ey + (ey - y), x + halfSize.x, P);
    }
    if (y < 0.0) return float3(x >= 0.0 ? x : P + x, y + halfSize.y, P);
    return float3(ex + 2.0 * arc + 2.0 * ey + (ex - x), halfSize.y - y, P);
}

half4 main(float2 fragCoord) {
    float2 halfSize = size * 0.5;
    float2 rel = fragCoord - origin - halfSize;
    float3 bp = borderPathCoord(rel, halfSize, corner);
    float f = fract(bp.x / bp.z - phase);
    float w = stopsAlpha(f);
    if (w <= 0.0) return half4(0.0);
    float across = bp.y / glow;
    float a = w * exp(-across * across) * strength;
    // The aurora along the lit window: violet at the tail to orange at the head.
    float t = clamp((f - 0.30) / 0.65, 0.0, 1.0) * 3.0;
    half3 col = t < 1.0 ? mix(c0.rgb, c1.rgb, t) : (t < 2.0 ? mix(c1.rgb, c2.rgb, t - 1.0) : mix(c2.rgb, c3.rgb, t - 2.0));
    return half4(col * a, a);
}
"""
}

/** The rim beam for one surface. Hardware canvases on API 33+ only. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class BeamShader {
    private val shader = RuntimeShader(BorderBeam.AGSL).apply {
        val stops = HeylanaTokens.auroraStops
        for (i in 0 until 4) {
            val c = stops[i]
            setFloatUniform(
                "c$i",
                android.graphics.Color.red(c) / 255f, android.graphics.Color.green(c) / 255f,
                android.graphics.Color.blue(c) / 255f, 1f
            )
        }
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        // Ordinary alpha over the glass, so the glow reads over a white app as well as black.
        shader = this@BeamShader.shader
    }

    /** Uniforms only: nothing is allocated per frame. */
    fun set(left: Float, top: Float, width: Float, height: Float, corner: Float, phase: Float, strength: Float, glow: Float) {
        shader.setFloatUniform("origin", left, top)
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("corner", corner)
        shader.setFloatUniform("phase", phase)
        shader.setFloatUniform("strength", strength)
        shader.setFloatUniform("glow", glow.coerceAtLeast(1f))
    }
}
