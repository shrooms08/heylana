package xyz.heylana.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.brain.SolanaCore

/**
 * The user must never guess whether Heylana is describing, preparing, or waiting on a
 * signature, or which network it is on. These hold the labels, the order they come in, the
 * badge and every line said at the end of a send.
 */
class TxStateTest {

    private val treasury = "7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv"

    private val preview = TransferPreview(
        from = "EFj9…5L1S", fromLabel = "your wallet", to = "7c2y…SxSv", toLabel = "your Heylana treasury",
        amount = "0.01", token = "USDC", feeSol = "0.000005", accountRentSol = "0", createsAccount = false,
        programs = emptyList(), cluster = "devnet"
    )
    private val summary = TxSummary.of(preview)

    private fun walk(vararg events: TxEvent): List<String?> {
        var state: TxState? = null
        return events.map { event -> state = TxMachine.next(state, event); state?.label }
    }

    // ------------------------------------------------------------- the labels, in order

    @Test
    fun `built, waiting, then sent`() {
        assertEquals(
            listOf("Prepared, not signed", "Waiting for your wallet", "Signed, confirming", "Sent"),
            walk(TxEvent.Built(summary), TxEvent.WalletOpened, TxEvent.Checking(signed = true), TxEvent.Landed("5555…5555", null))
        )
    }

    @Test
    fun `rejected in the wallet is cancelled, and not sent`() {
        var state: TxState? = null
        for (event in listOf(TxEvent.Built(summary), TxEvent.WalletOpened, TxEvent.Rejected)) state = TxMachine.next(state, event)
        assertEquals(TxState.NotSent(TxEnding.CANCELLED), state)
        assertEquals("Not sent", state!!.label)
        // Cancel on the strip, before the wallet was ever opened, is the same ending.
        assertEquals(TxState.NotSent(TxEnding.CANCELLED), TxMachine.next(TxState.Prepared(summary), TxEvent.Rejected))
    }

    @Test
    fun `failed, before or after the wallet, is not sent`() {
        assertEquals(TxState.NotSent(TxEnding.FAILED), TxMachine.next(TxState.Prepared(summary), TxEvent.Failed))
        assertEquals(TxState.NotSent(TxEnding.FAILED), TxMachine.next(TxState.Waiting, TxEvent.Failed))
    }

    @Test
    fun `expired, while waiting or after signing, is not sent`() {
        assertEquals(TxState.NotSent(TxEnding.EXPIRED), TxMachine.next(TxState.Waiting, TxEvent.Expired))
        assertEquals(TxState.NotSent(TxEnding.EXPIRED), TxMachine.next(TxState.Confirming(signed = true), TxEvent.Expired))
    }

    @Test
    fun `signed and not seen is not confirmed yet - never not sent`() {
        assertEquals(TxState.Unsure, TxMachine.next(TxState.Confirming(signed = true), TxEvent.Unconfirmed))
        // Something landed that was not this send as prepared: the same, since it may have gone.
        assertEquals(TxState.Unsure, TxMachine.next(TxState.Confirming(signed = true), TxEvent.Failed))
        assertEquals("Not confirmed yet", TxState.Unsure.label)
    }

    @Test
    fun `the wallet ending without a signature is looked for, and can still be sent`() {
        assertEquals(
            listOf("Prepared, not signed", "Waiting for your wallet", "Checking the network", "Sent"),
            walk(TxEvent.Built(summary), TxEvent.WalletOpened, TxEvent.Checking(signed = false), TxEvent.Landed("4444…4444", null))
        )
    }

    @Test
    fun `nothing is sent that the wallet was never asked to sign, and an ending is final`() {
        // The chain cannot report a send that is still only prepared.
        assertEquals(TxState.Prepared(summary), TxMachine.next(TxState.Prepared(summary), TxEvent.Landed("5555…5555", null)))
        // Nothing before a build.
        assertNull(TxMachine.next(null, TxEvent.WalletOpened))
        // Ended stays ended, whatever arrives late.
        val sent = TxState.Sent("5555…5555", null)
        for (late in listOf(TxEvent.Rejected, TxEvent.Failed, TxEvent.Expired, TxEvent.WalletOpened, TxEvent.Unconfirmed)) {
            assertEquals(sent, TxMachine.next(sent, late))
        }
        val cancelled = TxState.NotSent(TxEnding.CANCELLED)
        assertEquals(cancelled, TxMachine.next(cancelled, TxEvent.Landed("5555…5555", null)))
        // Only a new build starts again.
        assertEquals(TxState.Prepared(summary), TxMachine.next(sent, TxEvent.Built(summary)))
    }

    // ------------------------------------------------------------- the prepared card

    @Test
    fun `the prepared card says what leaves, what arrives and the fee, from the simulation's preview`() {
        val card = TxText.preparedCard(summary)
        assertEquals(
            "Prepared, not signed · Devnet\n" +
                "Leaves your wallet: 0.01 USDC\n" +
                "Arrives: 0.01 USDC at your Heylana treasury (7c2y…SxSv)\n" +
                "Fee: 0.000005 SOL",
            card
        )
        val opening = TxSummary.of(preview.copy(createsAccount = true, accountRentSol = "0.00203928"))
        assertEquals("0.01 USDC, and 0.00203928 SOL to open their account", opening.leaves)
    }

    @Test
    fun `the spoken line says nothing moves until the wallet, and on devnet says devnet`() {
        assertEquals("I've prepared it on devnet. Nothing moves until you approve in your wallet.", TxText.preparedSpoken(Cluster.DEVNET))
        assertEquals("I've prepared it. Nothing moves until you approve in your wallet.", TxText.preparedSpoken(Cluster.MAINNET))
        assertEquals("On devnet: That's a lot.", TxText.onNetwork("That's a lot.", Cluster.DEVNET))
        assertEquals("That's a lot.", TxText.onNetwork("That's a lot.", Cluster.MAINNET))
    }

    // ------------------------------------------------------------- the network's badge

    @Test
    fun `the badge is the worker's cluster, amber on devnet, and nothing until it is heard`() {
        assertEquals(ClusterBadge.DEVNET, ClusterBadge.of("devnet"))
        assertEquals("Devnet", ClusterBadge.DEVNET.label)
        assertTrue(ClusterBadge.DEVNET.amber)
        assertEquals(ClusterBadge.MAINNET, ClusterBadge.of("mainnet-beta"))
        assertEquals("Mainnet", ClusterBadge.MAINNET.label)
        assertFalse(ClusterBadge.MAINNET.amber)
        // Never a guess: /me not heard yet, or a network Heylana does not know.
        assertNull(ClusterBadge.of(null as String?))
        assertNull(ClusterBadge.of(""))
        assertNull(ClusterBadge.of("testnet"))
        // The same answer the send path gets from the worker's quote.
        assertEquals(ClusterBadge.DEVNET, ClusterBadge.of(Cluster.fromWorker("devnet")))
        assertEquals("Waiting for your wallet · Mainnet", TxText.heading(TxState.Waiting, Cluster.MAINNET))
    }

    // ------------------------------------------------------------- the endings

    @Test
    fun `done names the amount, the token and the short address, with the signature on a chip`() {
        assertEquals("Done. 0.01 USDC went to 7c2y…SxSv.", TxText.done("0.01", "USDC", treasury))
        assertEquals("Signature 5555…5555", TxText.signatureChipTitle("5555…5555"))
        assertEquals("https://explorer.solana.com/tx/abc?cluster=devnet", TxText.explorerUrl("abc", Cluster.DEVNET))
        assertEquals("https://explorer.solana.com/tx/abc", TxText.explorerUrl("abc", Cluster.MAINNET))
    }

    @Test
    fun `rejected is cancelled, and nothing left the wallet`() {
        val ending = TxText.ending(SendResult.Stopped(BuildText.REJECTED, StopKind.CANCELLED))
        assertEquals(TxEvent.Rejected, ending.event)
        assertEquals("Cancelled. Nothing left your wallet.", ending.line)
    }

    @Test
    fun `a failure is the plain reason, one next step, and what left the wallet`() {
        val fee = TxText.ending(SendResult.Stopped("Your wallet has no SOL to pay the network fee.", StopKind.FAILED, "no_sol_for_fee"))
        assertEquals(TxEvent.Failed, fee.event)
        assertEquals(
            "Your wallet has no SOL to pay the network fee. You need about 0.001 SOL more for fees. Nothing left your wallet.",
            fee.line
        )
        val busy = TxText.ending(SendResult.Stopped("The network moved on before it could be checked. Ask again", code = "blockhash_expired"))
        assertEquals(
            "The network moved on before it could be checked. Ask again. The network was busy, try again. Nothing left your wallet.",
            busy.line
        )
        // A refusal on purpose has no next step that makes it safe; the wallet is still accounted for.
        val refused = TxText.ending(SendResult.Stopped(BuildText.NOT_WHAT_WAS_CONFIRMED, code = "not_what_was_confirmed"))
        assertEquals(BuildText.NOT_WHAT_WAS_CONFIRMED + " Nothing left your wallet.", refused.line)
        // An unknown word still gets a step.
        assertTrue(TxText.ending(SendResult.Stopped("Something odd.", code = "whatever")).line.contains("Try again in a moment."))
    }

    @Test
    fun `expired says so, and that nothing left the wallet`() {
        val ending = TxText.ending(SendResult.Stopped(BuildText.EXPIRED, StopKind.EXPIRED))
        assertEquals(TxEvent.Expired, ending.event)
        assertTrue(ending.line.startsWith("It expired before you signed it."))
        assertTrue(ending.line.endsWith("Nothing left your wallet."))
    }

    @Test
    fun `an unsure ending never claims nothing left the wallet`() {
        for (kind in listOf(StopKind.UNSURE_SIGNED, StopKind.NOT_FOUND)) {
            val ending = TxText.ending(SendResult.Stopped("x", kind))
            assertEquals(TxEvent.Unconfirmed, ending.event)
            assertFalse(kind.name, ending.line.contains("Nothing left your wallet"))
            assertTrue(kind.name, ending.line.contains("wallet"))
        }
        assertTrue(TxText.UNSURE_SIGNED.contains("I won't send it again"))
    }

    @Test
    fun `every ending says where the wallet stands, and none of them tries again by itself`() {
        val all = StopKind.entries.map { TxText.ending(SendResult.Stopped("A reason.", it, "no_sol_for_fee")).line }
        for (line in all) {
            assertTrue(line, line.contains("wallet"))
            assertFalse(line, line.contains("I'll try again") || line.contains("trying again now") || line.contains("retrying"))
        }
    }

    // ------------------------------------------------------------- facts and estimates

    @Test
    fun `prices are estimates with their age, balances are facts`() {
        val rules = SolanaCore.RULES
        assertTrue(rules, rules.contains("Prices, fees and yields are estimates"))
        assertTrue(rules, rules.contains("\"about\""))
        assertTrue(rules, rules.contains("as of a minute ago"))
        assertTrue(rules, rules.contains("A balance get_balances just read is a fact"))
    }
}
