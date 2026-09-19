package xyz.heylana.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.voice.EarsRace.Ear
import xyz.heylana.app.voice.EarsRace.Verdict

class ThreeEarsRaceTest {

    private fun race() = EarsRace(racing = setOf(Ear.DEEPGRAM, Ear.ASSEMBLYAI, Ear.ANDROID))

    @Test
    fun `both cloud finals in, the higher confidence wins, whichever came first`() {
        val r = race()
        r.released(0)
        assertEquals(Verdict.Wait, r.heard(Ear.DEEPGRAM, 600, "next song", 0.88f))
        val v = r.heard(Ear.ASSEMBLYAI, 800, "Next song.", 0.95f) as Verdict.Use
        assertEquals(Ear.ASSEMBLYAI, v.ear)
        assertEquals("assemblyai_higher_confidence", v.reason)
        assertEquals(0.95f, v.confidence)
    }

    @Test
    fun `the first cloud final waits only a moment for the other`() {
        val r = race()
        r.released(0)
        assertEquals(Verdict.Wait, r.heard(Ear.ASSEMBLYAI, 500, "torch on", 0.9f))
        assertEquals(Verdict.Wait, r.tick(500 + EarsRace.COMPARE_MS - 1))
        val v = r.tick(500 + EarsRace.COMPARE_MS) as Verdict.Use
        assertEquals(Ear.ASSEMBLYAI, v.ear)
        assertEquals("assemblyai_in_time", v.reason)
        // Deepgram's late words change nothing.
        assertEquals(Verdict.Settled, r.heard(Ear.DEEPGRAM, 1_200, "torch on", 0.99f))
    }

    @Test
    fun `the other cloud ear known to have nothing, the first is used at once`() {
        val r = race()
        r.released(0)
        r.failed(Ear.ASSEMBLYAI, 10, "token_refused_503")
        val v = r.heard(Ear.DEEPGRAM, 700, "turn it off", 0.9f) as Verdict.Use
        assertEquals(Ear.DEEPGRAM, v.ear)
    }

    @Test
    fun `a tie goes to the first`() {
        val r = race()
        r.released(0)
        r.heard(Ear.DEEPGRAM, 600, "a", 0.9f)
        val v = r.heard(Ear.ASSEMBLYAI, 700, "b", 0.9f) as Verdict.Use
        assertEquals(Ear.DEEPGRAM, v.ear)
        assertEquals("deepgram_first_on_tie", v.reason)
    }

    @Test
    fun `no cloud words, the phone's are used once both cloud ears are done`() {
        val r = race()
        r.released(0)
        r.heard(Ear.ANDROID, 300, "next song")
        r.nothing(Ear.DEEPGRAM, 900)
        val v = r.nothing(Ear.ASSEMBLYAI, 1_000) as Verdict.Use
        assertEquals(Ear.ANDROID, v.ear)
        assertEquals("deepgram_assemblyai_heard_nothing", v.reason)
    }

    @Test
    fun `a forced ear races alone`() {
        val r = EarsRace(racing = setOf(Ear.ASSEMBLYAI))
        r.released(0)
        val v = r.heard(Ear.ASSEMBLYAI, 400, "torch on", 0.8f) as Verdict.Use
        assertEquals(Ear.ASSEMBLYAI, v.ear)
        val silent = EarsRace(racing = setOf(Ear.ASSEMBLYAI))
        silent.released(0)
        assertEquals(Verdict.NothingHeard, silent.nothing(Ear.ASSEMBLYAI, 400))
    }

    @Test
    fun `the summary says each ear's confidence and time, never words`() {
        val r = race()
        r.released(1_000)
        r.heard(Ear.DEEPGRAM, 1_820, "secret words", 0.91f)
        r.heard(Ear.ASSEMBLYAI, 1_640, "secret words", 0.95f)
        val s = r.summary()
        assertEquals("deepgram=0.91/820ms assemblyai=0.95/640ms android=pending", s)
        assertTrue(!s.contains("secret"))
    }
}
