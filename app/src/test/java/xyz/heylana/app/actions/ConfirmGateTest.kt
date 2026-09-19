package xyz.heylana.app.actions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.wallet.Answer

class ConfirmGateTest {

    private val confirmed: suspend (String, String) -> Answer<String> = { _, _ -> Answer.Ok("token") }

    private suspend fun check(
        intent: String,
        actionId: String?,
        confirm: suspend (String, String) -> Answer<String>
    ) = ConfirmGate.check(intent, actionId, confirm, log = {})

    @Test
    fun `an alarm, a timer or an app is R2 and never asks the worker`() = runBlocking {
        var asked = false
        for (intent in listOf(QuickAction.ALARM, QuickAction.TIMER, QuickAction.OPEN_APP)) {
            val decision = check(intent, null, confirm = { _, _ -> asked = true; Answer.Ok("t") })
            assertEquals(ConfirmGate.Decision.Fire("r2"), decision)
        }
        assertEquals(false, asked)
    }

    @Test
    fun `a message or a reminder fires only with the worker's confirmation of its id`() = runBlocking {
        val asked = mutableListOf<Pair<String, String>>()
        for (intent in listOf(QuickAction.MESSAGE, QuickAction.REMINDER)) {
            val decision = check(intent, "id-1", confirm = { kind, id -> asked += kind to id; Answer.Ok("t") })
            assertEquals(ConfirmGate.Decision.Fire("confirmed"), decision)
        }
        assertEquals(listOf("message" to "id-1", "reminder" to "id-1"), asked)
    }

    @Test
    fun `refused, unreachable, or no id from the worker holds it, in plain words`() = runBlocking {
        val refused = check(QuickAction.MESSAGE, "id", confirm = { _, _ -> Answer.Refused(403, "not_confirmable") })
        val offline = check(QuickAction.MESSAGE, "id", confirm = { _, _ -> Answer.Unreachable("IOException") })
        val noId = check(QuickAction.REMINDER, null, confirmed)
        for (decision in listOf(refused, offline, noId)) {
            assertTrue(decision is ConfirmGate.Decision.Hold)
            assertEquals(ConfirmGate.NOT_CONFIRMED, (decision as ConfirmGate.Decision.Hold).line)
        }
    }

    @Test
    fun `a user's own key changes nothing - a message still waits for the worker's confirmation`() = runBlocking {
        assertEquals(ConfirmGate.Decision.Hold(ConfirmGate.NOT_CONFIRMED, "no_action_id"), check(QuickAction.MESSAGE, null, confirm = { _, _ -> Answer.Ok("t") }))
    }
}
