package xyz.heylana.app.wallet

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendFlowTest {

    private val from = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val blockhash = "EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k"

    /** A quote as the worker's /send/prepare answer becomes one, cluster and all. */
    private fun quote(workerCluster: String) = SendQuote(
        id = "send-1", toAddress = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU", resolvedFrom = null,
        amount = "0.05", token = "USDC", mint = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU", decimals = 6,
        tokenProgram = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", feeEstimate = "0.000005", accountRent = "0",
        willCreateAta = false, balance = "5", cluster = Cluster.fromWorker(workerCluster)
    )

    @Test
    fun `a devnet worker produces a devnet send - blockhash, wallet and every log line`() = runBlocking {
        val asked = mutableListOf<String>()
        val logged = mutableListOf<String>()
        val flow = SendFlow(
            blockhash = { cluster -> asked += "blockhash:${cluster.id}"; Answer.Ok(blockhash) },
            signAndSend = { transaction, cluster ->
                asked += "wallet:${cluster.id}"
                assertTrue(transaction.isNotEmpty())
                SeedVault.Trip.Done("signature")
            },
            confirm = { _, _ -> Answer.Ok("5555…5555") },
            log = { logged += it },
            sleep = {}
        )

        val result = flow.run(SendFlow.Request.of(quote("devnet")), from)

        assertEquals(listOf("blockhash:devnet", "wallet:devnet"), asked)
        assertEquals(SendResult.Sent("5555…5555"), result)
        assertTrue(logged.all { !it.contains("cluster=") || it.contains("cluster=devnet") })
        assertEquals(3, logged.count { it.contains("cluster=devnet") })
    }

    @Test
    fun `a mainnet worker produces a mainnet send`() = runBlocking {
        val asked = mutableListOf<String>()
        val flow = SendFlow(
            blockhash = { asked += "blockhash:${it.id}"; Answer.Ok(blockhash) },
            signAndSend = { _, cluster -> asked += "wallet:${cluster.id}"; SeedVault.Trip.Done("signature") },
            confirm = { _, _ -> Answer.Ok("s") },
            log = {},
            sleep = {}
        )
        flow.run(SendFlow.Request.of(quote("mainnet-beta")), from)
        assertEquals(listOf("blockhash:mainnet-beta", "wallet:mainnet-beta"), asked)
    }

    @Test
    fun `a worker whose RPC is on another network stops the send before Seed Vault, in its own words`() = runBlocking {
        var walletAsked = false
        val line = "Heylana's server is set up for devnet but connected to mainnet-beta, so nothing can be sent until that is fixed."
        val flow = SendFlow(
            blockhash = { Answer.Refused(503, "rpc_wrong_cluster", line) },
            signAndSend = { _, _ -> walletAsked = true; SeedVault.Trip.Done("signature") },
            confirm = { _, _ -> Answer.Ok("s") },
            log = {},
            sleep = {}
        )
        val result = flow.run(SendFlow.Request.of(quote("devnet")), from)
        assertFalse(walletAsked)
        assertEquals(SendResult.Stopped(line), result)
    }

    @Test
    fun `a send that has not landed within a minute says so, and one that does not match says that`() = runBlocking {
        var clock = 0L
        val slow = SendFlow(
            blockhash = { Answer.Ok(blockhash) },
            signAndSend = { _, _ -> SeedVault.Trip.Done("signature") },
            confirm = { _, _ -> Answer.Refused(409, "not_confirmed") },
            log = {},
            now = { clock },
            sleep = { clock += it }
        )
        assertEquals(SendResult.Stopped(SendFlow.NOT_CONFIRMED_YET), slow.run(SendFlow.Request.of(quote("devnet")), from))
        assertTrue(clock <= SendFlow.LAND_TIMEOUT_MS)

        val wrong = SendFlow(
            blockhash = { Answer.Ok(blockhash) },
            signAndSend = { _, _ -> SeedVault.Trip.Done("signature") },
            confirm = { _, _ -> Answer.Refused(402, "no_matching_transfer") },
            log = {},
            sleep = {}
        )
        assertEquals(SendResult.Stopped(SendFlow.DID_NOT_MATCH), wrong.run(SendFlow.Request.of(quote("devnet")), from))
    }

    @Test
    fun `a wallet that errors after sending still reports Sent when the transfer is found on chain`() = runBlocking {
        val asked = mutableListOf<String?>()
        val flow = SendFlow(
            blockhash = { Answer.Ok(blockhash) },
            signAndSend = { _, _ -> SeedVault.Trip.Stopped(WalletProblem.UNKNOWN) },
            confirm = { _, signature -> asked += signature; Answer.Ok("4444…4444") },
            log = {},
            sleep = {}
        )
        assertEquals(SendResult.Sent("4444…4444"), flow.run(SendFlow.Request.of(quote("devnet")), from))
        assertEquals(listOf<String?>(null), asked)
    }

    @Test
    fun `declined in Seed Vault is said at once, with no search on chain`() = runBlocking {
        var searched = false
        val flow = SendFlow(
            blockhash = { Answer.Ok(blockhash) },
            signAndSend = { _, _ -> SeedVault.Trip.Stopped(WalletProblem.CANCELLED) },
            confirm = { _, _ -> searched = true; Answer.Ok("s") },
            log = {},
            sleep = {}
        )
        assertEquals(SendResult.Stopped(WalletProblem.CANCELLED.words), flow.run(SendFlow.Request.of(quote("devnet")), from))
        assertFalse(searched)
    }

    @Test
    fun `an unsure wallet and nothing on chain says check your wallet, never try again`() = runBlocking {
        var clock = 0L
        val flow = SendFlow(
            blockhash = { Answer.Ok(blockhash) },
            signAndSend = { _, _ -> SeedVault.Trip.Stopped(WalletProblem.UNKNOWN) },
            confirm = { _, _ -> Answer.Refused(409, "not_confirmed") },
            log = {},
            now = { clock },
            sleep = { clock += it }
        )
        assertEquals(SendResult.Stopped(SendFlow.UNSURE_LINE), flow.run(SendFlow.Request.of(quote("devnet")), from))
    }
}
