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
        for (fact in listOf("made by Minos,", "independent developer in Lagos", "for the Solana Seeker",
            "not made by Solana Mobile or Solana Labs", "adopted by the Seeker and become part of it")) {
            assertTrue(fact, HeylanaPrompt.IDENTITY.contains(fact, ignoreCase = true))
            assertTrue(fact, SettingsText.ABOUT_DETAIL.contains(fact, ignoreCase = true))
        }
    }

    @Test
    fun `Heylana calls its maker Minos only - no surname anywhere in the app or its prompts`() {
        val root = listOf(java.io.File(".."), java.io.File(".")).first { java.io.File(it, "skills").isDirectory }
        val places = listOf("app/src/main", "skills", "worker/src").map { java.io.File(root, it) }
        val found = places.flatMap { dir -> dir.walkTopDown().filter { it.isFile } }
            .filter { file -> file.readText().let { it.contains("Eminokanju") || it.contains("Oghenerukevwe") } }
        assertTrue("surname found in $found", found.isEmpty())
        assertTrue(!HeylanaPrompt.system(solana = true).contains("Eminokanju"))
    }
}
