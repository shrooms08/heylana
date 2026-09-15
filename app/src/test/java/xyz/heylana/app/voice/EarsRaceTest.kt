package xyz.heylana.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.voice.EarsRace.Ear
import xyz.heylana.app.voice.EarsRace.Verdict

/** Both ears listen; this decides whose words are used, in every order they can arrive. */
class EarsRaceTest {

    private val released = 10_000L

    private fun used(verdict: Verdict): Verdict.Use {
        assertTrue("expected words to be used, got $verdict", verdict is Verdict.Use)
        return verdict as Verdict.Use
    }

    @Test
    fun `nothing is decided before the user lets go`() {
        val race = EarsRace()
        assertEquals(Verdict.Wait, race.heard(Ear.ANDROID, 5_000, "hello"))
        assertEquals(Verdict.Wait, race.heard(Ear.DEEPGRAM, 5_100, "hello"))
    }

    @Test
    fun `Deepgram's words win when they arrive within a second and a half`() {
        val race = EarsRace()
        race.released(released)
        race.heard(Ear.ANDROID, released + 300, "come in oh earn")
        val use = used(race.heard(Ear.DEEPGRAM, released + 1_200, "Kamino earn"))
        assertEquals(Ear.DEEPGRAM, use.ear)
        assertEquals("Kamino earn", use.text)
    }

    @Test
    fun `the phone's words wait for Deepgram until the window closes`() {
        val race = EarsRace()
        race.released(released)
        assertEquals(Verdict.Wait, race.heard(Ear.ANDROID, released + 300, "where is the seed vault"))
        val use = used(race.tick(released + EarsRace.PREFER_DEEPGRAM_MS))
        assertEquals(Ear.ANDROID, use.ear)
        assertEquals("deepgram_late", use.reason)
    }

    @Test
    fun `a failed socket does not make the phone's words wait`() {
        val race = EarsRace()
        race.failed(Ear.DEEPGRAM, 9_000, "socket_error_401")
        race.released(released)
        val use = used(race.heard(Ear.ANDROID, released + 200, "what is my balance"))
        assertEquals(Ear.ANDROID, use.ear)
        assertEquals("deepgram_failed_socket_error_401", use.reason)
    }

    @Test
    fun `Deepgram hearing nothing hands over to the phone at once`() {
        val race = EarsRace()
        race.released(released)
        race.heard(Ear.ANDROID, released + 200, "how do I swap")
        val use = used(race.nothing(Ear.DEEPGRAM, released + 400))
        assertEquals(Ear.ANDROID, use.ear)
        assertEquals("deepgram_heard_nothing", use.reason)
    }

    @Test
    fun `late Deepgram words are still used when the phone heard nothing`() {
        val race = EarsRace()
        race.released(released)
        race.nothing(Ear.ANDROID, released + 300)
        val use = used(race.heard(Ear.DEEPGRAM, released + 2_000, "Kamino"))
        assertEquals(Ear.DEEPGRAM, use.ear)
    }

    @Test
    fun `neither ear hearing a word is nothing heard`() {
        val race = EarsRace()
        race.released(released)
        race.nothing(Ear.ANDROID, released + 300)
        assertEquals(Verdict.NothingHeard, race.nothing(Ear.DEEPGRAM, released + 900))
    }

    @Test
    fun `the trace from the Seeker ends in nothing heard, not a stuck wait`() {
        // Socket failed before release, the phone recogniser reported no match
        // five seconds later.
        val race = EarsRace()
        race.failed(Ear.DEEPGRAM, 32_822, "socket_error")
        race.released(32_375)
        assertEquals(Verdict.NothingHeard, race.nothing(Ear.ANDROID, 37_052))
    }

    @Test
    fun `a real problem from the phone is passed on when Deepgram has nothing`() {
        val race = EarsRace()
        race.released(released)
        race.failed(Ear.DEEPGRAM, released + 100, "socket_error")
        val verdict = race.failed(Ear.ANDROID, released + 200, "Voice input needs a connection")
        assertEquals(Verdict.Problem("Voice input needs a connection"), verdict)
    }

    @Test
    fun `a phone problem waits in case Deepgram still has the words`() {
        val race = EarsRace()
        race.released(released)
        assertEquals(Verdict.Wait, race.failed(Ear.ANDROID, released + 200, "busy"))
        val use = used(race.heard(Ear.DEEPGRAM, released + 900, "where is the seed vault"))
        assertEquals(Ear.DEEPGRAM, use.ear)
    }

    @Test
    fun `nothing waits past four seconds after the release`() {
        val race = EarsRace()
        race.released(released)
        assertEquals(Verdict.Wait, race.tick(released + EarsRace.GIVE_UP_MS - 1))
        assertEquals(Verdict.NothingHeard, race.tick(released + EarsRace.GIVE_UP_MS))
    }

    @Test
    fun `once decided, later reports change nothing`() {
        val race = EarsRace()
        race.released(released)
        race.nothing(Ear.ANDROID, released + 100)
        race.nothing(Ear.DEEPGRAM, released + 200)
        assertEquals(Verdict.Settled, race.heard(Ear.DEEPGRAM, released + 300, "too late"))
        assertTrue(race.settled)
    }

    @Test
    fun `empty words count as nothing`() {
        val race = EarsRace()
        race.released(released)
        race.heard(Ear.ANDROID, released + 100, "   ")
        assertEquals(Verdict.NothingHeard, race.heard(Ear.DEEPGRAM, released + 200, ""))
    }

    @Test
    fun `the windows are a second and a half and four seconds`() {
        assertEquals(1_500L, EarsRace.PREFER_DEEPGRAM_MS)
        assertEquals(4_000L, EarsRace.GIVE_UP_MS)
    }
}
