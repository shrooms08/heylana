package xyz.heylana.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.heylana.app.wallet.BuiltFixtures.FRIEND
import xyz.heylana.app.wallet.BuiltFixtures.PAYER
import xyz.heylana.app.wallet.BuiltFixtures.TOKEN_PROGRAM
import xyz.heylana.app.wallet.BuiltFixtures.TREASURY
import xyz.heylana.app.wallet.BuiltFixtures.USDC

class BuiltCheckTest {

    private val sol = BuiltCheck.Expected(PAYER, FRIEND, null, null, 50_000_000)
    private val token = BuiltCheck.Expected(PAYER, FRIEND, USDC, TOKEN_PROGRAM, 50_000)
    private val pay = BuiltCheck.Expected(PAYER, TREASURY, USDC, TOKEN_PROGRAM, 100_000)

    @Test
    fun `the transfers the worker builds match what was confirmed`() {
        assertEquals(BuiltCheck.Verdict.Matches, BuiltCheck.check(BuiltFixtures.SOL, sol))
        assertEquals(BuiltCheck.Verdict.Matches, BuiltCheck.check(BuiltFixtures.TOKEN, token))
        assertEquals(BuiltCheck.Verdict.Matches, BuiltCheck.check(BuiltFixtures.PAY, pay))
    }

    @Test
    fun `another amount, recipient, token or payer is not what was confirmed`() {
        assertEquals(BuiltCheck.Verdict.Differs("amount"), BuiltCheck.check(BuiltFixtures.SOL, sol.copy(units = 50_000_001)))
        assertEquals(BuiltCheck.Verdict.Differs("amount"), BuiltCheck.check(BuiltFixtures.TOKEN, token.copy(units = 49_999)))
        assertEquals(BuiltCheck.Verdict.Differs("recipient"), BuiltCheck.check(BuiltFixtures.SOL, sol.copy(to = TREASURY)))
        assertEquals(BuiltCheck.Verdict.Differs("recipient"), BuiltCheck.check(BuiltFixtures.TOKEN, token.copy(to = TREASURY)))
        assertEquals(BuiltCheck.Verdict.Differs("fee_payer"), BuiltCheck.check(BuiltFixtures.TOKEN, token.copy(from = FRIEND)))
        assertEquals(BuiltCheck.Verdict.Differs("token_in_sol_send"), BuiltCheck.check(BuiltFixtures.TOKEN, sol))
        assertEquals(BuiltCheck.Verdict.Differs("sol_in_token_send"), BuiltCheck.check(BuiltFixtures.SOL, token))
    }

    @Test
    fun `a byte changed anywhere in the transfer is caught`() {
        // The amount's lowest byte, in the last instruction's data.
        val tampered = BuiltFixtures.TOKEN.copyOf().also { it[it.size - 9] = (it[it.size - 9] + 1).toByte() }
        assertEquals(BuiltCheck.Verdict.Differs("amount"), BuiltCheck.check(tampered, token))
        // Anything after the message.
        assertEquals(BuiltCheck.Verdict.Differs("trailing_bytes"), BuiltCheck.check(BuiltFixtures.SOL + byteArrayOf(0), sol))
        // Cut short.
        assertEquals(BuiltCheck.Verdict.Differs("unreadable"), BuiltCheck.check(BuiltFixtures.SOL.copyOf(80), sol))
    }

    @Test
    fun `a transaction that asks for a second signer is refused`() {
        val twoSigners = BuiltFixtures.SOL.copyOf().also { it[0] = 2 }
        assertEquals(BuiltCheck.Verdict.Differs("signatures=2"), BuiltCheck.check(twoSigners, sol))
    }
}
