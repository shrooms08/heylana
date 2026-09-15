package xyz.heylana.app.wallet

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Waiting for a payment to land: patient with "not yet", final on a real no. */
class ConfirmPollTest {

    private val pro = Standing("pro", 0, null, 10, "2026-10-15T12:00:00.000Z", null, "wallet")

    /** A clock that only moves when the poll sleeps. */
    private class FakeClock {
        var millis = 0L
        var sleeps = 0
        val now: () -> Long = { millis }
        val sleep: suspend (Long) -> Unit = { millis += it; sleeps++ }
    }

    private fun poll(clock: FakeClock, answers: List<Answer<Standing>>): PayOutcome = runBlocking {
        var i = 0
        ConfirmPoll.await(
            check = { answers[minOf(i++, answers.lastIndex)] },
            now = clock.now,
            sleep = clock.sleep
        )
    }

    @Test
    fun `not confirmed, then confirmed, is paid`() {
        val clock = FakeClock()
        val outcome = poll(
            clock,
            listOf(
                Answer.Refused(409, "not_confirmed"),
                Answer.Unreachable("SocketTimeoutException"),
                Answer.Refused(502, "error"),
                Answer.Ok(pro)
            )
        )
        assertEquals(PayOutcome.Paid(pro), outcome)
        assertEquals(3, clock.sleeps)
    }

    @Test
    fun `still not confirmed after a minute took too long`() {
        val clock = FakeClock()
        val outcome = poll(clock, listOf(Answer.Refused(409, "not_confirmed")))
        assertEquals(PayOutcome.Stopped(WalletProblem.TOOK_TOO_LONG), outcome)
        assertTrue("never waits past 60s", clock.millis <= ConfirmPoll.TIMEOUT_MS)
        assertEquals(ConfirmPoll.TIMEOUT_MS / ConfirmPoll.INTERVAL_MS, clock.sleeps.toLong())
    }

    @Test
    fun `a payment that does not match stops at once`() {
        val clock = FakeClock()
        val outcome = poll(clock, listOf(Answer.Refused(402, "short_amount")))
        assertEquals(PayOutcome.Stopped(WalletProblem.MISMATCH), outcome)
        assertEquals(0, clock.sleeps)
    }
}
