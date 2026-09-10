package xyz.heylana.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone's own voice is chosen, never taken as it comes. */
class PhoneVoiceTest {

    private fun option(
        name: String,
        locale: String = "en_US",
        quality: Int = 300,
        needsNetwork: Boolean = false
    ) = PhoneVoice.Option(name, locale, quality, needsNetwork)

    @Test
    fun `a female en-US voice is preferred`() {
        val best = PhoneVoice.best(
            listOf(
                option("en-us-x-sfg#male_2-local"),
                option("en-us-x-sfg#female_1-local")
            )
        )
        assertEquals("en-us-x-sfg#female_1-local", best?.name)
    }

    @Test
    fun `a voice that needs the network loses to one that does not`() {
        val best = PhoneVoice.best(
            listOf(
                option("en-us-x-iob#female_1-network", quality = 500, needsNetwork = true),
                option("en-us-x-iob#female_1-local", quality = 400)
            )
        )
        assertEquals("en-us-x-iob#female_1-local", best?.name)
    }

    @Test
    fun `the better of two otherwise equal voices wins`() {
        val best = PhoneVoice.best(
            listOf(
                option("en-us-x-aaa#female_1-local", quality = 100),
                option("en-us-x-bbb#female_1-local", quality = 500)
            )
        )
        assertEquals("en-us-x-bbb#female_1-local", best?.name)
    }

    @Test
    fun `en-US is preferred over other English`() {
        val best = PhoneVoice.best(
            listOf(
                option("en-gb-x-gba#female_1-local", locale = "en_GB", quality = 500),
                option("en-us-x-sfg#female_1-local", locale = "en_US", quality = 100)
            )
        )
        assertEquals("en-us-x-sfg#female_1-local", best?.name)
    }

    @Test
    fun `another English is better than none`() {
        val best = PhoneVoice.best(listOf(option("en-au-x-aua#female_1-local", locale = "en_AU")))
        assertEquals("en-au-x-aua#female_1-local", best?.name)
    }

    @Test
    fun `something is always picked when anything is offered`() {
        val best = PhoneVoice.best(listOf(option("de-de-x-deb#male_1-local", locale = "de_DE")))
        assertEquals("de-de-x-deb#male_1-local", best?.name)
    }

    @Test
    fun `the same phone picks the same voice every time`() {
        val offered = listOf(option("en-us-x-bbb#female_1-local"), option("en-us-x-aaa#female_1-local"))
        assertEquals(PhoneVoice.best(offered)?.name, PhoneVoice.best(offered.reversed())?.name)
    }

    @Test
    fun `an engine with no voices at all leaves nothing to pick`() {
        assertNull(PhoneVoice.best(emptyList()))
    }

    @Test
    fun `the voice is set a touch above flat`() {
        assertEquals(1.05f, PhoneVoice.PITCH, 0.0001f)
        assertEquals(1.0f, PhoneVoice.RATE, 0.0001f)
    }
}
