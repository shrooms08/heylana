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
    /** The quoted payment exactly as BuiltFixtures.PAY transfers it. */
    private val quote = Quote(
        currency = "usdc",
        mint = BuiltFixtures.USDC,
        amount = 100_000,
        decimals = 6,
        tokenProgram = BuiltFixtures.TOKEN_PROGRAM,
        treasury = BuiltFixtures.TREASURY,
        reference = BuiltFixtures.REFERENCE,
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
    fun `a devnet payment is built on devnet and signed by a devnet wallet`() = runBlocking {
        val asked = mutableListOf<String>()
        val payment = ProPayment(
            build = { _, cluster ->
                asked += "build:${cluster.id}"
                Answer.Ok(BuiltFixtures.built(BuiltFixtures.PAY))
            },
            signAndSend = { _, cluster ->
                asked += "wallet:${cluster.id}"
                SeedVault.Trip.Done("signature")
            },
            confirm = { _, _ -> Answer.Ok(pro) },
            log = {}
        )

        val outcome = payment.pay(session, quote, Cluster.DEVNET) { _, _ -> }

        assertEquals(listOf("build:devnet", "wallet:devnet"), asked)
        assertEquals(PayOutcome.Paid(pro), outcome)
    }

    @Test
    fun `a build refused for the wrong cluster never reaches the wallet`() = runBlocking {
        var walletAsked = false
        val payment = ProPayment(
            build = { _, _ -> Answer.Refused(409, "wrong_cluster", "This is for mainnet-beta, but Heylana is on devnet. I stopped before building it.") },
            signAndSend = { _, _ -> walletAsked = true; SeedVault.Trip.Done("signature") },
            confirm = { _, _ -> Answer.Ok(pro) },
            log = {}
        )

        val outcome = payment.pay(session, quote, Cluster.MAINNET) { _, _ -> }

        assertFalse(walletAsked)
        assertEquals("This is for mainnet-beta, but Heylana is on devnet. I stopped before building it.", (outcome as PayOutcome.Stopped).line)
    }
}
