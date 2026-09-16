package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressTextTest {

    private val treasury = "7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv"
    private val payer = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"

    @Test
    fun `a full address is never spoken or shown whole`() {
        assertEquals(
            "Sending 0.05 USDC to 7c2y…SxSv, your treasury.",
            AddressText.shorten("Sending 0.05 USDC to $treasury, your treasury.")
        )
        assertEquals("7c2y…SxSv and 9WzD…AWWM", AddressText.shorten("$treasury and $payer"))
    }

    @Test
    fun `names stay whole, and short forms, prices and signatures are left alone`() {
        assertEquals("Send to bob.skr and toly.sol", AddressText.shorten("Send to bob.skr and toly.sol"))
        // 34 base58 characters, but it is a name.
        val longName = "abcdefghijkmnopqrstuvwxyzABCDEFGHJ.skr"
        assertEquals(longName, AddressText.shorten(longName))
        assertEquals("to 7c2y…SxSv for \$150.25", AddressText.shorten("to 7c2y…SxSv for \$150.25"))
        val signature = "5".repeat(88)
        assertEquals(signature, AddressText.shorten(signature))
    }

    @Test
    fun `short forms are found in either spelling and matched by their ends`() {
        assertEquals(listOf("7c2y…SxSv"), AddressText.shortAddresses("To 7c2y...SxSv"))
        assertTrue(AddressText.matches("7c2y…SxSv", treasury))
        assertFalse(AddressText.matches("7c2y…SxSw", treasury))
        assertFalse(AddressText.matches("nonsense", treasury))
    }

    @Test
    fun `typed addresses are remembered, latest last, only real keys, capped`() {
        val typed = TypedAddresses(keep = 2)
        typed.record("send to $treasury")
        typed.record("and DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263")
        typed.record("again $treasury")
        typed.record(payer)
        assertEquals(listOf(treasury, payer), typed.all())
        typed.record("Mys7eryMint1111111111111111111111111111111111")
        assertEquals(listOf(treasury, payer), typed.all())
    }
}
