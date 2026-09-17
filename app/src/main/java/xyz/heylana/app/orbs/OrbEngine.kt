package xyz.heylana.app.orbs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * A port of the geometry engine of thinking-orbs 0.3.1 by Jakub Antalik, MIT
 * licence (https://github.com/Jakubantalik/Libraries.dev, packages/thinking-orbs).
 * Copyright (c) 2026 Jakub Antalik. The full licence text is in
 * app/src/main/assets/licenses/thinking-orbs.txt.
 *
 * Only the four states Heylana uses are ported — working (orbits), listening
 * (wave), composing (ribbon) and breathing (ring, ribbon's face-on variant) —
 * and each frame function is a line-for-line translation of the TypeScript in
 * src/engine/, checked against the library's own golden vectors
 * (spec/orbs-golden.json) in OrbEngineGoldenTest. Nothing here draws: a frame is
 * a finished, z-sorted list of dots, exactly as the library's `finalizeFrame`
 * returns it.
 */

/** One dot of a frame. [white] is ink on paper (0 darkest); a dark substrate mirrors it. */
data class Dot(val x: Double, val y: Double, val z: Double, var r: Double, val white: Double, val a: Double = 1.0)

/** The orb states Heylana draws, by the library's names. */
enum class OrbState(val key: String, val mode: Mode) {
    WORKING("working", Mode.ORBITS),
    LISTENING("listening", Mode.WAVE),
    COMPOSING("composing", Mode.RIBBON),
    BREATHING("breathing", Mode.RING);

    enum class Mode { ORBITS, WAVE, RIBBON, RING }
}

/** A resolved (state, size): its clock speed and fully scaled options. */
data class OrbPreset(val speed: Double, val opts: Map<String, Double>)

object OrbEngine {

    /** Sizes the library ships tunings for. */
    val SIZES = listOf(64, 32, 20)

    fun frame(state: OrbState, size: Int, t: Double): List<Dot> {
        val opts = resolve(state, size).opts
        return when (state.mode) {
            OrbState.Mode.ORBITS -> frameOrbits(size.toDouble(), t, opts)
            OrbState.Mode.WAVE -> frameWave(size.toDouble(), t, opts)
            OrbState.Mode.RIBBON, OrbState.Mode.RING -> frameRibbon(size.toDouble(), t, opts)
        }
    }

    // ------------------------------------------------------------ core.ts

    /** JavaScript's Math.round: halves go up, toward positive infinity. */
    internal fun jsRound(x: Double): Double = floor(x + 0.5)

    fun hashD(a: Double, b: Double): Double {
        val h = sin(a * 12.9898 + b * 78.233) * 43758.5453
        return h - floor(h)
    }

    fun fibDir(i: Int, n: Int): DoubleArray {
        val golden = PI * (3 - sqrt(5.0))
        val y = 1 - (2 * (i + 0.5)) / n
        val rad = sqrt(1 - y * y)
        val a = i * golden
        return doubleArrayOf(rad * cos(a), y, rad * sin(a))
    }

    fun angleDelta(a: Double, b: Double): Double = atan2(sin(a - b), cos(a - b))

    /** Shared spin + tilt + orthographic projection. */
    class Projector(yaw: Double, tilt: Double, private val cx: Double, private val cy: Double, private val scale: Double) {
        private val st = sin(tilt)
        private val ct = cos(tilt)
        private val sy = sin(yaw)
        private val cyw = cos(yaw)

        fun project(x: Double, y: Double, z: Double): DoubleArray {
            val x1 = x * cyw + z * sy
            val z1 = -x * sy + z * cyw
            val y1 = y * ct - z1 * st
            val z2 = y * st + z1 * ct
            return doubleArrayOf(cx + x1 * scale, cy - y1 * scale, z2)
        }
    }

    fun radiusScale(size: Double, pow: Double): Double = (size / 300).pow(pow)

    /** Drops invisible dots, clamps radii to the floor, and sorts far to near. Stable, as JS sort is. */
    fun finalizeFrame(dots: MutableList<Dot>, rMin: Double?): List<Dot> {
        val floorR = rMin ?: 0.3
        val visible = ArrayList<Dot>(dots.size)
        for (d in dots) {
            if (d.a < 0.02) continue
            d.r = max(floorR, d.r)
            visible.add(d)
        }
        return visible.sortedBy { it.z }
    }

    // --------------------------------------------------------- orbits.ts

    fun frameOrbits(size: Double, t: Double, o: Map<String, Double>): List<Dot> {
        val cx = size / 2
        val cy = size / 2
        val bigR = (size / 2) * 0.82
        val pt = Projector(t * 0.12, 0.3, cx, cy, 1.0)
        val rs = radiusScale(size, o["rsPow"] ?: 0.6)

        val dots = ArrayList<Dot>()
        val orbitN = (o["orbitN"] ?: 12.0).toInt()
        val ghostN = (o["ghostN"] ?: 40.0).toInt()
        val particles = (o["particles"] ?: 3.0).toInt()

        for (orb in 0 until orbitN) {
            val h1 = hashD(orb.toDouble(), 1.7)
            val h2 = hashD(orb.toDouble(), 5.2)
            val h3 = hashD(orb.toDouble(), 8.9)
            val ro = bigR * (0.45 + 0.52 * h1)
            val th = h1 * 2 * PI
            val phi = acos(2 * h2 - 1)
            val nx = sin(phi) * cos(th)
            val ny = cos(phi)
            val nz = sin(phi) * sin(th)
            var ux = -ny
            var uy = nx
            val uz = 0.0
            val ul = max(1e-6, sqrt(ux * ux + uy * uy))
            ux /= ul
            uy /= ul
            val vx = ny * uz - nz * uy
            val vy = nz * ux - nx * uz
            val vz = nx * uy - ny * ux
            val speed = (0.25 + 0.55 * h3) * (if (h3 > 0.5) 1 else -1)

            for (k in 0 until ghostN) {
                val a = (k.toDouble() / ghostN) * 2 * PI
                val p = pt.project(
                    (ux * cos(a) + vx * sin(a)) * ro,
                    (uy * cos(a) + vy * sin(a)) * ro,
                    (uz * cos(a) + vz * sin(a)) * ro
                )
                val depth = (p[2] / ro + 1) / 2
                dots.add(
                    Dot(p[0], p[1], p[2], (o["ghostR"] ?: 0.9) * rs, 0.72, (o["ghostA"] ?: 0.5) * (0.4 + 0.6 * depth))
                )
            }
            for (m in 0 until particles) {
                val a = t * speed + (m.toDouble() / particles) * 2 * PI + h2 * 6
                val p = pt.project(
                    (ux * cos(a) + vx * sin(a)) * ro,
                    (uy * cos(a) + vy * sin(a)) * ro,
                    (uz * cos(a) + vz * sin(a)) * ro
                )
                val depth = (p[2] / ro + 1) / 2
                dots.add(
                    Dot(
                        p[0], p[1], p[2],
                        ((o["partR"] ?: 1.2) + (o["partRDepth"] ?: 1.6) * depth) * rs,
                        0.3 - 0.22 * depth
                    )
                )
            }
        }
        return finalizeFrame(dots, o["rMin"])
    }

    // -------------------------------------------------------- lattice.ts

    fun frameWave(size: Double, t: Double, o: Map<String, Double>): List<Dot> {
        val cx = size / 2
        val cy = size / 2
        val bigR = (size / 2) * 0.874
        val pt = Projector(t * 0.18, 0.38, cx, cy, 1.0)
        val rs = radiusScale(size, o["rsPow"] ?: 0.6)

        val dots = ArrayList<Dot>()
        val rings = (o["rings"] ?: 15.0).toInt()
        val lonDensity = o["lonDensity"] ?: 40.0
        for (ri in 0..rings) {
            val lat = -PI / 2 + (ri.toDouble() / rings) * PI
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            val w = 0.62 * sin(t * 2.1 - ri * 0.52) + 0.38 * sin(t * 1.27 + ri * 0.83)
            val rr = bigR * (0.88 + 0.105 * w)
            val lonCount = max(1.0, jsRound(abs(cosLat) * lonDensity)).toInt()
            for (lj in 0 until lonCount) {
                val lon = (lj.toDouble() / lonCount) * 2 * PI
                val p = pt.project(cosLat * cos(lon) * rr, sinLat * rr, cosLat * sin(lon) * rr)
                val depth = (p[2] / bigR + 1) / 2
                val crest = max(0.0, w)
                dots.add(
                    Dot(
                        p[0], p[1], p[2],
                        ((o["rBase"] ?: 0.6) + (o["rDepth"] ?: 1.7) * depth) * (1 + 0.4 * crest) * rs,
                        0.66 - 0.56 * depth - 0.1 * crest
                    )
                )
            }
        }
        return finalizeFrame(dots, o["rMin"])
    }

    // --------------------------------------------------------- ribbon.ts

    fun frameRibbon(size: Double, t: Double, o: Map<String, Double>): List<Dot> {
        val cx = size / 2
        val cy = size / 2
        val bigR = (size / 2) * 0.78
        val spin = o["spin"] ?: 1.0
        val camTilt = 0.3
        val pt = Projector(t * 0.1 * spin, camTilt, cx, cy, 1.0)
        val rs = radiusScale(size, o["rsPow"] ?: 0.6)
        val faceOn = (o["faceOn"] ?: 0.0) != 0.0

        val dots = ArrayList<Dot>()
        val ghostN = (o["ghostN"] ?: 150.0).toInt()
        for (i in 0 until ghostN) {
            val d = fibDir(i, ghostN)
            val p = pt.project(d[0] * bigR, d[1] * bigR, d[2] * bigR)
            val depth = (p[2] / bigR + 1) / 2
            dots.add(Dot(p[0], p[1], p[2], 0.8 * rs, 0.78, 0.1 + 0.22 * depth))
        }

        val ya = t * 0.24 * spin
        val ta = if (faceOn) -camTilt else 0.55 + 0.3 * sin(t * 0.18) * spin
        val ux = cos(ya)
        val uy = 0.0
        val uz = sin(ya)
        val vx = -uz * sin(ta)
        val vy = cos(ta)
        val vz = ux * sin(ta)
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx

        val wobMul = o["wobMul"] ?: 1.0
        val wobAmp = 0.23 * wobMul
        val baseR = if (faceOn) bigR / (1 + 0.85 * wobAmp) else bigR

        val baseLanes = o["lanes"] ?: 5.0
        val segs = (o["segs"] ?: 88.0).toInt()
        val lanes = max(1.0, jsRound(baseLanes * (o["bandMul"] ?: 1.0))).toInt()
        for (w in 0 until lanes) {
            val laneOff = (w - (lanes - 1) / 2.0) * 0.075
            val edge = abs(w - (lanes - 1) / 2.0) / max(1.0, (lanes - 1) / 2.0)
            for (k in 0 until segs) {
                val a = (k.toDouble() / segs) * 2 * PI
                val wob = (0.16 * sin(a * 3 - t * 1.7 + w * 0.22) + 0.07 * sin(a * 5 + t * 1.1)) * wobMul
                val radial = if (faceOn) 1 + wob else 1.0
                val off = if (faceOn) laneOff else laneOff + wob
                val x = ux * cos(a) + vx * sin(a) + nx * off
                val y = uy * cos(a) + vy * sin(a) + ny * off
                val z = uz * cos(a) + vz * sin(a) + nz * off
                val l = sqrt(x * x + y * y + z * z)
                val rr = baseR * radial
                val p = pt.project((x / l) * rr, (y / l) * rr, (z / l) * rr)
                val depth = (p[2] / bigR + 1) / 2
                dots.add(
                    Dot(
                        p[0], p[1], p[2],
                        ((o["rBase"] ?: 1.1) + (o["rDepth"] ?: 1.7) * depth) * (1 - 0.25 * edge) * rs,
                        0.52 - 0.44 * depth + 0.18 * edge,
                        0.4 + 0.6 * depth
                    )
                )
            }
        }
        return finalizeFrame(dots, o["rMin"])
    }

    // ------------------------------------------------ profiles.ts, presets.ts

    private val COUNT_PAIRS = listOf("latRings" to "lonDensity", "rings" to "lonDensity", "lanes" to "segs")
    private val COUNT_KEYS = listOf("orbitN", "ghostN", "nodeN", "strandN", "signals")
    private val RADIUS_KEYS = listOf("rBase", "rDepth", "rActive", "rDot", "ghostR", "partR", "partRDepth", "nodeR", "nodeRDepth")

    fun scaleCounts(opts: Map<String, Double>, scale: Double): Map<String, Double> {
        val out = LinkedHashMap(opts)
        val done = HashSet<String>()
        val rt = sqrt(scale)
        for ((a, b) in COUNT_PAIRS) {
            val va = out[a]
            val vb = out[b]
            if (va != null && vb != null && a !in done && b !in done) {
                out[a] = max(2.0, jsRound(va * rt))
                out[b] = max(2.0, jsRound(vb * rt))
                done += a
                done += b
            }
        }
        for (k in COUNT_KEYS) {
            val v = out[k]
            if (v != null && v != 0.0 && k !in done) out[k] = max(1.0, jsRound(v * scale))
        }
        return out
    }

    fun scaleRadii(opts: Map<String, Double>, scale: Double): Map<String, Double> {
        val out = LinkedHashMap(opts)
        for (k in RADIUS_KEYS) out[k]?.let { out[k] = it * scale }
        out["rSizeMul"] = (out["rSizeMul"] ?: 1.0) * scale
        return out
    }

    private val BASE_PROFILES: Map<OrbState.Mode, Map<String, Double>> = mapOf(
        OrbState.Mode.ORBITS to mapOf(
            "orbitN" to 12.0, "ghostN" to 40.0, "ghostR" to 0.9, "ghostA" to 0.5, "particles" to 3.0,
            "partR" to 1.2, "partRDepth" to 1.6, "rsPow" to 0.6, "rMin" to 0.3
        ),
        OrbState.Mode.WAVE to mapOf(
            "rings" to 15.0, "lonDensity" to 40.0, "rBase" to 0.6, "rDepth" to 1.7, "rsPow" to 0.6, "rMin" to 0.3
        ),
        OrbState.Mode.RIBBON to mapOf(
            "lanes" to 5.0, "segs" to 88.0, "ghostN" to 150.0, "rBase" to 1.1, "rDepth" to 1.7, "rsPow" to 0.6, "rMin" to 0.3
        ),
        OrbState.Mode.RING to mapOf(
            "lanes" to 5.0, "segs" to 88.0, "ghostN" to 0.0, "faceOn" to 1.0, "rBase" to 1.1, "rDepth" to 1.7,
            "rsPow" to 0.6, "rMin" to 0.3
        )
    )

    private class Tuning(val speed: Double, val count: Double, val size: Double, val extra: Map<String, Double> = emptyMap())

    private val PRESETS: Map<OrbState.Mode, Map<Int, Tuning>> = mapOf(
        OrbState.Mode.ORBITS to mapOf(
            64 to Tuning(1.885, 1.0, 1.0),
            32 to Tuning(2.9072, 0.4251, 1.6849),
            20 to Tuning(3.9, 0.238, 2.4)
        ),
        OrbState.Mode.WAVE to mapOf(
            64 to Tuning(4.388, 0.341, 1.0),
            32 to Tuning(4.1512, 0.169, 1.3232),
            20 to Tuning(3.998, 0.105, 1.6)
        ),
        OrbState.Mode.RIBBON to mapOf(
            64 to Tuning(2.34, 0.25, 0.85, mapOf("spin" to 0.0, "bandMul" to 3.9, "wobMul" to 1.0)),
            32 to Tuning(2.7776, 0.0969, 0.9766, mapOf("spin" to 0.0, "bandMul" to 4.49, "wobMul" to 1.0)),
            20 to Tuning(3.12, 0.051, 1.073, mapOf("spin" to 0.0, "bandMul" to 4.94, "wobMul" to 1.0))
        ),
        OrbState.Mode.RING to mapOf(
            64 to Tuning(3.24, 0.25, 0.956, mapOf("spin" to 0.0, "bandMul" to 3.627, "wobMul" to 0.368)),
            32 to Tuning(3.5517, 0.0678, 1.31, mapOf("spin" to 0.0, "bandMul" to 3.8265, "wobMul" to 0.4751)),
            20 to Tuning(3.78, 0.028, 1.622, mapOf("spin" to 0.0, "bandMul" to 3.968, "wobMul" to 0.565))
        )
    )

    private val cache = HashMap<Pair<OrbState, Int>, OrbPreset>()

    /** The library's `resolvePreset`: base profile, count and radius multipliers, then the extras. */
    fun resolve(state: OrbState, size: Int): OrbPreset = synchronized(cache) {
        cache.getOrPut(state to size) {
            val tuning = requireNotNull(PRESETS.getValue(state.mode)[size]) { "no tuning for size $size" }
            var opts: Map<String, Double> = BASE_PROFILES.getValue(state.mode)
            if (tuning.count != 1.0) opts = scaleCounts(opts, tuning.count)
            if (tuning.size != 1.0) opts = scaleRadii(opts, tuning.size)
            if (tuning.extra.isNotEmpty()) opts = opts + tuning.extra
            OrbPreset(tuning.speed, opts)
        }
    }

    internal fun clamp01(x: Double) = min(1.0, max(0.0, x))
}
