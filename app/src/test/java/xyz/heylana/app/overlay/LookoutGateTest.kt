package xyz.heylana.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.wallet.SendResult
import xyz.heylana.app.wallet.StopKind
import xyz.heylana.app.wallet.TransferPreview
import xyz.heylana.app.wallet.TxEvent
import xyz.heylana.app.wallet.TxMachine
import xyz.heylana.app.wallet.TxState
import xyz.heylana.app.wallet.TxSummary
import xyz.heylana.app.wallet.TxText

/**
 * Heylana's own send and a System UI window arriving together: the lookout never takes the
 * send's card or its ending line, and the in-app confirm rule never fires on System UI alone.
 */
class LookoutGateTest {

    private val summary = TxSummary.of(
        TransferPreview(
            from = "EFj9…5L1S", fromLabel = "your wallet", to = "7c2y…SxSv", toLabel = "your Heylana treasury",
            amount = "0.01", token = "USDC", feeSol = "0.000005", accountRentSol = "0", createsAccount = false,
            programs = emptyList(), cluster = "devnet"
        )
    )

    private val jupiter = "ag.jup.jupiter.android"
    private val wallet = "com.solanamobile.wallet"

    /** What the service does for a System UI window: the busy check, then the in-app rule. */
    private fun systemWindow(tx: TxState?, changedAt: Long, now: Long, front: String?): LookoutGate.Verdict =
        LookoutGate.inAppConfirm(front, ownSend = LookoutGate.ownSendActive(tx, changedAt, now))

    @Test
    fun `a System UI window during a devnet send never fires, and the reject still ends with its line`() {
        var now = 1_000L
        var tx: TxState? = TxMachine.next(null, TxEvent.Built(summary))
        var changedAt = now
        // Prepared, the card up: a System UI window (a heads-up, a toast) over Jupiter's form.
        assertEquals(LookoutGate.Verdict(false, "own_send"), systemWindow(tx, changedAt, now + 500, jupiter))

        // Confirm: Seed Vault opens.
        now += 4_000
        tx = TxMachine.next(tx, TxEvent.WalletOpened)
        changedAt = now
        assertEquals(TxState.Waiting, tx)
        assertFalse(systemWindow(tx, changedAt, now + 2_000, jupiter).fires)

        // Reject in Seed Vault: the ending is Not sent and the line said is the Cancelled one.
        now += 13_000
        val ending = TxText.ending(SendResult.Stopped("rejected", StopKind.CANCELLED, null))
        tx = TxMachine.next(tx, ending.event)
        changedAt = now
        assertTrue(tx is TxState.NotSent)
        assertEquals("Cancelled. Nothing left your wallet.", ending.line)
        // While that line is said and the card read, still nothing from the lookout.
        assertEquals(LookoutGate.Verdict(false, "own_send"), systemWindow(tx, changedAt, now + 1_000, jupiter))
        assertFalse(systemWindow(tx, changedAt, now + LookoutGate.ENDING_QUIET_MS - 1, jupiter).fires)

        // Afterwards, over Jupiter's form, the rule is back.
        assertTrue(systemWindow(tx, changedAt, now + LookoutGate.ENDING_QUIET_MS, jupiter).fires)
    }

    @Test
    fun `System UI by itself, or over an app with no entry, never fires`() {
        assertEquals(LookoutGate.Verdict(false, "no_app_in_front"), systemWindow(null, 0L, 10_000, null))
        assertEquals(LookoutGate.Verdict(false, "app_in_front_has_no_entry"), systemWindow(null, 0L, 10_000, wallet))
        assertEquals(LookoutGate.Verdict(true, "prompt_over_jupiter"), systemWindow(null, 0L, 10_000, jupiter))
    }

    @Test
    fun `a prepared card left alone does not silence the lookout for ever`() {
        val tx = TxMachine.next(null, TxEvent.Built(summary))
        assertTrue(LookoutGate.ownSendActive(tx, 1_000, 1_000 + LookoutGate.OWN_SEND_MAX_MS - 1))
        assertFalse(LookoutGate.ownSendActive(tx, 1_000, 1_000 + LookoutGate.OWN_SEND_MAX_MS))
        assertFalse(LookoutGate.ownSendActive(null, 0, 5_000))
    }
}
