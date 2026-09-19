package xyz.heylana.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberRunsTest {

    private fun runs(text: String) = NumberRuns.ranges(text).map { text.substring(it) }

    @Test
    fun `balances, amounts, prices, percentages and counts are numbers`() {
        assertEquals(listOf("$142.31"), runs("SOL is $142.31 right now."))
        assertEquals(listOf("0.05", "0.000005"), runs("Send 0.05 USDC. Fee 0.000005 SOL."))
        assertEquals(listOf("1,204", "12%"), runs("You have 1,204 SKR, up 12% today."))
        assertEquals(listOf("30"), runs("30 talks a month"))
        assertEquals(listOf("2", "5"), runs("Lesson · PDAs, 2 of 5"))
        assertEquals(listOf("00", "12"), runs("00:12"))
    }

    @Test
    fun `digits inside a word or a shortened address are left alone`() {
        assertEquals(emptyList<String>(), runs("Web3 and 7c2y…SxSv"))
        assertEquals(emptyList<String>(), runs("a 2nd try on sol4k"))
    }
}
