package xyz.heylana.app.orbs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * The port against thinking-orbs' own golden vectors: every dot of every frame the
 * library froze for Heylana's four states, at both shipped sizes and four moments,
 * position, radius, ink and alpha, in draw order, to the library's 1e-4 tolerance.
 * Extracted by scripts/orbs_golden.py.
 */
class OrbEngineGoldenTest {

    private class Case(val state: OrbState, val size: Int, val t: Double, val dots: List<DoubleArray>)
    private class Opts(val state: OrbState, val size: Int, val speed: Double, val opts: Map<String, Double>)

    private val lines: List<String> by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("orbs/golden.txt.gz"))
        GZIPInputStream(stream).bufferedReader().readLines()
    }

    private fun stateOf(key: String) = OrbState.entries.first { it.key == key }

    private val cases: List<Case> by lazy {
        val out = ArrayList<Case>()
        var i = 0
        while (i < lines.size) {
            val parts = lines[i].split(' ')
            if (parts[0] == "case") {
                val count = parts[4].toInt()
                val dots = (1..count).map { k -> lines[i + k].split(' ').map(String::toDouble).toDoubleArray() }
                out += Case(stateOf(parts[1]), parts[2].toInt(), parts[3].toDouble(), dots)
                i += count + 1
            } else {
                i++
            }
        }
        out
    }

    private val resolved: List<Opts> by lazy {
        lines.filter { it.startsWith("opts ") }.map { line ->
            val parts = line.split(' ')
            val opts = parts.drop(4).associate { it.substringBefore('=') to it.substringAfter('=').toDouble() }
            Opts(stateOf(parts[1]), parts[2].toInt(), parts[3].toDouble(), opts)
        }
    }

    @Test
    fun `all four states at both sizes and four moments are covered`() {
        assertEquals(32, cases.size)
        assertEquals(OrbState.entries.toSet(), cases.map { it.state }.toSet())
        assertEquals(8, resolved.size)
    }

    @Test
    fun `presets resolve to the library's options and speeds`() {
        for (expected in resolved) {
            val actual = OrbEngine.resolve(expected.state, expected.size)
            assertEquals("${expected.state} ${expected.size} speed", expected.speed, actual.speed, 1e-9)
            assertEquals("${expected.state} ${expected.size} keys", expected.opts.keys, actual.opts.keys)
            for ((key, value) in expected.opts) {
                assertEquals("${expected.state} ${expected.size} $key", value, actual.opts.getValue(key), 1e-9)
            }
        }
    }

    @Test
    fun `every dot matches the golden vectors`() {
        for (case in cases) {
            val frame = OrbEngine.frame(case.state, case.size, case.t)
            val label = "${case.state.key}-${case.size}-${case.t}"
            assertEquals("$label dot count", case.dots.size, frame.size)
            frame.forEachIndexed { index, dot ->
                val want = case.dots[index]
                val got = doubleArrayOf(dot.x, dot.y, dot.z, dot.r, dot.white, dot.a)
                for (field in 0 until 6) {
                    assertTrue(
                        "$label dot $index field $field: want ${want[field]} got ${got[field]}",
                        abs(want[field] - got[field]) <= TOLERANCE
                    )
                }
            }
        }
    }

    private companion object {
        const val TOLERANCE = 1e-4
    }
}
