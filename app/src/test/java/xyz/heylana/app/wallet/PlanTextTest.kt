package xyz.heylana.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class PlanTextTest {

    private fun standing(
        plan: String = "free",
        used: Int = 0,
        limit: Int? = 50,
        skills: Int = 3,
        proUntil: String? = null,
        judgeUntil: String? = null
    ) = Standing(plan, used, limit, skills, proUntil, judgeUntil, wallet = null)

    private fun quote(currency: String, amount: Long, decimals: Int = 6, priceUsd: String? = "0.10") =
        Quote(currency, "mint", amount, decimals, "program", "treasury", "ref", "2026-09-15T12:10:00.000Z", priceUsd)

    @Test
    fun `free shows talks used of the limit`() {
        val free = standing(used = 1, limit = 50)
        assertEquals("Free", PlanText.name(free.plan))
        assertEquals("30 talks a month", PlanText.summary(free))
        assertEquals("1 of 50 used this month", PlanText.used(free))
        assertNull(PlanText.until(free))
    }

    @Test
    fun `pro and judge are unlimited and say until when`() {
        val pro = standing(plan = "pro", limit = null, skills = 10, proUntil = "2026-10-15T12:00:00.000Z")
        assertEquals("Unlimited talks", PlanText.summary(pro))
        assertNull(PlanText.used(pro))
        assertEquals("Pro until Oct 15, 2026", PlanText.until(pro))

        val judge = standing(plan = "judge", limit = null, skills = 10, judgeUntil = "2026-11-09T23:59:59.000Z")
        assertEquals("Judge", PlanText.name(judge.plan))
        assertEquals("Judge until Nov 9, 2026", PlanText.until(judge))
        assertEquals("Unlimited until Nov 9", PlanText.summary(judge))
    }

    @Test
    fun `a judge code ending Nov 9 says Nov 9 on a phone east of UTC`() {
        val before = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Lagos"))
        try {
            val judge = standing(plan = "judge", limit = null, skills = 10, judgeUntil = "2026-11-09T23:59:59.000Z")
            assertEquals("Judge until Nov 9, 2026", PlanText.until(judge))
        assertEquals("Unlimited until Nov 9", PlanText.summary(judge))
        } finally {
            TimeZone.setDefault(before)
        }
    }

    @Test
    fun `an unreadable date still shows the day`() {
        assertEquals("2026-10-15", PlanText.date("2026-10-15 soon"))
    }

    @Test
    fun `amounts are exact, with two decimals at least`() {
        assertEquals("0.10", PlanText.amount(100_000, 6))
        assertEquals("15.00", PlanText.amount(15_000_000, 6))
        assertEquals("3.333334", PlanText.amount(3_333_334, 6))
        assertEquals("1.000000001", PlanText.amount(1_000_000_001, 9))
    }

    @Test
    fun `the sheet says what will be sent and what it is worth`() {
        assertEquals("You'll send 0.10 USDC", PlanText.send(quote("usdc", 100_000)))
        assertNull(PlanText.worth(quote("usdc", 100_000)))
        assertEquals("You'll send 3.333334 SKR", PlanText.send(quote("skr", 3_333_334)))
        assertEquals("About \$0.10 at today's SKR price", PlanText.worth(quote("skr", 3_333_334)))
    }
}
