package xyz.heylana.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the wallet library reports becomes a plain line, and nothing it said is shown as-is. */
class WalletProblemTest {

    @Test
    fun `declining in Seed Vault reads as cancelled`() {
        assertEquals(WalletProblem.CANCELLED, WalletProblem.from("JsonRpc20RemoteException", "authorization declined"))
        assertEquals(WalletProblem.CANCELLED, WalletProblem.from(null, "transaction not signed"))
    }

    @Test
    fun `a wallet with too little reads as not enough`() {
        assertEquals(WalletProblem.NOT_ENOUGH, WalletProblem.from(null, "Transaction simulation failed: insufficient funds"))
        assertEquals(WalletProblem.NOT_ENOUGH, WalletProblem.from(null, "custom program error: 0x1"))
    }

    @Test
    fun `a timeout reads as took too long`() {
        assertEquals(WalletProblem.TOOK_TOO_LONG, WalletProblem.from("TimeoutException", null))
    }

    @Test
    fun `anything else is a plain try again, never the library's words`() {
        val problem = WalletProblem.from("IllegalStateException", "something internal at line 42")
        assertEquals(WalletProblem.UNKNOWN, problem)
        assertEquals("That didn't go through. Try again.", problem.words)
    }

    @Test
    fun `the wallet's own error code decides before its words`() {
        assertEquals(WalletProblem.CANCELLED, WalletProblem.fromRemote(-1, "JsonRpc20RemoteException", "whatever"))
        assertEquals(WalletProblem.CANCELLED, WalletProblem.fromRemote(-3, null, null))
        // Signed but perhaps not sent: unsure, so the send path looks on chain.
        assertEquals(WalletProblem.UNKNOWN, WalletProblem.fromRemote(-4, "NotSubmittedException", "insufficient funds"))
        assertEquals(WalletProblem.WRONG_NETWORK, WalletProblem.fromRemote(-7, null, null))
        assertEquals(WalletProblem.TOOK_TOO_LONG, WalletProblem.fromRemote(null, "TimeoutException", null))
    }

    @Test
    fun `the worker's reasons map to the same plain lines`() {
        assertEquals(WalletProblem.SESSION_ENDED, WalletProblem.fromWorker("bad_session"))
        assertEquals(WalletProblem.MISMATCH, WalletProblem.fromWorker("short_amount"))
        assertEquals(WalletProblem.MISMATCH, WalletProblem.fromWorker("wrong_mint"))
        assertEquals(WalletProblem.NOT_SET_UP, WalletProblem.fromWorker("not_configured"))
        assertEquals(WalletProblem.TOOK_TOO_LONG, WalletProblem.fromWorker("unknown_quote"))
    }
}
