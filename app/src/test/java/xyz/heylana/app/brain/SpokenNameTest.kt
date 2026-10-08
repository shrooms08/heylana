package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenNameTest {

    private val written = "Oghenerukevwe"
    private val spoken = "Og-heh-neh-roo-KEV-weh"

    @Test
    fun `the name is swapped for its respelling, whatever the case`() {
        assertEquals("Hi, $spoken.", SpokenName.forSpeech("Hi, $written.", written, spoken))
        assertEquals("Hi, $spoken.", SpokenName.forSpeech("Hi, OGHENERUKEVWE.", written, spoken))
        assertEquals("$spoken, that's your wallet.", SpokenName.forSpeech("$written, that's your wallet.", written, spoken))
    }

    @Test
    fun `every time it appears, and never inside another word`() {
        assertEquals(
            "$spoken, I said $spoken.",
            SpokenName.forSpeech("$written, I said $written.", written, spoken)
        )
        // A name inside a longer word is not the name.
        assertEquals("Adalade is a place.", SpokenName.forSpeech("Adalade is a place.", "Ada", "AY-da"))
        assertEquals("AY-da is a person.", SpokenName.forSpeech("Ada is a person.", "Ada", "AY-da"))
    }

    @Test
    fun `nothing set, nothing changed`() {
        val line = "That's your wallet's home screen."
        assertEquals(line, SpokenName.forSpeech(line, written, ""))
        assertEquals(line, SpokenName.forSpeech(line, "", spoken))
        assertEquals("", SpokenName.forSpeech("", written, spoken))
        // The respelling that is the same word does nothing either.
        assertEquals("Hi, Ada.", SpokenName.forSpeech("Hi, Ada.", "Ada", "Ada"))
    }

    @Test
    fun `a display name with no letters is never matched`() {
        val line = "Hi, ... there."
        assertEquals(line, SpokenName.forSpeech(line, "...", "dot dot dot"))
    }

    @Test
    fun `a respelling is letters, hyphens and spaces, and never long`() {
        assertEquals("Og-heh-neh", SpokenName.cleanRespelling("  Og-heh-neh  "))
        assertEquals("AY da", SpokenName.cleanRespelling("AY   da"))
        // Digits, punctuation and line breaks would read oddly aloud, so they go.
        assertEquals("Ada", SpokenName.cleanRespelling("Ada7!\n"))
        assertTrue(SpokenName.cleanRespelling("x".repeat(200)).length <= SpokenName.MAX_SPOKEN)
        assertEquals("", SpokenName.cleanRespelling("123"))
    }

    @Test
    fun `a respelling with regex in it is taken literally`() {
        // Whatever someone types is a name, never a pattern.
        assertEquals("Hi, A.B.", SpokenName.forSpeech("Hi, Ada.", "Ada", "A.B"))
        assertEquals("Hi, Ada.", SpokenName.forSpeech("Hi, Ada.", "A.a", "nope"))
    }

    @Test
    fun `her character is stated once, in tone only`() {
        val character = HeylanaPrompt.CHARACTER
        // The lines the brief gave, and the ones that keep her from performing.
        assertTrue(character.contains("You are Heylana."))
        assertTrue(character.contains("the friend who sits with someone"))
        assertTrue(character.contains("calm and brief"))
        assertTrue(character.contains("never flatter, never hype, never celebrate"))
        assertTrue(character.contains("You explain; they decide."))
        assertTrue(character.contains("Made by Minos, an independent developer in Lagos, for the Seeker."))
        // It is in the system prompt, before the rules that still win over it.
        val system = HeylanaPrompt.SYSTEM
        assertTrue(system.contains(character))
        assertTrue(system.indexOf(character) < system.indexOf("Reply with ONLY this JSON"))
        // And it names the openers she must never use.
        assertTrue(character.contains("Certainly"))
        assertTrue(character.contains("Great question"))
        // Tone only: it asks for nothing about length or sourcing, which other rules own.
        assertFalse(character.contains("sentences"))
        assertFalse(character.contains("JSON"))
    }
}
