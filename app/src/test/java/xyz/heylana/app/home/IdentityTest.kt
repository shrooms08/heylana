package xyz.heylana.app.home

import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.brain.HeylanaPrompt

/** The system prompt and Settings' About line name the same maker, the same way. */
class IdentityTest {

    @Test
    fun `the prompt says who made Heylana, word for word, and About agrees`() {
        assertTrue(HeylanaPrompt.system(solana = false).contains(HeylanaPrompt.IDENTITY))
        assertTrue(HeylanaPrompt.system(solana = true).contains(HeylanaPrompt.IDENTITY))
        for (fact in listOf("Minos (Oghenerukevwe Eminokanju)", "independent developer in Lagos", "for the Solana Seeker",
            "not made by Solana Mobile or Solana Labs", "adopted by the Seeker and become part of it")) {
            assertTrue(fact, HeylanaPrompt.IDENTITY.contains(fact, ignoreCase = true))
            assertTrue(fact, SettingsText.ABOUT_DETAIL.contains(fact, ignoreCase = true))
        }
    }
}
