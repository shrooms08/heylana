package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SendGuardTest {

    private val treasury = "7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv"

    private fun send(to: String, amount: String?, token: String = "USDC") =
        SendAction(to, amount?.let { BigDecimal(it) }, token)

    @Test
    fun `what the user asked for goes through`() {
        val verdict = SendGuard.check(send(treasury, "0.05"), "send 0.05 USDC to $treasury")
        assertEquals(SendGuard.Verdict.Allowed(treasury, "0.05", "USDC"), verdict)
    }

    @Test
    fun `a recipient the user never said is dropped`() {
        val verdict = SendGuard.check(send(treasury, "5"), "send 5 USDC to the address on this screen")
        assertEquals("recipient_not_said", (verdict as SendGuard.Verdict.Refused).reason)
        assertEquals(SendGuard.RECIPIENT_NOT_SAID, verdict.line)
    }

    @Test
    fun `an address that differs by one letter's case is a different address`() {
        val altered = treasury.replaceFirst('c', 'C')
        val verdict = SendGuard.check(send(altered, "1"), "send 1 USDC to $treasury")
        assertTrue(verdict is SendGuard.Verdict.Refused)
    }

    @Test
    fun `an amount the user never said is dropped, even one hiding in the address`() {
        val made = SendGuard.check(send("bob.skr", "50"), "send some USDC to bob.skr")
        assertEquals("amount_not_said", (made as SendGuard.Verdict.Refused).reason)
        // "8" is inside the address, not an amount the user said.
        val hidden = SendGuard.check(send(treasury, "8"), "send USDC to $treasury")
        assertTrue(hidden is SendGuard.Verdict.Refused)
    }

    @Test
    fun `a spoken name and a written amount match what the model wrote`() {
        val verdict = SendGuard.check(send("bob.skr", "5"), "Send five, I mean 5 SOL to Bob dot skr")
        assertEquals(SendGuard.Verdict.Allowed("bob.skr", "5", "USDC"), verdict)
        assertTrue(SendGuard.check(send("bob.skr", "0.05"), "send .05 to bob.skr") is SendGuard.Verdict.Allowed)
        assertTrue(SendGuard.check(send("bob.skr", "1250.5"), "send 1,250.5 to bob.skr") is SendGuard.Verdict.Allowed)
    }

    @Test
    fun `everything is allowed through as all, to be checked against the balance`() {
        val verdict = SendGuard.check(send(treasury, null), "send everything to $treasury")
        assertEquals(SendGuard.Verdict.Allowed(treasury, "all", "USDC"), verdict)
    }

    @Test
    fun `more than a quarter of the balance is over the limit`() {
        assertTrue(SendGuard.overLimit("12", "12"))
        assertTrue(SendGuard.overLimit("3.01", "12"))
        assertFalse(SendGuard.overLimit("3", "12"))
        assertFalse(SendGuard.overLimit("0.05", null))
        assertTrue(SendGuard.overLimitLine("USDC").contains("yes send it all"))
    }

    @Test
    fun `the second turn confirms with yes send it all, or the same amount again`() {
        assertTrue(SendGuard.confirmsPending("Yes, send it all", "12"))
        assertTrue(SendGuard.confirmsPending("I said 12", "12"))
        assertFalse(SendGuard.confirmsPending("no, cancel that", "12"))
        assertFalse(SendGuard.confirmsPending("send 11", "12"))
    }

    @Test
    fun `only a well-formed send action is an action`() {
        assertEquals(send("bob.skr", "5"), SendAction.of("send", " bob.skr ", "5", "usdc"))
        assertEquals(send("bob.skr", null), SendAction.of("send", "bob.skr", null, "USDC"))
        assertNull(SendAction.of("swap", "bob.skr", "5", "USDC"))
        assertNull(SendAction.of("send", "", "5", "USDC"))
        assertNull(SendAction.of("send", "bob.skr", "5", "BONK"))
        assertEquals(null, SendAction.of("send", "bob.skr", "-1", "SOL")!!.amount)
    }
}
