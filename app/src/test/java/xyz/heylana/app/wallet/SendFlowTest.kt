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

    /** The worker's answer when it sees the send land; it finds the whole signature only when it looked itself. */
    private fun landed(short: String, full: String? = null) = Landed(short, full)
    private val passes: suspend (String, Cluster) -> Answer<BuiltTransfer> = { _, _ -> Answer.Ok(BuiltFixtures.built(BuiltFixtures.TOKEN)) }

    private fun flow(
        build: suspend (String, Cluster) -> Answer<BuiltTransfer> = passes,
        wallet: suspend (ByteArray, Cluster) -> SeedVault.Trip<SeedVault.Signed> = { _, _ -> SeedVault.Trip.Done(SeedVault.Signed("signature", 5_000)) },
        confirm: suspend (String, String?) -> Answer<Landed> = { _, _ -> Answer.Ok(landed("5555…5555")) },
        log: (String) -> Unit = {},
        clock: LongArray = longArrayOf(0L),
        stages: MutableList<SendStage> = mutableListOf(),
        /** The wait on the wallet: by default it answers, as a wallet that comes back does. */
        wait: suspend (suspend () -> SeedVault.Trip<SeedVault.Signed>) -> SeedVault.Trip<SeedVault.Signed>? = { it() }
    ) = SendFlow(
        build, wallet, confirm, log,
        now = { clock[0] }, sleep = { clock[0] += it }, waitForWallet = wait, stage = { stages += it }
    )

    @Test
    fun `a passing simulation opens the wallet with exactly the simulated bytes, on the send's cluster`() = runBlocking {
        val asked = mutableListOf<String>()
        val handed = mutableListOf<ByteArray>()
        val logged = mutableListOf<String>()
        val stages = mutableListOf<SendStage>()
        val result = flow(
            build = { id, cluster -> asked += "build:$id:${cluster.id}"; Answer.Ok(BuiltFixtures.built(BuiltFixtures.TOKEN)) },
            wallet = { bytes, cluster -> asked += "wallet:${cluster.id}"; handed += bytes; SeedVault.Trip.Done(SeedVault.Signed("signature", 5_000)) },
            log = { logged += it },
            stages = stages
        ).run(request, PAYER)

        // The wallet's own signature, whole, for the Explorer link under "Done".
        assertEquals(SendResult.Sent("5555…5555", fullSignature = "signature"), result)
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
            wallet = { _, _ -> walletAsked = true; SeedVault.Trip.Done(SeedVault.Signed("signature", 5_000)) }
        ).run(request, PAYER)
        assertFalse(walletAsked)
        assertEquals(
            // The worker's plain reason and its one word: the next step is chosen from the word.
            SendResult.Stopped("Not enough USDC. You have 0.03.", StopKind.FAILED, "not_enough_token"),
            result
        )
    }

    @Test
    fun `the wrong network stops before the wallet, in the worker's words`() = runBlocking {
        var walletAsked = false
        val line = "This is for mainnet-beta, but Heylana is on devnet. I stopped before building it."
        val result = flow(
            build = { _, _ -> Answer.Refused(409, "wrong_cluster", line) },
            wallet = { _, _ -> walletAsked = true; SeedVault.Trip.Done(SeedVault.Signed("signature", 5_000)) }
        ).run(request, PAYER)
        assertFalse(walletAsked)
        assertEquals(SendResult.Stopped(line, StopKind.FAILED, "wrong_cluster"), result)
    }

    @Test
    fun `bytes that are not the confirmed transfer never reach the wallet`() = runBlocking {
        var walletAsked = false
        // The worker hands back a SOL transfer for a USDC send.
        val result = flow(
            build = { _, _ -> Answer.Ok(BuiltFixtures.built(BuiltFixtures.SOL)) },
            wallet = { _, _ -> walletAsked = true; SeedVault.Trip.Done(SeedVault.Signed("signature", 5_000)) }
        ).run(request, PAYER)
        assertFalse(walletAsked)
        assertEquals(SendResult.Stopped(BuildText.NOT_WHAT_WAS_CONFIRMED, StopKind.FAILED, "not_what_was_confirmed"), result)
    }

    @Test
    fun `rejected in the wallet is final - no search on chain, never asked again`() = runBlocking {
        var searched = false
        var walletTimes = 0
        val result = flow(
            wallet = { _, _ -> walletTimes++; SeedVault.Trip.Stopped(WalletProblem.CANCELLED) },
            confirm = { _, _ -> searched = true; Answer.Ok(landed("s")) }
        ).run(request, PAYER)
        assertEquals(SendResult.Stopped(BuildText.REJECTED, StopKind.CANCELLED), result)
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
        assertEquals(SendResult.Stopped(BuildText.EXPIRED, StopKind.EXPIRED), result)
        assertEquals(1, walletTimes)
    }

    @Test
    fun `unknown then found by its reference is Sent`() = runBlocking {
        val asked = mutableListOf<String?>()
        var looks = 0
        val stages = mutableListOf<SendStage>()
        val result = flow(
            wallet = { _, _ -> SeedVault.Trip.Stopped(WalletProblem.UNKNOWN) },
            confirm = { _, signature -> asked += signature; if (++looks < 3) Answer.Refused(409, "not_confirmed") else Answer.Ok(landed("4444…4444", "4444full")) },
            stages = stages
        ).run(request, PAYER)
        // No signature from the wallet: the worker found it, and hands the whole one back.
        assertEquals(SendResult.Sent("4444…4444", fullSignature = "4444full"), result)
        assertEquals(SendStage.LOOKING, stages.last())
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
        assertEquals(SendResult.Stopped(BuildText.NOT_FOUND, StopKind.NOT_FOUND), withoutSignature)
        assertEquals(SendFlow.LAND_TIMEOUT_MS, clock[0])
        assertFalse(BuildText.NOT_FOUND.contains("try again", ignoreCase = true) && !BuildText.NOT_FOUND.contains("before trying again"))

        val signed = flow(confirm = { _, _ -> Answer.Refused(409, "not_confirmed") }).run(request, PAYER)
        assertEquals(SendResult.Stopped(BuildText.UNKNOWN_SIGNED, StopKind.UNSURE_SIGNED), signed)
        assertTrue(BuildText.UNKNOWN_SIGNED.contains("won't sign or submit a second copy"))
    }

    @Test
    fun `a transfer that is not this send ends the look at once`() = runBlocking {
        val result = flow(confirm = { _, _ -> Answer.Refused(402, "no_matching_transfer") }).run(request, PAYER)
        // Something landed that is not this send: it may have left the wallet, so it is never "not sent".
        assertEquals(SendResult.Stopped(SendFlow.DID_NOT_MATCH, StopKind.UNSURE_SIGNED), result)
    }

    @Test
    fun `a slow landing is looked for with growing waits, every look logged`() = runBlocking {
        val logged = mutableListOf<String>()
        val clock = longArrayOf(0L)
        var looks = 0
        val result = flow(
            confirm = { _, _ -> if (++looks < 3) Answer.Refused(409, "not_confirmed") else Answer.Ok(landed("5555…5555")) },
            log = { logged += it },
            clock = clock
        ).run(request, PAYER)
        assertEquals(SendResult.Sent("5555…5555", fullSignature = "signature"), result)
        assertEquals(5_000L, clock[0])
        assertEquals(3, logged.count { it.startsWith("send: check #") })
        assertTrue(logged.any { it.contains("check #1") && it.contains("result=409 not_confirmed") })
    }

    @Test
    fun `a signature back within 1500ms of Seed Vault opening was signed automatically, and is said so`() = runBlocking {
        val fast = flow(wallet = { _, _ -> SeedVault.Trip.Done(SeedVault.Signed("signature", 400)) }).run(request, PAYER)
        assertEquals(SendResult.Sent("5555…5555", autoSigned = true, fullSignature = "signature"), fast)
        val read = flow(wallet = { _, _ -> SeedVault.Trip.Done(SeedVault.Signed("signature", 1_500)) }).run(request, PAYER)
        assertEquals(SendResult.Sent("5555…5555", autoSigned = false, fullSignature = "signature"), read)
        assertTrue(BuildText.AUTO_SIGNED.startsWith("Seed Vault signed that automatically because Heylana is marked trusted there."))
    }

    @Test
    fun `a wallet that never comes back ends the send as not sent, and nothing is asked again`() = runBlocking {
        val logged = mutableListOf<String>()
        var looks = 0
        val stages = mutableListOf<SendStage>()
        val result = flow(
            confirm = { _, _ -> looks++; Answer.Ok(landed("5555…5555")) },
            log = { logged += it },
            stages = stages,
            // The wallet is opened and simply never answers: the wait is given up on.
            wait = { null }
        ).run(request, PAYER)

        assertEquals(SendResult.Stopped(TxText.NO_ANSWER, StopKind.NO_ANSWER, "wallet_no_answer"), result)
        // Never looked on the network, never asked the wallet a second time.
        assertEquals(0, looks)
        assertEquals(listOf(SendStage.SIMULATING, SendStage.APPROVE_IN_WALLET), stages)
        assertTrue(logged.any { it.contains("the wallet never came back") })
        assertEquals(60_000L, SendFlow.WALLET_TIMEOUT_MS)
    }

    @Test
    fun `the card ends as not sent, in the words the user hears`() {
        val stopped = SendResult.Stopped(TxText.NO_ANSWER, StopKind.NO_ANSWER, "wallet_no_answer")
        val ending = TxText.ending(stopped)
        assertEquals(TxEvent.Failed, ending.event)
        assertEquals(
            "Your wallet didn't come back. Nothing left your wallet. " +
                "Check your wallet app, and try again if it's clear.",
            ending.line
        )
        val ended = TxMachine.next(TxState.Waiting, ending.event)
        assertEquals(TxState.NotSent(TxEnding.FAILED), ended)
        assertEquals("Not sent", (ended as TxState.NotSent).label)
    }

    @Test
    fun `a wallet answer that arrives after it was given up on neither revives the card nor sends again`() = runBlocking {
        var looks = 0
        var walletRuns = 0
        var late: (suspend () -> SeedVault.Trip<SeedVault.Signed>)? = null
        val result = flow(
            wallet = { _, _ -> walletRuns++; SeedVault.Trip.Done(SeedVault.Signed("signature", 5_000)) },
            confirm = { _, _ -> looks++; Answer.Ok(landed("5555…5555")) },
            // The trip is kept, not run: the wallet is still thinking when the wait ends.
            wait = { trip -> late = trip; null }
        ).run(request, PAYER)
        assertEquals(StopKind.NO_ANSWER, (result as SendResult.Stopped).kind)

        // The wallet finally answers, signed and all. Nothing follows from it.
        val answered = late!!.invoke()
        assertTrue(answered is SeedVault.Trip.Done)
        assertEquals(1, walletRuns)
        assertEquals(0, looks)

        // And the card it belonged to has ended: no event moves it again.
        val ended = TxState.NotSent(TxEnding.FAILED)
        assertEquals(ended, TxMachine.next(ended, TxEvent.Landed("5555…5555", "https://explorer.solana.com/tx/5")))
        assertEquals(ended, TxMachine.next(ended, TxEvent.Checking(signed = true)))
    }
}
