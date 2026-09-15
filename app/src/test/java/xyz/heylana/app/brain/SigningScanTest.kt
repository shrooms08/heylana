package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningScanTest {

    private val treasury = "7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv"
    private val payer = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val wallet = "com.solanamobile.wallet"
    private val seedVault = "com.solanamobile.seedvaultimpl"

    private val confirmScreen = """
        App: com.solanamobile.wallet
        [1] Text: Confirm transaction
        [2] Text: Send 0.05 USDC
        [3] Text: To $treasury
        [4] Text: Network fee 0.000005 SOL
        [5] Button: Approve
    """.trimIndent()

    @Test
    fun `addresses are real 32-byte keys, each once, three at most`() {
        val text = "$treasury and $payer and $treasury again, " +
            "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263, 4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
        assertEquals(
            listOf(treasury, payer, "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"),
            SigningScan.addresses(text)
        )
        // 44 characters of base58 that decode to 33 bytes are not an address.
        assertEquals(emptyList<String>(), SigningScan.addresses("Mys7eryMint1111111111111111111111111111111111"))
    }

    @Test
    fun `amounts are numbers with a token or a dollar sign, not step counts`() {
        assertEquals(listOf("0.05 USDC", "0.000005 SOL"), SigningScan.amounts(confirmScreen))
        assertEquals(listOf("1,250.5 SKR", "\$12.30"), SigningScan.amounts("You get 1,250.5 SKR worth $ 12.30"))
        assertEquals(emptyList<String>(), SigningScan.amounts("Step 2 of 3, 5 items"))
    }

    @Test
    fun `Seed Vault is always a signing screen, whatever it shows`() {
        assertTrue(SigningScan.looksLikeSigning(seedVault, ""))
    }

    @Test
    fun `a wallet's confirm screen is one, and its home screen is not`() {
        assertTrue(SigningScan.looksLikeSigning(wallet, confirmScreen))
        val home = "[1] Text: 1.5 SOL\n[2] Button: Send\n[3] Button: Receive\n[4] Button: Swap"
        assertFalse(SigningScan.looksLikeSigning(wallet, home))
    }

    @Test
    fun `approve and an amount in Chrome is not a signing screen`() {
        assertFalse(SigningScan.looksLikeSigning("com.android.chrome", "Approve 5 USDC"))
    }

    @Test
    fun `a wallet confirm screen routes to the signing explanation on the task model`() {
        val route = Routing.forQuestion(wallet, "hello", confirmScreen)
        assertEquals(Routing.Why.SIGNING_SCREEN, route.why)
        assertEquals(ProxyClient.MODE_TASK, route.mode)
        assertTrue(route.explainsSigning)

        val home = Routing.forQuestion(wallet, "what is my balance", "[1] Text: 1.5 SOL\n[2] Button: Send")
        assertEquals(Routing.Why.WALLET_SCREEN, home.why)
        assertFalse(home.explainsSigning)
    }

    @Test
    fun `the signing message lists what was found and asks for plain findings, never safe`() {
        val found = SigningScan.of(confirmScreen)
        val message = HeylanaPrompt.signingMessage(confirmScreen, "what am I signing", found.addresses, found.amounts)
        assertTrue(message.contains("Addresses on screen: $treasury."))
        assertTrue(message.contains("Amounts on screen: 0.05 USDC, 0.000005 SOL."))
        assertTrue(message.contains("explain_address"))
        assertTrue(message.contains("fine, check the amount, or do not sign"))
        assertTrue(message.contains("Never call it safe"))
        assertTrue(message.endsWith("User asks: what am I signing"))
    }

    @Test
    fun `a signing screen with nothing readable says so instead of guessing`() {
        val message = HeylanaPrompt.signingMessage("App: com.solanamobile.seedvaultimpl\n(no readable elements)", "what is this", emptyList(), emptyList())
        assertTrue(message.contains("No addresses or amounts could be read from this screen."))
        assertTrue(message.contains("read the request in Seed Vault"))
    }
}
