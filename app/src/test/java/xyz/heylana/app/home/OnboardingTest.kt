package xyz.heylana.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.brain.SpokenName

class OnboardingTest {

    @Test
    fun `the steps come in the order she speaks them`() {
        var step = OnboardingStep.WELCOME
        val walked = mutableListOf(step)
        repeat(OnboardingFlow.order.size) {
            step = OnboardingFlow.next(step)
            if (step != OnboardingStep.DONE) walked += step
        }
        assertEquals(
            listOf(
                OnboardingStep.WELCOME,
                OnboardingStep.PERMISSIONS,
                OnboardingStep.WALLET,
                OnboardingStep.NAME,
                OnboardingStep.SIGNING,
                OnboardingStep.HANDOVER
            ),
            walked
        )
        // And it ends there: nothing after the handover but Home.
        assertEquals(OnboardingStep.DONE, OnboardingFlow.next(OnboardingStep.HANDOVER))
        assertEquals(OnboardingStep.DONE, OnboardingFlow.next(OnboardingStep.DONE))
    }

    @Test
    fun `each line is the one she was given, word for word`() {
        assertEquals(
            "Hey. I'm Heylana. I'm your Seeker buddy and I live on your screen, so you can ask me " +
                "about anything on it. I'll need two things from you first.",
            OnboardingText.WELCOME
        )
        assertEquals("Got it. I can see your screen now, but only when you hold me.", OnboardingText.SCREEN_READING)
        assertEquals("That's me. Hold me any time and ask.", OnboardingText.ORB)
        assertEquals(
            "Connected. I can read your wallet, but I can never move anything. Every signature is " +
                "your fingerprint, not mine.",
            OnboardingText.WALLET
        )
        assertEquals(
            "Last thing, and it's the important one. When your wallet asks you to approve something, " +
                "I'll speak up first and tell you what it actually does. You never have to guess again.",
            OnboardingText.SIGNING
        )
        assertEquals("That's it. Open any app, hold me, and ask what you're looking at.", OnboardingText.HANDOVER)
        assertEquals("Your Seeker says you're Ada. Did I say that right?", OnboardingText.nameCheck("Ada"))
        assertEquals("Ada. Better?", OnboardingText.readBack("Ada"))
    }

    @Test
    fun `the line said on arriving at a step is the step's own`() {
        assertEquals(Moment.WELCOME, OnboardingFlow.arrivingAt(OnboardingStep.WELCOME))
        assertEquals(Moment.SIGNING, OnboardingFlow.arrivingAt(OnboardingStep.SIGNING))
        assertEquals(Moment.HANDOVER, OnboardingFlow.arrivingAt(OnboardingStep.HANDOVER))
        // The permissions and the wallet say nothing on arrival: their lines follow the deed.
        assertNull(OnboardingFlow.arrivingAt(OnboardingStep.PERMISSIONS))
        assertNull(OnboardingFlow.arrivingAt(OnboardingStep.WALLET))
        // The name check carries the name, so it has no fixed words.
        assertNull(OnboardingFlow.line(Moment.NAME_CHECK))
    }

    @Test
    fun `screen reading then the overlay, each spoken once and only on the way on`() {
        val none = PermissionState()
        val reading = none.copy(screenReading = true)
        val both = reading.copy(overlay = true)

        assertEquals(listOf(Moment.SCREEN_READING), OnboardingFlow.granted(none, reading))
        assertEquals(listOf(Moment.ORB), OnboardingFlow.granted(reading, both))
        // Granted together: screen reading first, then the orb's moment.
        assertEquals(listOf(Moment.SCREEN_READING, Moment.ORB), OnboardingFlow.granted(none, both))
        // Already on, or switched back off: nothing said.
        assertEquals(emptyList<Moment>(), OnboardingFlow.granted(both, both))
        assertEquals(emptyList<Moment>(), OnboardingFlow.granted(both, reading))
        // The other two are not hers to announce.
        assertEquals(emptyList<Moment>(), OnboardingFlow.granted(none, none.copy(notifications = true, microphone = true)))
    }

    @Test
    fun `skip from any step ends the run and leaves the app working`() {
        OnboardingFlow.order.forEach { step ->
            assertEquals("skipping at $step", OnboardingStep.DONE, OnboardingFlow.skip())
        }
        assertFalse(OnboardingFlow.running(OnboardingStep.DONE))
        OnboardingFlow.order.forEach { assertTrue(OnboardingFlow.running(it)) }
    }

    @Test
    fun `the flow never waits on her voice - every step has words on screen`() {
        // A silent voice changes nothing: each spoken moment is text the screen can show.
        Moment.entries.filter { it != Moment.NAME_CHECK }.forEach { moment ->
            val line = OnboardingFlow.line(moment)
            assertTrue("$moment has no words", !line.isNullOrBlank())
        }
        // And the one that carries a name still has words once it has one.
        assertTrue(OnboardingText.nameCheck("Ada").isNotBlank())
    }

    @Test
    fun `a cold install starts on the welcome, a returning user on Home`() {
        assertEquals(Screen.ONBOARDING, AppRoute.start(firstRunDone = false))
        assertEquals(Screen.HOME, AppRoute.start(firstRunDone = true))
        // The welcome has no way back, replayed or not.
        assertNull(AppRoute.back(Screen.ONBOARDING, firstRunDone = false))
        assertNull(AppRoute.back(Screen.ONBOARDING, firstRunDone = true))
        assertEquals(Screen.HOME, AppRoute.afterOnboarding())
    }

    @Test
    fun `replaying the welcome asks for nothing already granted`() {
        // Everything on: every row is a tick, so the screen opens no system page.
        val all = PermissionState(overlay = true, screenReading = true, notifications = true, microphone = true)
        assertTrue(PermissionsModel.rowsFor(all).all { it.on })
        // And she says nothing about permissions that did not just change.
        assertEquals(emptyList<Moment>(), OnboardingFlow.granted(all, all))
    }

    @Test
    fun `three tries at a pronunciation, then she offers to move on`() {
        assertEquals(3, PRONUNCIATION_TRIES)
        assertTrue(OnboardingText.GOOD_ENOUGH.isNotBlank())
        // The hint shows the shape the respelling takes, and says what it means.
        assertTrue(OnboardingText.RESPELL_HINT.contains("-"))
        assertTrue(OnboardingText.RESPELL_HINT.any { it.isUpperCase() })
        assertTrue(OnboardingText.RESPELL_HOW.contains("Hyphens"))
        assertTrue(OnboardingText.RESPELL_HOW.contains("CAPITALS"))
    }

    @Test
    fun `what is spoken carries the respelling, what is written never does`() {
        val written = "Oghenerukevwe"
        val spoken = "Og-heh-neh-roo-KEV-weh"
        val said = SpokenName.forSpeech("Hi, $written. Your balance is 2 SOL.", written, spoken)
        assertEquals("Hi, $spoken. Your balance is 2 SOL.", said)
        // The screen's own copy is untouched: this is only ever applied on the way to the voice.
        assertEquals("Your Seeker says you're $written. Did I say that right?", OnboardingText.nameCheck(written))
    }

    @Test
    fun `with no respelling set she says exactly what she said before`() {
        val line = "Hi, Ada. That's your wallet's home screen."
        assertEquals(line, SpokenName.forSpeech(line, "Ada", ""))
        assertEquals(line, SpokenName.forSpeech(line, "", ""))
        // The same spelling is not a respelling either.
        assertEquals(line, SpokenName.forSpeech(line, "Ada", "ada"))
    }
}
