package xyz.heylana.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock that says how long an answer took to start, and the pipe the one-trip answer's
 * audio travels down. Both are plain arithmetic and threads, so both are tested here.
 */
class SpeedTest {

    @Test
    fun `two trips add up to the total`() {
        val clock = AnswerClock(1_000)
        clock.heard(1_900)      // 900ms of ears
        clock.asked(1_950)
        clock.answered(3_700)   // 1750ms of model
        clock.voiceAsked(3_750)
        clock.firstAudio(4_400) // 650ms to the first audio
        clock.spoke(4_500)      // 100ms of pre-roll

        assertEquals(900, clock.earsMs)
        assertEquals(1_750, clock.brainMs)
        assertEquals(650, clock.ttsFirstByteMs)
        assertEquals(100, clock.playMs)
        assertEquals(3_500, clock.totalMs)
        val line = clock.line()
        assertTrue(line, line.contains("total_ms=3500"))
        assertTrue(line, line.contains("trips=two"))
        // What is left over is the handing about between them, and it is small.
        assertTrue(line, line.contains("rest_ms=100"))
    }

    @Test
    fun `one trip does not count the model twice`() {
        val clock = AnswerClock(1_000)
        clock.heard(1_900)
        clock.asked(1_950)
        clock.voiceRidesWithTheQuestion()
        clock.firstAudio(3_500)
        clock.spoke(3_600)
        // The answer itself lands after the first word is already being said.
        clock.answered(4_800)

        assertEquals(900, clock.earsMs)
        assertEquals(2_850, clock.brainMs)
        // The voice was asked for with the question, so this is the whole way to the first sound.
        assertEquals(1_550, clock.ttsFirstByteMs)
        assertEquals(2_600, clock.totalMs)
        val line = clock.line()
        assertTrue(line, line.contains("trips=one"))
        // ears 900 + 1550 + 100 = 2550 of the 2600; the model is inside that, not added to it.
        assertTrue(line, line.contains("rest_ms=50"))
    }

    @Test
    fun `the line waits for the answer as well as the first word`() {
        val clock = AnswerClock(0)
        clock.asked(10)
        clock.spoke(100)
        assertTrue(clock.done)
        assertTrue("the answer is not back yet", !clock.complete)
        clock.answered(200)
        assertTrue(clock.complete)
    }

    @Test
    fun `a stretch that never happened is not guessed at`() {
        val clock = AnswerClock(1_000)
        clock.spoke(2_000)
        assertEquals(-1, clock.earsMs)
        assertEquals(-1, clock.brainMs)
        assertEquals(1_000, clock.totalMs)
    }

    @Test
    fun `the pipe hands every byte over in order and then ends`() {
        val pipe = PcmPipe()
        pipe.write(byteArrayOf(1, 2, 3))
        pipe.write(byteArrayOf(4, 5))
        pipe.finish()

        val out = pipe.readBytes()
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), out.toList())
        assertEquals(-1, pipe.read())
    }

    @Test
    fun `a read waits for audio that has not arrived yet`() {
        val pipe = PcmPipe()
        val writer = Thread {
            Thread.sleep(150)
            pipe.write(ByteArray(64) { 7 })
            Thread.sleep(50)
            pipe.finish()
        }
        writer.start()

        val started = System.currentTimeMillis()
        val out = pipe.readBytes()
        writer.join()
        assertEquals(64, out.size)
        assertTrue("it should have waited", System.currentTimeMillis() - started >= 150)
    }

    @Test
    fun `a chunk larger than the read buffer comes over in pieces`() {
        val pipe = PcmPipe()
        pipe.write(ByteArray(10) { it.toByte() })
        pipe.finish()

        val small = ByteArray(4)
        assertEquals(4, pipe.read(small, 0, 4))
        assertEquals(listOf<Byte>(0, 1, 2, 3), small.toList())
        assertEquals(4, pipe.read(small, 0, 4))
        assertEquals(listOf<Byte>(4, 5, 6, 7), small.toList())
        assertEquals(2, pipe.read(small, 0, 4))
        assertEquals(-1, pipe.read(small, 0, 4))
    }
}
