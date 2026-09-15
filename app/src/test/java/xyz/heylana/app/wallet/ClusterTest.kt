package xyz.heylana.app.wallet

import com.solana.mobilewalletadapter.clientlib.Solana
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cluster the worker names reaches every place a payment touches a chain. */
class ClusterTest {

    private val session = WalletSession("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", "token")
    private val quote = Quote(
        currency = "usdc",
        mint = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU",
        amount = 100_000,
        decimals = 6,
        tokenProgram = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
        treasury = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU",
        reference = "11111111111111111111111111111111",
        expiresAt = "2099-01-01T00:00:00.000Z"
    )
    private val pro = Standing("pro", 0, null, 10, "2026-10-15T12:00:00.000Z", null, session.pubkey, Cluster.DEVNET)

    @Test
    fun `the worker's name picks the cluster, mainnet unless devnet`() {
        assertEquals(Cluster.DEVNET, Cluster.fromWorker("devnet"))
        assertEquals(Cluster.MAINNET, Cluster.fromWorker("mainnet-beta"))
        assertEquals(Cluster.MAINNET, Cluster.fromWorker(null))
        assertEquals(Cluster.MAINNET, Cluster.fromWorker("testnet"))
    }

    @Test
    fun `each cluster asks Seed Vault about the same chain`() {
        assertEquals(Solana.Devnet, Cluster.DEVNET.blockchain)
        assertEquals(Solana.Mainnet, Cluster.MAINNET.blockchain)
    }

    @Test
    fun `SKR is off on devnet, with a plain line`() {
        assertFalse(Cluster.DEVNET.hasSkr)
        assertTrue(Cluster.MAINNET.hasSkr)
        assertEquals("SKR is not on devnet.", PlanText.skrMissing(Cluster.DEVNET))
        assertNull(PlanText.skrMissing(Cluster.MAINNET))
    }

    @Test
    fun `a devnet payment asks for a devnet blockhash and a devnet wallet`() = runBlocking {
        val asked = mutableListOf<String>()
        val payment = ProPayment(
            blockhash = { cluster ->
                asked += "blockhash:${cluster.id}"
                Answer.Ok("EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k")
            },
            signAndSend = { _, cluster ->
                asked += "wallet:${cluster.id}"
                SeedVault.Trip.Done("signature")
            },
            confirm = { _, _ -> Answer.Ok(pro) },
            log = {}
        )

        val outcome = payment.pay(session, quote, Cluster.DEVNET) { _, _ -> }

        assertEquals(listOf("blockhash:devnet", "wallet:devnet"), asked)
        assertEquals(PayOutcome.Paid(pro), outcome)
    }

    @Test
    fun `a blockhash refused for the wrong cluster never reaches the wallet`() = runBlocking {
        var walletAsked = false
        val payment = ProPayment(
            blockhash = { Answer.Refused(409, "wrong_cluster") },
            signAndSend = { _, _ -> walletAsked = true; SeedVault.Trip.Done("signature") },
            confirm = { _, _ -> Answer.Ok(pro) },
            log = {}
        )

        val outcome = payment.pay(session, quote, Cluster.MAINNET) { _, _ -> }

        assertFalse(walletAsked)
        assertTrue(outcome is PayOutcome.Stopped)
    }
}
