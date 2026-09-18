package xyz.heylana.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.settings.VoiceCopy
import xyz.heylana.app.skills.SkillIndex
import xyz.heylana.app.ui.app.Glyph
import xyz.heylana.app.wallet.Standing

class MenuScreensTest {

    private fun standing(plan: String, used: Int = 0, limit: Int? = null, judgeUntil: String? = null) =
        Standing(plan, used, limit, skillsCap = 3, proUntil = null, judgeUntil = judgeUntil, wallet = null)

    @Test
    fun `free shows talks, a bar and Go Pro`() {
        val free = standing("free", used = 12, limit = 30)
        assertEquals("Free", MenuText.planName(free))
        assertEquals("12 of 30 talks", MenuText.planLine(free))
        assertEquals(0.4f, MenuText.planFill(free)!!, 0.001f)
        assertTrue(MenuText.showGoPro(free))
    }

    @Test
    fun `judge shows its end date and no Go Pro`() {
        val judge = standing("judge", judgeUntil = "2026-11-09T23:59:59Z")
        assertEquals("Judge", MenuText.planName(judge))
        assertEquals("Judge until Nov 9, 2026", MenuText.planLine(judge))
        assertNull(MenuText.planFill(judge))
        assertFalse(MenuText.showGoPro(judge))
    }

    @Test
    fun `before the worker answers the plan is being checked`() {
        assertEquals(MenuText.CHECKING, MenuText.planLine(null))
        assertFalse(MenuText.showGoPro(null))
    }

    @Test
    fun `the footer names the wallet or says there is none`() {
        assertEquals("7c2y…SxSv · Seed Vault", MenuText.walletLine("7c2y…SxSv"))
        assertEquals(MenuText.NO_WALLET, MenuText.walletLine(null))
        assertEquals("A", MenuText.initial("aaron"))
        assertEquals("H", MenuText.initial(" "))
    }

    @Test
    fun `get is offered only for skills not already here`() {
        val chrome = SkillIndex.Entry("chrome", "Chrome", "", "1", "https://x/chrome.md")
        val youtube = SkillIndex.Entry("youtube", "YouTube", "", "1", "https://x/youtube.md")
        assertEquals(listOf(chrome), MarketText.toGet(listOf(chrome, youtube), setOf("youtube", "jupiter")))
        assertEquals(Glyph.SWAP, MarketText.glyph("jupiter"))
        assertEquals(Glyph.DOC, MarketText.glyph("something-new"))
    }

    @Test
    fun `only Anthropic is wired, and the note never mentions Seed Vault`() {
        assertEquals(listOf("anthropic"), AdvancedText.PROVIDERS.filter { it.wired }.map { it.id })
        assertEquals(3, AdvancedText.PROVIDERS.size)
        assertEquals("Held in the phone's keystore. It never leaves the device.", AdvancedText.NOTE)
        assertFalse(AdvancedText.NOTE.contains("Seed Vault"))
    }

    @Test
    fun `the privacy list has the seven items and names the voice in use`() {
        val gemini = PrivacyCopy.items(VoiceCopy.GEMINI)
        assertEquals(7, gemini.size)
        assertTrue(gemini[2].detail.contains("Google (Gemini)"))
        assertTrue(PrivacyCopy.items(VoiceCopy.DEEPGRAM)[2].detail.contains("Deepgram"))
        assertTrue(PrivacyCopy.PIXELS.contains("never captures pixels"))
    }

    @Test
    fun `permissions opened from settings go back there, the first run has no way back`() {
        assertEquals(Screen.SETTINGS, AppRoute.back(Screen.PERMISSIONS, firstRunDone = true))
        assertNull(AppRoute.back(Screen.PERMISSIONS, firstRunDone = false))
    }
}
