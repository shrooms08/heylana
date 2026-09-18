package xyz.heylana.app.home

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.Features
import xyz.heylana.app.skills.SkillIndex
import xyz.heylana.app.wallet.PlanText
import xyz.heylana.app.wallet.Standing

/** The Skill market is on the roadmap: nothing reaches it and nothing is downloaded. */
class SkillMarketOffTest {

    @Test
    fun `the flag is off`() {
        assertFalse(Features.SKILL_MARKET)
    }

    @Test
    fun `the market screen cannot be reached, every other screen can`() {
        assertFalse(AppRoute.reachable(Screen.SKILLS))
        Screen.entries.filter { it != Screen.SKILLS }.forEach { assertTrue(AppRoute.reachable(it)) }
    }

    @Test
    fun `the public index is not fetched`() {
        val fetched = runBlocking { SkillIndex.fetch("https://example.com/index.json", SkillIndex.MAX_INDEX_BYTES, false) }
        assertEquals(SkillIndex.Fetched.Failed("market_off"), fetched)
    }

    @Test
    fun `no plan line mentions skills`() {
        listOf(
            Standing("free", 3, 30, 3, null, null, null),
            Standing("pro", 0, null, 10, "2026-10-15T12:00:00.000Z", null, null),
            Standing("judge", 0, null, 10, null, "2026-11-09T23:59:59.000Z", null)
        ).forEach { plan ->
            val words = listOfNotNull(PlanText.summary(plan), PlanText.used(plan), MenuText.planLine(plan))
            words.forEach { assertFalse(it, it.contains("skill", ignoreCase = true)) }
        }
    }
}
