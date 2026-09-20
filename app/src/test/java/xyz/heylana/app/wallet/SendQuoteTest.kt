package xyz.heylana.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendQuoteTest {

    private fun quote(
        amount: String = "5",
        token: String = "USDC",
        resolvedFrom: String? = "bob.skr",
        willCreateAta: Boolean = false,
        decimals: Int = 6
    ) = SendQuote(
        id = "id", toAddress = "7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv", resolvedFrom = resolvedFrom,
        amount = amount, token = token, mint = if (token == "SOL") null else "mint", decimals = decimals,
        tokenProgram = null, feeEstimate = "0.000005", accountRent = if (willCreateAta) "0.00203928" else "0",
        willCreateAta = willCreateAta, balance = "20", cluster = Cluster.DEVNET
    )

    @Test
    fun `the strip is the app's own words, with the address shortened`() {
        assertEquals("Send 0.05 USDC to 7c2y…SxSv. Confirm?", SendText.strip(quote(amount = "0.05", resolvedFrom = null)))
    }

    @Test
    fun `a name keeps its whole, and a new token account is said`() {
        assertEquals("Send 5 USDC to bob.skr (7c2y…SxSv). Confirm?", SendText.strip(quote()))
        assertEquals(
            "Send 0.05 USDC to 7c2y…SxSv. It also opens their USDC account for 0.00203928 SOL. Confirm?",
            SendText.strip(quote(amount = "0.05", resolvedFrom = null, willCreateAta = true))
        )
    }

    @Test
    fun `the amount becomes exact base units`() {
        assertEquals(50_000L, quote(amount = "0.05").units)
        assertEquals(250_000_000L, quote(amount = "0.25", token = "SOL", decimals = 9).units)
    }

    @Test(expected = ArithmeticException::class)
    fun `an amount finer than the token allows is refused, never rounded`() {
        quote(amount = "0.0000001").units
    }

    @Test
    fun `the first send's strip asks the user not to trust Heylana in Seed Vault, before Confirm`() {
        val first = SendText.previewed(BuiltFixtures.preview, firstSend = true)
        assertTrue(first.endsWith("Nothing has been signed. ${BuildText.TRUST_HINT} Confirm?"))
        assertTrue(!SendText.previewed(BuiltFixtures.preview).contains("trust this app"))
    }

    // ------------------------------------------------- what it does, and whether it is new

    @Test
    fun `the detail goes under the strip, and the spoken line is untouched`() {
        val strip = "Send 0.05 USDC from your wallet (EFj9\u20265L1S) to a wallet (7c2y\u2026SxSv). Confirm?"
        val detail = SendText.withDetail(strip, listOf("Sends 0.05 USDC to 7c2y\u2026SxSv."))
        assertTrue(detail.startsWith(strip))
        assertTrue(detail.contains("\u2022 Sends 0.05 USDC to 7c2y\u2026SxSv."))
        // Nothing to add means nothing added.
        assertEquals(strip, SendText.withDetail(strip, emptyList()))
    }

    @Test
    fun `a first-time address is the first thing the strip says`() {
        val strip = "Send 0.05 USDC to 7c2y\u2026SxSv. Confirm?"
        val detail = SendText.withDetail(strip, listOf("Sends 0.05 USDC to 7c2y\u2026SxSv."), firstDestination = true)
        val lines = detail.lines()
        assertEquals("\u2022 " + SendText.FIRST_DESTINATION, lines[1])
        assertTrue(lines[2].startsWith("\u2022 Sends"))
        // A fact about their own history, never a verdict.
        assertFalse(SendText.FIRST_DESTINATION.contains("scam", ignoreCase = true))
        assertFalse(SendText.FIRST_DESTINATION.contains("unsafe", ignoreCase = true))
    }

    @Test
    fun `at most four lines of detail, however many it found`() {
        val many = List(9) { "Line $it." }
        val detail = SendText.withDetail("Strip.", many, firstDestination = true)
        assertEquals(6, detail.lines().size) // the strip, the first-time line, four of the nine
    }
}
