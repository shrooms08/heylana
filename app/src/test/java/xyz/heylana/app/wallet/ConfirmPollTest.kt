package xyz.heylana.app.wallet

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Waiting for a payment to land: patient with "not yet", final on a real no. */
class ConfirmPollTest {

    private val pro = Standing("pro", 0, null, 10, "2026-10-15T12:00:00.000Z", null, "wallet")

    private fun poll(answers: List<Answer<Standing>>, slept: MutableList<Long>, logged: MutableList<String>): PayOutcome =
        runBlocking {
            var i = 0
            ConfirmPoll.await(
                check = { answers[minOf(i++, answers.lastIndex)] },
                sleep = { slept += it },
                log = { logged += it }
            )
        }

    @Test
    fun `not confirmed, a hiccup and no connection are asked again with growing waits`() {
        val slept = mutableListOf<Long>()
        val logged = mutableListOf<String>()
        val outcome = poll(
            listOf(Answer.Refused(409, "not_confirmed"), Answer.Unreachable("SocketTimeoutException"), Answer.Refused(502, "error"), Answer.Ok(pro)),
            slept, logged
        )
        assertEquals(PayOutcome.Paid(pro), outcome)
        assertEquals(listOf(2_000L, 3_000L, 5_000L), slept)
        assertEquals(
            listOf("pay: check #1 result=409 not_confirmed", "pay: check #2 result=unreachable SocketTimeoutException", "pay: check #3 result=502 error", "pay: check #4 result=ok"),
            logged
        )
    }

    @Test
    fun `still not confirmed after a minute took too long, never waiting past it`() {
        val slept = mutableListOf<Long>()
        val outcome = poll(listOf(Answer.Refused(409, "not_confirmed")), slept, mutableListOf())
        assertEquals(PayOutcome.Stopped(WalletProblem.TOOK_TOO_LONG), outcome)
        assertEquals(ConfirmPoll.TIMEOUT_MS, slept.sum())
        assertTrue(slept.zipWithNext().dropLast(1).all { (a, b) -> b > a })
    }

    @Test
    fun `a payment that does not match stops at once`() {
        val slept = mutableListOf<Long>()
        assertEquals(PayOutcome.Stopped(WalletProblem.MISMATCH), poll(listOf(Answer.Refused(402, "short_amount")), slept, mutableListOf()))
        assertEquals(emptyList<Long>(), slept)
    }
}
