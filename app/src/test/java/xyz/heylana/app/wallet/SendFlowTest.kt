package xyz.heylana.app.wallet

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.wallet.BuiltFixtures.FRIEND
import xyz.heylana.app.wallet.BuiltFixtures.PAYER
import xyz.heylana.app.wallet.BuiltFixtures.TOKEN_PROGRAM
import xyz.heylana.app.wallet.BuiltFixtures.USDC

class SendFlowTest {

    /** A quote as the worker's /send/prepare answer becomes one, cluster and all. */
    private fun quote(workerCluster: String) = SendQuote(
        id = "send-1", toAddress = FRIEND, resolvedFrom = null,
        amount = "0.05", token = "USDC", mint = USDC, decimals = 6,
        tokenProgram = TOKEN_PROGRAM, feeEstimate = "0.000005", accountRent = "0",
        willCreateAta = false, balance = "5", cluster = Cluster.fromWorker(workerCluster)
    )

    private val request = SendFlow.Request.of(quote("devnet"))
    private val passes: suspend (String, Cluster) -> Answer<BuiltTransfer> = { _, _ -> Answer.Ok(BuiltFixtures.built(BuiltFixtures.TOKEN)) }

    private fun flow(
        build: suspend (String, Cluster) -> Answer<BuiltTransfer> = passes,
        wallet: suspend (ByteArray, Cluster) -> SeedVault.Trip<String> = { _, _ -> SeedVault.Trip.Done("signature") },
        confirm: suspend (String, String?) -> Answer<String> = { _, _ -> Answer.Ok("5555…5555") },
        log: (String) -> Unit = {},
        clock: LongArray = longArrayOf(0L),
        stages: MutableList<SendStage> = mutableListOf()
    ) = SendFlow(build, wallet, confirm, log, now = { clock[0] }, sleep = { clock[0] += it }, stage = { stages += it })

    @Test
    fun `a passing simulation opens the wallet with exactly the simulated bytes, on the send's cluster`() = runBlocking {
        val asked = mutableListOf<String>()
        val handed = mutableListOf<ByteArray>()
        val logged = mutableListOf<String>()
        val stages = mutableListOf<SendStage>()
        val result = flow(
            build = { id, cluster -> asked += "build:$id:${cluster.id}"; Answer.Ok(BuiltFixtures.built(BuiltFixtures.TOKEN)) },
            wallet = { bytes, cluster -> asked += "wallet:${cluster.id}"; handed += bytes; SeedVault.Trip.Done("signature") },
            log = { logged += it },
            stages = stages
        ).run(request, PAYER)

        assertEquals(SendResult.Sent("5555…5555"), result)
        assertEquals(listOf("build:send-1:devnet", "wallet:devnet"), asked)
        assertTrue(handed.single().contentEquals(BuiltFixtures.TOKEN))
        assertEquals(listOf(SendStage.SIMULATING, SendStage.APPROVE_IN_WALLET, SendStage.CHECKING), stages)
        assertTrue(logged.any { it.startsWith("send: simulation passed, opening Seed Vault cluster=devnet") })
    }

    @Test
    fun `a failed simulation never opens the wallet, and says why`() = runBlocking {
        var walletAsked = false
        val failed = SimulationResult.Failed("not_enough_token", "Not enough USDC. You have 0.03.")
        val result = flow(
            build = { _, _ -> Answer.Ok(BuiltFixtures.built(null, failed)) },
            wallet = { _, _ -> walletAsked = true; SeedVault.Trip.Done("signature") }
        ).run(request, PAYER)
        assertFalse(walletAsked)
        assertEquals(
            SendResult.Stopped("I did not open the wallet because the simulation failed. Not enough USDC. You have 0.03."),
            result
        )
    }

    @Test
    fun `the wrong network stops before the wallet, in the worker's words`() = runBlocking {
        var walletAsked = false
        val line = "This is for mainnet-beta, but Heylana is on devnet. I stopped before building it."
        val result = flow(
            build = { _, _ -> Answer.Refused(409, "wrong_cluster", line) },
            wallet = { _, _ -> walletAsked = true; SeedVault.Trip.Done("signature") }
        ).run(request, PAYER)
        assertFalse(walletAsked)
        assertEquals(SendResult.Stopped(line), result)
    }

    @Test
    fun `bytes that are not the confirmed transfer never reach the wallet`() = runBlocking {
        var walletAsked = false
        // The worker hands back a SOL transfer for a USDC send.
        val result = flow(
            build = { _, _ -> Answer.Ok(BuiltFixtures.built(BuiltFixtures.SOL)) },
            wallet = { _, _ -> walletAsked = true; SeedVault.Trip.Done("signature") }
        ).run(request, PAYER)
        assertFalse(walletAsked)
        assertEquals(SendResult.Stopped(BuildText.NOT_WHAT_WAS_CONFIRMED), result)
    }

    @Test
    fun `rejected in the wallet is final - no search on chain, never asked again`() = runBlocking {
        var searched = false
        var walletTimes = 0
        val result = flow(
            wallet = { _, _ -> walletTimes++; SeedVault.Trip.Stopped(WalletProblem.CANCELLED) },
            confirm = { _, _ -> searched = true; Answer.Ok("s") }
        ).run(request, PAYER)
        assertEquals(SendResult.Stopped(BuildText.REJECTED), result)
        assertEquals("The wallet rejected the request. No transaction was submitted.", BuildText.REJECTED)
        assertFalse(searched)
        assertEquals(1, walletTimes)
    }

    @Test
    fun `an expired blockhash is said as expired, never retried with the same bytes`() = runBlocking {
        var walletTimes = 0
        val result = flow(
            wallet = { _, _ -> walletTimes++; SeedVault.Trip.Stopped(WalletProblem.TOOK_TOO_LONG) },
            confirm = { _, _ -> Answer.Refused(410, "expired") }
        ).run(request, PAYER)
        assertEquals(SendResult.Stopped(BuildText.EXPIRED), result)
        assertEquals(1, walletTimes)
    }

    @Test
    fun `unknown then found by its reference is Sent`() = runBlocking {
        val asked = mutableListOf<String?>()
        var looks = 0
        val result = flow(
            wallet = { _, _ -> SeedVault.Trip.Stopped(WalletProblem.UNKNOWN) },
            confirm = { _, signature -> asked += signature; if (++looks < 3) Answer.Refused(409, "not_confirmed") else Answer.Ok("4444…4444") }
        ).run(request, PAYER)
        assertEquals(SendResult.Sent("4444…4444"), result)
        assertEquals(listOf<String?>(null, null, null), asked)
    }

    @Test
    fun `not found after the minute says check the wallet, never try again`() = runBlocking {
        val clock = longArrayOf(0L)
        val withoutSignature = flow(
            wallet = { _, _ -> SeedVault.Trip.Stopped(WalletProblem.UNKNOWN) },
            confirm = { _, _ -> Answer.Refused(409, "not_confirmed") },
            clock = clock
        ).run(request, PAYER)
        assertEquals(SendResult.Stopped(BuildText.NOT_FOUND), withoutSignature)
        assertEquals(SendFlow.LAND_TIMEOUT_MS, clock[0])
        assertFalse(BuildText.NOT_FOUND.contains("try again", ignoreCase = true) && !BuildText.NOT_FOUND.contains("before trying again"))

        val signed = flow(confirm = { _, _ -> Answer.Refused(409, "not_confirmed") }).run(request, PAYER)
        assertEquals(SendResult.Stopped(BuildText.UNKNOWN_SIGNED), signed)
        assertTrue(BuildText.UNKNOWN_SIGNED.contains("won't sign or submit a second copy"))
    }

    @Test
    fun `a transfer that is not this send ends the look at once`() = runBlocking {
        val result = flow(confirm = { _, _ -> Answer.Refused(402, "no_matching_transfer") }).run(request, PAYER)
        assertEquals(SendResult.Stopped(SendFlow.DID_NOT_MATCH), result)
    }

    @Test
    fun `a slow landing is looked for with growing waits, every look logged`() = runBlocking {
        val logged = mutableListOf<String>()
        val clock = longArrayOf(0L)
        var looks = 0
        val result = flow(
            confirm = { _, _ -> if (++looks < 3) Answer.Refused(409, "not_confirmed") else Answer.Ok("5555…5555") },
            log = { logged += it },
            clock = clock
        ).run(request, PAYER)
        assertEquals(SendResult.Sent("5555…5555"), result)
        assertEquals(5_000L, clock[0])
        assertEquals(3, logged.count { it.startsWith("send: check #") })
        assertTrue(logged.any { it.contains("check #1") && it.contains("result=409 not_confirmed") })
    }
}
