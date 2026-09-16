package xyz.heylana.app.wallet

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A Pro payment the wallet did not hand back a signature for. */
class ProPaymentTest {

    private val session = WalletSession("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", "token")
    private val quote = Quote(
        currency = "usdc", mint = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU", amount = 100_000, decimals = 6,
        tokenProgram = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", treasury = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU",
        reference = "4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T", expiresAt = "2099-01-01T00:00:00.000Z"
    )
    private val pro = Standing("pro", 0, null, 10, "2026-10-15T12:00:00.000Z", null, session.pubkey)

    @Test
    fun `a wallet that times out is remembered by reference and found by it`() = runBlocking {
        val remembered = mutableListOf<Pair<String, String>>()
        val asked = mutableListOf<Pair<String, String?>>()
        val payment = ProPayment(
            blockhash = { Answer.Ok("EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k") },
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
    fun `declined in Seed Vault stops at once and remembers nothing`() = runBlocking {
        var asked = false
        var remembered = false
        val payment = ProPayment(
            blockhash = { Answer.Ok("EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k") },
            signAndSend = { _, _ -> SeedVault.Trip.Stopped(WalletProblem.CANCELLED) },
            confirm = { _, _ -> asked = true; Answer.Ok(pro) },
            log = {}
        )
        assertEquals(PayOutcome.Stopped(WalletProblem.CANCELLED), payment.pay(session, quote, Cluster.DEVNET) { _, _ -> remembered = true })
        assertFalse(asked)
        assertFalse(remembered)
    }
}
