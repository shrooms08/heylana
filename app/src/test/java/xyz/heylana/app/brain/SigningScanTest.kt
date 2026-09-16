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
    private val chrome = "com.android.chrome"

    private val confirmScreen = """
        App: com.solanamobile.wallet
        [1] Text: Confirm transaction
        [2] Text: Send 0.05 USDC
        [3] Text: To $treasury
        [4] Text: Network fee 0.000005 SOL
        [5] Button: Approve
    """.trimIndent()

    /** How the Wallet and Seed Vault actually print addresses. */
    private val shortConfirmScreen = """
        App: com.solanamobile.wallet
        [1] Text: Review
        [2] Text: 0.05 USDC
        [3] Text: To 7c2y…SxSv
        [4] Text: From 9WzD…AWWM
        [5] Button: Confirm
    """.trimIndent()

    /** The Wallet's real confirm screen, as its content descriptions read (Sept 16, devnet). */
    private val walletSendScreen = """
        App: com.solanamobile.wallet
        [1] View (Send)
        [2] View (Sending)
        [3] View (0.05 4zMM...ncDU)
        [4] View (Value)
        [5] View (${'$'}0.00)
        [6] View (To)
        [7] View (7c2y...SxSv)
        [8] View (From)
        [9] View (oghenekparobor.skr)
        [10] View (Network fee)
        [11] View (0.000018598 SOL)
        [12] Button (Send)
    """.trimIndent()

    @Test
    fun `the Wallet's own send screen is a signing screen, read the way it prints things`() {
        assertTrue(SigningScan.looksLikeSigning(wallet, walletSendScreen, ownWallet = payer))
        val found = SigningScan.of(walletSendScreen)
        assertEquals(listOf("0.000018598 SOL", "0.05 USDC", "${'$'}0.00"), found.amounts)
        // The mint after the amount is the token, not a recipient.
        assertEquals(listOf("7c2y…SxSv"), found.shortAddresses)
        assertEquals(Routing.Why.SIGNING_SCREEN, Routing.forQuestion(wallet, "what am I signing", walletSendScreen, payer).why)
    }

    @Test
    fun `a known mint is its symbol, full or shortened, and an unknown one says so`() {
        assertEquals("USDC", SigningScan.tokenFor("4zMM…ncDU"))
        assertEquals("USDC", SigningScan.tokenFor("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"))
        assertEquals(listOf("3 of the token AAAA…BBBB"), SigningScan.amounts("[1] View (3 AAAA...BBBB)"))
    }

    @Test
    fun `addresses are real 32-byte keys, each once, three at most`() {
        val text = "$treasury and $payer and $treasury again, " +
            "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263, 4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
        assertEquals(
            listOf(treasury, payer, "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"),
            SigningScan.addresses(text)
        )
        assertEquals(emptyList<String>(), SigningScan.addresses("Mys7eryMint1111111111111111111111111111111111"))
    }

    @Test
    fun `amounts are numbers with a token or a dollar sign, not step counts`() {
        assertEquals(listOf("0.05 USDC", "0.000005 SOL"), SigningScan.amounts(confirmScreen))
        assertEquals(listOf("1,250.5 SKR", "\$12.30"), SigningScan.amounts("You get 1,250.5 SKR worth $ 12.30"))
        assertEquals(emptyList<String>(), SigningScan.amounts("Step 2 of 3, 5 items"))
    }

    @Test
    fun `shortened addresses are found too, written one way`() {
        val found = SigningScan.of(shortConfirmScreen)
        assertEquals(emptyList<String>(), found.addresses)
        assertEquals(listOf("7c2y…SxSv", "9WzD…AWWM"), found.shortAddresses)
        assertEquals(listOf("0.05 USDC"), found.amounts)
        assertEquals(listOf("7c2y…SxSv"), SigningScan.of("[1] Text: to 7c2y...SxSv").shortAddresses)
    }

    @Test
    fun `Seed Vault is always a signing screen, whatever it shows`() {
        assertTrue(SigningScan.looksLikeSigning(seedVault, ""))
    }

    @Test
    fun `a wallet's confirm screen is one, full or shortened, and its home screen is not`() {
        assertTrue(SigningScan.looksLikeSigning(wallet, confirmScreen))
        assertTrue(SigningScan.looksLikeSigning(wallet, shortConfirmScreen, ownWallet = payer))
        val home = "[1] Text: 1.5 SOL\n[2] Button: Send\n[3] Button: Receive\n[4] Button: Swap"
        assertFalse(SigningScan.looksLikeSigning(wallet, home, ownWallet = payer))
        // Your own shortened address next to "Review backup" is not a request to sign.
        assertFalse(SigningScan.looksLikeSigning(wallet, "[1] Text: 9WzD…AWWM\n[2] Button: Review backup", ownWallet = payer))
    }

    @Test
    fun `approve and an amount in Chrome is not a signing screen`() {
        assertFalse(SigningScan.looksLikeSigning(chrome, "Approve 5 USDC"))
    }

    @Test
    fun `a wallet confirm screen routes to the signing explanation on the task model`() {
        val route = Routing.forQuestion(wallet, "hello", confirmScreen)
        assertEquals(Routing.Why.SIGNING_SCREEN, route.why)
        assertEquals(ProxyClient.MODE_TASK, route.mode)
        assertTrue(route.explainsSigning)
        assertEquals(Routing.Why.SIGNING_SCREEN, Routing.forQuestion(wallet, "what am I signing", shortConfirmScreen, payer).why)

        val home = Routing.forQuestion(wallet, "what is my balance", "[1] Text: 1.5 SOL\n[2] Button: Send")
        assertEquals(Routing.Why.WALLET_SCREEN, home.why)
        assertFalse(home.explainsSigning)
    }

    @Test
    fun `the signing message lists what was found and asks for plain findings, never safe`() {
        val message = HeylanaPrompt.signingMessage(confirmScreen, "what am I signing", SigningScan.of(confirmScreen))
        assertTrue(message.contains("Addresses on screen: $treasury."))
        assertTrue(message.contains("Amounts on screen: 0.05 USDC, 0.000005 SOL."))
        assertTrue(message.contains("explain_address"))
        assertTrue(message.contains("fine, check the amount, or do not sign"))
        assertTrue(message.contains("Never call it safe"))
        assertTrue(message.endsWith("User asks: what am I signing"))
    }

    @Test
    fun `shortened addresses are listed, never reported as unreadable`() {
        val message = HeylanaPrompt.signingMessage(shortConfirmScreen, "what am I signing", SigningScan.of(shortConfirmScreen))
        assertTrue(message.contains("Addresses on screen: 7c2y…SxSv, 9WzD…AWWM."))
        assertFalse(message.contains("No addresses or amounts could be read"))
        assertTrue(message.contains("I can't verify a shortened address from here; check it matches who you meant."))
    }

    @Test
    fun `a signing screen with nothing readable says so instead of guessing`() {
        val message = HeylanaPrompt.signingMessage(
            "App: com.solanamobile.seedvaultimpl\n(no readable elements)", "what is this",
            SigningScan.Found(emptyList(), emptyList(), emptyList())
        )
        assertTrue(message.contains("No addresses or amounts could be read from this screen."))
        assertTrue(message.contains("read the request in Seed Vault"))
    }

    @Test
    fun `no greeting on a send or a signing explanation`() {
        assertFalse(Routing.forQuestion(chrome, "send 0.05 USDC to $treasury").allowsGreeting)
        assertFalse(Routing.forQuestion(chrome, "what am I signing").allowsGreeting)
        assertFalse(Routing.forQuestion(wallet, "hello", confirmScreen).allowsGreeting)
        assertTrue(Routing.forQuestion(chrome, "what is the capital of Nigeria").allowsGreeting)
    }
}
