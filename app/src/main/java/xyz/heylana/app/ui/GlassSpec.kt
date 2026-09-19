package xyz.heylana.app.ui

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Clear liquid glass: every number of the material, and the maths that uses them.
 *
 * The reference is design/refs/liquid_glass_render.py, a NumPy renderer (it works at
 * 2x, so its pixels are halved here into dp), tuned by the design brief for
 * design-2d-clear-glass. There is no colour in the material anywhere: white and black
 * at low alphas over whatever is behind, and the system's own blur behind the window.
 *
 * `d` is the signed distance to the surface's edge in px, negative inside; `n` is the
 * outward normal. Light comes from the top-left: `ndotl` is `n · LIGHT` and `ndotb`
 * is `n · -LIGHT`, clamped to [0, 1], with LIGHT = (-0.45, -0.89) in screen
 * coordinates (y down). So the rim is brightest along the top edge and the top-left
 * corner and shaded along the bottom and bottom-right, with the specular near the top.
 * (The renderer had the sign the other way round, lighting the bottom-right; that was
 * wrong and is corrected here.)
 *
 * The Kotlin functions are the same expressions as [AGSL]; GlassSpecTest checks them
 * against the renderer's and the brief's formulas. [ClearGlass] runs them per pixel on
 * API 33+ hardware canvases; below that [GlassDrawable] draws the same numbers as
 * gradients, without the refraction.
 */
object GlassSpec {

    /** One kind of surface: its corner, edge band E, refraction strength (all dp), and body magnification. */
    data class Surface(val radiusDp: Float, val edgeDp: Float, val strengthDp: Float, val magnification: Float)

    val PANEL = Surface(radiusDp = 20f, edgeDp = 30f, strengthDp = 34f, magnification = 0.985f)
    /** The 80dp disc; its radius is its half-size. */
    val DISC_OPEN = Surface(radiusDp = 40f, edgeDp = 20f, strengthDp = 30f, magnification = 0.96f)
    /** The docked 64dp disc. */
    val DISC_DOCKED = Surface(radiusDp = 32f, edgeDp = 16f, strengthDp = 24f, magnification = 0.96f)

    /** The disc between docked and open, for the swell in flight. */
    fun disc(discDp: Float): Surface {
        val f = ((discDp - 64f) / 16f).coerceIn(0f, 1f)
        return Surface(
            radiusDp = discDp / 2f,
            edgeDp = lerp(DISC_DOCKED.edgeDp, DISC_OPEN.edgeDp, f),
            strengthDp = lerp(DISC_DOCKED.strengthDp, DISC_OPEN.strengthDp, f),
            magnification = DISC_OPEN.magnification
        )
    }

    // ------------------------------------------------------------ numbers

    /** Light arrives from the top-left: (x, y) in screen coordinates, y down. */
    const val LIGHT_X = -0.45f
    const val LIGHT_Y = -0.89f

    const val BAND_FROM = 0.30f
    const val BAND_TO = 0.80f
    const val PULL_BASE = 0.45f
    const val PULL_DEPTH = 0.55f

    /** A permanent light smoke, no colour, so white text holds up over bright apps. */
    const val FILL_BLACK = 0.12f
    const val SATURATION = 1.18f
    const val BAND_BRIGHTNESS = 0.05f

    const val GRADIENT_TOP = 0.07f
    const val GRADIENT_BOTTOM = -0.04f
    /** The gradient is at full strength this far in from the edge. (The 30dp band is refraction only.) */
    const val GRADIENT_REACH_DP = 8f

    const val RIM_WIDTH = 0.075f
    const val RIM_BASE = 0.10f
    const val RIM_LIGHT = 0.32f
    const val RIM_SHADE = 0.10f

    const val LENS_LINE_AT = 0.10f
    const val LENS_LINE_WIDTH = 0.045f
    const val LENS_LINE = -0.03f

    const val BOTTOM_SHADE = -0.06f
    const val BOTTOM_SHADE_REACH = 0.9f

    const val SPECULAR = 0.05f

    const val HAIRLINE_PX = 1f
    const val HAIRLINE = 0.30f

    const val BLUR_BEHIND_DP = 8f
    const val BAND_BLUR_DP = 1f

    const val SHADOW_BLUR_DP = 7f
    const val SHADOW_ALPHA = 0.22f
    const val SHADOW_DY_DP = 8f

    const val CHIP_RADIUS_DP = 14f
    const val CHIP_HEIGHT_DP = 44f
    const val CHIP_UNSELECTED_BLACK = 0.27f
    const val CHIP_SELECTED_WHITE = 0.94f
    const val CHIP_SELECTED_TEXT = 0xFF1A1A24.toInt()
    /** A 1dp outline on the white chips, so they hold their shape over white. */
    const val CHIP_SELECTED_OUTLINE_BLACK = 0.15f
    const val CHIP_SELECTED_OUTLINE_DP = 1f

    const val TEXT_SHADOW_DY_DP = 1f
    const val TEXT_SHADOW_RADIUS_DP = 8f
    const val TEXT_SHADOW_BLACK = 0.40f

    /** "Darker glass" in Settings: up to 30% more black on top of the smoke, for light apps. */
    const val DARKER_BASE_BLACK = 0.30f

    const val MARK_ACTIVE = 1.0f
    const val MARK_DOCKED = 0.7f

    // ------------------------------------------------------ purple streak

    /**
     * A light streak in the accent behind the glass (variant C with motion, from
     * design/refs/liquid_glass_motion_render.py, which drew it purple): [HeylanaTokens.accent]
     * at 75%, handed to the shader as `streakColour` ([ClearGlass]), added into the
     * surface's own backing and bent by the same lens. Its lines run at 122°, so it
     * travels along 32° from off the top-left to off the bottom-right.
     */
    const val STREAK_MIX = 0.75f
    const val STREAK_ANGLE_DEG = 122f
    const val STREAK_WIDTH_DP = 30f
    const val STREAK_BREATH_DP = 8f
    const val STREAK_TRAIL_BEHIND_DP = 90f
    const val STREAK_TRAIL_STRENGTH = 0.35f
    const val STREAK_TRAIL_WIDTH_DP = 55f
    const val STREAK_PASS_MS = 6_000L
    const val STREAK_THINKING_PASS_MS = 4_000L
    const val STREAK_THINKING_STRENGTH = 0.95f
    /** The disc's streak: weaker and narrower, and its width scales with the disc (18dp at 80dp). */
    const val DISC_STREAK_STRENGTH = 0.6f
    const val DISC_STREAK_WIDTH_DP = 18f
    /** How much of a pane the streak may cover at once. */
    const val STREAK_MAX_COVER = 1f / 3f
    /** A gaussian of width w is visible over about ±2w. */
    private const val STREAK_VISIBLE_WIDTHS = 4f

    private val STREAK_TRAVEL_RAD = Math.toRadians((STREAK_ANGLE_DEG - 90f).toDouble())
    val STREAK_COS = kotlin.math.cos(STREAK_TRAVEL_RAD).toFloat()
    val STREAK_SIN = kotlin.math.sin(STREAK_TRAVEL_RAD).toFloat()

    /** Distance along the streak's travel of a point ([x], [y]) from the surface's top-left. */
    fun streakU(x: Float, y: Float): Float = x * STREAK_COS + y * STREAK_SIN

    /** The furthest [streakU] reaches on a surface of this size. */
    fun streakExtent(width: Float, height: Float): Float = width * STREAK_COS + height * STREAK_SIN

    /** The breathing width at [phase] (0 to 1 through a pass). */
    fun streakWidth(phase: Float, width: Float, breath: Float): Float =
        width + breath * kotlin.math.sin(phase * 2f * Math.PI.toFloat())

    /**
     * How much to narrow the streak so its widest breath covers at most a third of a
     * surface this far across: 1 when it already fits.
     */
    fun streakFit(extent: Float, width: Float, breath: Float): Float =
        min(1f, extent * STREAK_MAX_COVER / (STREAK_VISIBLE_WIDTHS * (width + breath)))

    /** Where the leading streak's centre is at [phase]: from fully off the top-left to the trail fully off the bottom-right. */
    fun streakCentre(phase: Float, extent: Float, width: Float, trailBehind: Float, trailWidth: Float): Float {
        val start = -2f * width
        val end = extent + trailBehind + 2f * trailWidth
        return start + (end - start) * phase
    }

    /** The streak's brightness at [u]: the leading gaussian plus the fainter trail 90dp behind. */
    fun streak(u: Float, centre: Float, width: Float, trailBehind: Float, trailWidth: Float): Float {
        val a = (u - centre) / width
        val b = (u - centre + trailBehind) / trailWidth
        return exp(-a * a) + STREAK_TRAIL_STRENGTH * exp(-b * b)
    }

    /**
     * The purple band, the aurora under the disc face, the chromatic rim and the
     * velocity RGB split from design-2c. Kept, and off.
     */
    const val TINTED_EXTRAS = false

    /** Set from Settings ("Darker glass"); read at draw time by every glass surface. */
    @Volatile
    var darkerGlass: Boolean = false

    // ------------------------------------------------------------- maths

    fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The renderer's `sd_rbox`: signed distance to a rounded box of half-size (hw, hh). */
    fun sdRoundBox(px: Float, py: Float, hw: Float, hh: Float, r: Float): Float {
        val qx = kotlin.math.abs(px) - hw + r
        val qy = kotlin.math.abs(py) - hh + r
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - r
    }

    /** 1 at the edge, fading out between 30% and 80% of E in. */
    fun band(d: Float, edge: Float): Float = 1f - smoothstep(BAND_FROM, BAND_TO, -d / edge)

    /** How far outward along the normal the surface's own content is sampled from, px. */
    fun pull(d: Float, edge: Float, strength: Float): Float {
        val t2 = -d / edge
        val deep = (1f - t2 / BAND_TO).coerceIn(0f, 1f)
        return strength * band(d, edge) * (PULL_BASE + PULL_DEPTH * deep)
    }

    /** 0 at the edge, 1 from [reach] px in. */
    fun depth(d: Float, reach: Float): Float = (-d / reach).coerceIn(0f, 1f)

    /** Facing the light (top-left): 1 on the top edge's top-left side. */
    fun ndotl(nx: Float, ny: Float): Float = (nx * LIGHT_X + ny * LIGHT_Y).coerceIn(0f, 1f)
    /** Facing away from the light: the bottom and bottom-right. */
    fun ndotb(nx: Float, ny: Float): Float = (nx * -LIGHT_X + ny * -LIGHT_Y).coerceIn(0f, 1f)

    /**
     * +0.07 at the top fading to -0.04 at the bottom ([yRel] 0 to 1), at full strength
     * [reach] px in from the edge (8dp), so there is no inner rectangle.
     */
    fun gradient(yRel: Float, d: Float, reach: Float): Float =
        lerp(GRADIENT_TOP, GRADIENT_BOTTOM, yRel.coerceIn(0f, 1f)) * depth(d, reach)

    /** The rim, 7.5% of E wide: lit toward the light, shaded on the far side. */
    fun rim(d: Float, edge: Float, nx: Float, ny: Float): Float {
        if (d > 0f) return 0f
        val w = (1f - (-d) / (RIM_WIDTH * edge)).coerceIn(0f, 1f)
        return w * (RIM_BASE + RIM_LIGHT * ndotl(nx, ny)) - w * RIM_SHADE * ndotb(nx, ny)
    }

    /** The dark lens line at 10% of E, 4.5% of E wide. */
    fun lensLine(d: Float, edge: Float): Float {
        val x = ((-d) - LENS_LINE_AT * edge) / (LENS_LINE_WIDTH * edge)
        return LENS_LINE * exp(-x * x)
    }

    /** The inner shadow on the far side, reaching 0.9E in. */
    fun bottomShade(d: Float, edge: Float, nx: Float, ny: Float): Float =
        BOTTOM_SHADE * ndotb(nx, ny) * (1f - (-d) / (BOTTOM_SHADE_REACH * edge)).coerceIn(0f, 1f)

    /** The 1px hairline just inside the boundary: white at this alpha. */
    fun hairline(d: Float, nx: Float, ny: Float): Float {
        if (d > 0f) return 0f
        val w = (1f - (-d) / HAIRLINE_PX).coerceIn(0f, 1f)
        return w * HAIRLINE * (0.3f + 0.7f * ndotl(nx, ny))
    }

    /** Everything added to a pixel inside the surface, as signed white (+) or black (-) alpha. */
    fun lighting(d: Float, edge: Float, nx: Float, ny: Float, yRel: Float, reach: Float): Float =
        BAND_BRIGHTNESS * band(d, edge) + gradient(yRel, d, reach) + rim(d, edge, nx, ny) +
            lensLine(d, edge) + bottomShade(d, edge, nx, ny)

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    // -------------------------------------------------------------- AGSL

    const val AGSL = """
uniform shader layer;
uniform float2 origin;
uniform float2 size;
uniform float corner;
uniform float edge;
uniform float strength;
uniform float magnification;
uniform float bandBlur;
uniform float2 specCentre;
uniform float2 specSize;
uniform float gradientReach;
uniform float streakCentre;
uniform float streakWidth;
uniform float streakTrailBehind;
uniform float streakTrailWidth;
uniform float streakStrength;
uniform half3 streakColour;

const float2 LIGHT = float2(-0.45, -0.89);

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half3 saturateColour(half3 c, float s) {
    half l = dot(c, half3(0.299, 0.587, 0.114));
    return half3(l) + (c - half3(l)) * s;
}

half4 main(float2 fragCoord) {
    float2 c = size * 0.5;
    float2 p = fragCoord - origin - c;
    float d = sdRoundBox(p, c, corner);
    float coverage = clamp(0.5 - d, 0.0, 1.0);
    if (coverage <= 0.0) return half4(0.0);

    // The outward normal, from the distance field.
    float2 n = float2(
        sdRoundBox(p + float2(0.5, 0.0), c, corner) - sdRoundBox(p - float2(0.5, 0.0), c, corner),
        sdRoundBox(p + float2(0.0, 0.5), c, corner) - sdRoundBox(p - float2(0.0, 0.5), c, corner)
    );
    n = n / max(length(n), 1e-4);
    // Light from the top-left: the top edge faces it, the bottom faces away.
    float ndotl = clamp(dot(n, LIGHT), 0.0, 1.0);
    float ndotb = clamp(dot(n, -LIGHT), 0.0, 1.0);

    float t2 = -d / edge;
    float band = 1.0 - smoothstep(0.30, 0.80, t2);
    float depth = clamp(-d / gradientReach, 0.0, 1.0);

    // Refraction of the surface's own layers: magnified toward the centre, pulled
    // outward along the normal in the band, with a 1dp blur there.
    float pull = strength * band * (0.45 + 0.55 * clamp(1.0 - t2 / 0.80, 0.0, 1.0));
    float2 s = origin + c + p * magnification + n * pull;
    float blur = bandBlur * band;
    half4 col = half4(0.0);
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            col += layer.eval(s + float2(float(x), float(y)) * blur);
        }
    }
    col /= 9.0;

    // The purple streak lies behind the glass, in the backing the lens bends: it is
    // evaluated where the lens samples, so it bends at the rim like everything else.
    if (streakStrength > 0.0) {
        float2 local = s - origin;
        float u = local.x * 0.848048 + local.y * 0.529919;
        float a = (u - streakCentre) / streakWidth;
        float b = (u - streakCentre + streakTrailBehind) / streakTrailWidth;
        float k = streakStrength * 0.75 * (exp(-a * a) + 0.35 * exp(-b * b));
        col.rgb += streakColour * k;
        col.a = clamp(col.a + k, 0.0, 1.0);
        col.rgb = min(col.rgb, half3(col.a));
    }

    if (col.a > 0.0) {
        col.rgb = saturateColour(col.rgb / col.a, 1.18) * col.a;
    }

    // Lighting, as signed alpha: white where positive, black where negative.
    float yRel = clamp((p.y + c.y) / size.y, 0.0, 1.0);
    float v = 0.05 * band;
    v += mix(0.07, -0.04, yRel) * depth;
    float rimW = clamp(1.0 - (-d) / (0.075 * edge), 0.0, 1.0);
    v += rimW * (0.10 + 0.32 * ndotl) - rimW * 0.10 * ndotb;
    float lx = ((-d) - 0.10 * edge) / (0.045 * edge);
    v += -0.03 * exp(-lx * lx);
    v += -0.06 * ndotb * clamp(1.0 - (-d) / (0.9 * edge), 0.0, 1.0);
    float2 sp = (fragCoord - specCentre) / specSize;
    v += 0.05 * exp(-dot(sp, sp) * 1.2);

    half4 outc = col;
    if (v < 0.0) {
        float k = clamp(-v, 0.0, 1.0);
        outc = half4(outc.rgb * (1.0 - k), outc.a + k * (1.0 - outc.a));
    } else {
        float k = clamp(v, 0.0, 1.0);
        outc = half4(outc.rgb * (1.0 - k) + half3(k), outc.a + k * (1.0 - outc.a));
    }

    // The 1px hairline at the boundary.
    float hair = clamp(1.0 - (-d), 0.0, 1.0) * 0.30 * (0.3 + 0.7 * ndotl);
    outc = half4(outc.rgb * (1.0 - hair) + half3(hair), outc.a + hair * (1.0 - outc.a));

    return outc * coverage;
}
"""
}

/**
 * The clear glass shader for one surface. Hardware canvases on API 33+ only; see
 * [GlassSpec] for what each uniform means.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class ClearGlass {
    private val shader = RuntimeShader(GlassSpec.AGSL).apply {
        // The streak is the accent, from the tokens: nothing in the shader carries a colour.
        val c = HeylanaTokens.accent
        setFloatUniform(
            "streakColour", android.graphics.Color.red(c) / 255f, android.graphics.Color.green(c) / 255f,
            android.graphics.Color.blue(c) / 255f
        )
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { shader = this@ClearGlass.shader }

    /**
     * [left], [top], [width], [height] place the surface on the canvas; [layer] is what
     * it refracts — its own frost and fill, never the screen; [density] turns the spec's
     * dp into px.
     */
    fun set(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        surface: GlassSpec.Surface,
        corner: Float,
        layer: Shader,
        density: Float
    ) {
        shader.setInputShader("layer", layer)
        shader.setFloatUniform("origin", left, top)
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("corner", corner)
        shader.setFloatUniform("edge", surface.edgeDp * density)
        shader.setFloatUniform("strength", surface.strengthDp * density)
        shader.setFloatUniform("magnification", surface.magnification)
        shader.setFloatUniform("bandBlur", GlassSpec.BAND_BLUR_DP * density)
        // The faint specular near the top-left: a wide, thin glint just under the top edge.
        shader.setFloatUniform("specCentre", left + width * 0.38f, top + 4f * density)
        shader.setFloatUniform("specSize", width * 0.30f, 3.5f * density)
        shader.setFloatUniform("gradientReach", GlassSpec.GRADIENT_REACH_DP * density)
    }

    /** The streak for this frame, in px; [strength] 0 turns it off. Uniforms only: nothing is allocated. */
    fun setStreak(centre: Float, width: Float, trailBehind: Float, trailWidth: Float, strength: Float) {
        shader.setFloatUniform("streakCentre", centre)
        shader.setFloatUniform("streakWidth", width.coerceAtLeast(1f))
        shader.setFloatUniform("streakTrailBehind", trailBehind)
        shader.setFloatUniform("streakTrailWidth", trailWidth.coerceAtLeast(1f))
        shader.setFloatUniform("streakStrength", strength)
    }
}
