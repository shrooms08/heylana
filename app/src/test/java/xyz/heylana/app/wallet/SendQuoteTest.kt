package xyz.heylana.app.wallet

import org.junit.Assert.assertEquals
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
    fun `the strip names the amount, the name, the short address and the fee`() {
        assertEquals("Send 5 USDC to bob.skr (7c2y…nSxSv). Fee ~0.000005 SOL.".replace("nSxSv", "SxSv"), SendText.strip(quote()))
    }

    @Test
    fun `a plain address and a new token account are both said`() {
        assertEquals(
            "Send 0.05 USDC to 7c2y…SxSv. Fee ~0.000005 SOL, plus 0.00203928 SOL to open their USDC account.",
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
}
