package xyz.heylana.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceNetworkTest {

    @Test
    fun `on devnet the balance says so`() {
        // What the Seeker said over the Wallet on 2026-09-21, before this.
        assertEquals(
            "You've got 17.65 USDC on devnet and no SOL.",
            BalanceNetwork.said("You've got 17.65 USDC and no SOL.", devnet = true, balancesRead = true, appInFront = null)
        )
        // Nothing to hang it on: it leads.
        assertEquals(
            "On devnet: Your wallet is empty.",
            BalanceNetwork.said("Your wallet is empty.", devnet = true, balancesRead = true, appInFront = null)
        )
        // Already said: left alone.
        val already = "You have 2 SOL on devnet."
        assertEquals(already, BalanceNetwork.said(already, devnet = true, balancesRead = true, appInFront = null))
    }

    @Test
    fun `over a wallet app it says the two differ`() {
        val line = BalanceNetwork.said(
            "You've got 17.65 USDC.", devnet = true, balancesRead = true,
            appInFront = BalanceNetwork.appName("com.solanamobile.wallet")
        )
        assertEquals("You've got 17.65 USDC on devnet. That's your devnet balance; the Wallet shows mainnet.", line)
        // As the Seeker's answer came back on 2026-09-21: it said so itself, so nothing is added.
        val saidSo = "You have 17.65 USDC, but that's on devnet, not the mainnet wallet shown on your screen."
        assertEquals(saidSo, BalanceNetwork.said(saidSo, devnet = true, balancesRead = true, appInFront = "the Wallet"))
        assertEquals("Jupiter", BalanceNetwork.appName("ag.jup.jupiter.android"))
        assertNull(BalanceNetwork.appName("com.android.chrome"))
    }

    @Test
    fun `on mainnet, or with no balance read, nothing changes`() {
        val line = "You've got 17.65 USDC."
        assertEquals(line, BalanceNetwork.said(line, devnet = false, balancesRead = true, appInFront = "the Wallet"))
        assertEquals(line, BalanceNetwork.said(line, devnet = true, balancesRead = false, appInFront = "the Wallet"))
    }

    @Test
    fun `pieces get the network on the first amount and the app's line last`() {
        val pieces = BalanceNetwork.saidPieces(
            listOf("Your balances are here.", "You hold 0.5 SOL.", "And 3 USDC."),
            devnet = true, balancesRead = true, appInFront = "the Wallet"
        )
        assertEquals("You hold 0.5 SOL on devnet.", pieces[1])
        assertTrue(pieces[2].endsWith("the Wallet shows mainnet."))
        assertFalse(pieces[0].contains("devnet"))
    }

    @Test
    fun `the worker's usage line says whether balances were read`() {
        assertTrue(BalanceNetwork.readBalances("get_balances:212,get_price:90"))
        assertFalse(BalanceNetwork.readBalances("search_solana_kb:300"))
        assertFalse(BalanceNetwork.readBalances(null))
    }
}
