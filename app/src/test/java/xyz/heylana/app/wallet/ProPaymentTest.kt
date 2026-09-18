package xyz.heylana.app.wallet

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.wallet.BuiltFixtures.PAYER
import xyz.heylana.app.wallet.BuiltFixtures.REFERENCE
import xyz.heylana.app.wallet.BuiltFixtures.TOKEN_PROGRAM
import xyz.heylana.app.wallet.BuiltFixtures.TREASURY
import xyz.heylana.app.wallet.BuiltFixtures.USDC

/** A Pro payment: built and simulated by the worker, checked here, then Seed Vault. */
class ProPaymentTest {

    private val session = WalletSession(PAYER, "token")
    private val quote = Quote(
        currency = "usdc", mint = USDC, amount = 100_000, decimals = 6,
        tokenProgram = TOKEN_PROGRAM, treasury = TREASURY,
        reference = REFERENCE, expiresAt = "2099-01-01T00:00:00.000Z"
    )
    private val pro = Standing("pro", 0, null, 10, "2026-10-15T12:00:00.000Z", null, session.pubkey)
    private val builds: suspend (String, Cluster) -> Answer<BuiltTransfer> = { _, _ -> Answer.Ok(BuiltFixtures.built(BuiltFixtures.PAY)) }

    @Test
    fun `a passing simulation pays with exactly the simulated bytes`() = runBlocking {
        val handed = mutableListOf<ByteArray>()
        val payment = ProPayment(
            build = builds,
            signAndSend = { bytes, _ -> handed += bytes; SeedVault.Trip.Done(SeedVault.Signed("signature", 5_000)) },
            confirm = { _, _ -> Answer.Ok(pro) },
            log = {}
        )
        assertEquals(PayOutcome.Paid(pro), payment.pay(session, quote, Cluster.DEVNET) { _, _ -> })
        assertTrue(handed.single().contentEquals(BuiltFixtures.PAY))
    }

    @Test
    fun `a failed simulation never opens the wallet`() = runBlocking {
        var walletAsked = false
        val payment = ProPayment(
            build = { _, _ -> Answer.Ok(BuiltFixtures.built(null, SimulationResult.Failed("not_enough_token", "Not enough USDC. You have 0.05."))) },
            signAndSend = { _, _ -> walletAsked = true; SeedVault.Trip.Done(SeedVault.Signed("signature", 5_000)) },
            confirm = { _, _ -> Answer.Ok(pro) },
            log = {}
        )
        val outcome = payment.pay(session, quote, Cluster.DEVNET) { _, _ -> } as PayOutcome.Stopped
        assertFalse(walletAsked)
        assertEquals("I did not open the wallet because the simulation failed. Not enough USDC. You have 0.05.", outcome.line)
    }

    @Test
    fun `bytes that are not the quoted payment never reach the wallet`() = runBlocking {
        var walletAsked = false
        val payment = ProPayment(
            build = { _, _ -> Answer.Ok(BuiltFixtures.built(BuiltFixtures.TOKEN)) },
            signAndSend = { _, _ -> walletAsked = true; SeedVault.Trip.Done(SeedVault.Signed("signature", 5_000)) },
            confirm = { _, _ -> Answer.Ok(pro) },
            log = {}
        )
        val outcome = payment.pay(session, quote, Cluster.DEVNET) { _, _ -> } as PayOutcome.Stopped
        assertFalse(walletAsked)
        assertEquals(BuildText.NOT_WHAT_WAS_CONFIRMED, outcome.line)
    }

    @Test
    fun `a wallet that times out is remembered by reference and found by it`() = runBlocking {
        val remembered = mutableListOf<Pair<String, String>>()
        val asked = mutableListOf<Pair<String, String?>>()
        val payment = ProPayment(
            build = builds,
            signAndSend = { _, _ -> SeedVault.Trip.Stopped(WalletProblem.UNKNOWN) },
            confirm = { reference, signature -> asked += reference to signature; Answer.Ok(pro) },
            log = {}
        )
        val outcome = payment.pay(session, quote, Cluster.DEVNET) { reference, signature -> remembered += reference to signature }
        assertEquals(PayOutcome.Paid(pro), outcome)
        assertEquals(listOf(quote.reference to ""), remembered)
        assertEquals(listOf<Pair<String, String?>>(quote.reference to null), asked)
    }

    @Test
    fun `rejected in Seed Vault stops at once, in the blueprint's words, and remembers nothing`() = runBlocking {
        var asked = false
        var remembered = false
        val payment = ProPayment(
            build = builds,
            signAndSend = { _, _ -> SeedVault.Trip.Stopped(WalletProblem.CANCELLED) },
            confirm = { _, _ -> asked = true; Answer.Ok(pro) },
            log = {}
        )
        val outcome = payment.pay(session, quote, Cluster.DEVNET) { _, _ -> remembered = true } as PayOutcome.Stopped
        assertEquals(BuildText.REJECTED, outcome.line)
        assertFalse(asked)
        assertFalse(remembered)
    }

    @Test
    fun `an expired payment is said as expired`() = runBlocking {
        val outcome = ConfirmPoll.await(check = { Answer.Refused(410, "expired") }, sleep = {}) as PayOutcome.Stopped
        assertEquals(BuildText.EXPIRED, outcome.line)
    }
}
