package xyz.heylana.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.brain.SigningScan

class WindowMergeTest {

    private val own = "xyz.heylana.app"
    private val wallet = "com.solanamobile.wallet"
    private val payer = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"

    private fun raw(text: String? = null, desc: String? = null, clickable: Boolean = false) =
        WindowMerge.Raw(0, "View", text, desc, null, clickable, false, false, null, 0, 0, 10, 10)

    /** The Wallet's send sheet, its own window above the activity (Sept 16, devnet). */
    private val sheet = WindowMerge.Window(
        wallet, layer = 12,
        nodes = listOf(
            raw(desc = "Send"), raw(desc = "Sending"), raw(desc = "0.05 4zMM...ncDU"),
            raw(desc = "To"), raw(desc = "7c2y...SxSv"), raw(desc = "From"), raw(desc = "oghenekparobor.skr"),
            raw(desc = "Network fee"), raw(desc = "0.000018598 SOL"), raw(desc = "Send", clickable = true)
        )
    )

    /** The activity behind it: a long list, with one balance line at the very end. */
    private val activity = WindowMerge.Window(
        wallet, layer = 5,
        nodes = List(200) { raw(text = "Row $it", clickable = true) } + raw(desc = "Balance 12.5 USDC")
    )

    private val heylana = WindowMerge.Window(own, layer = 30, nodes = listOf(raw(text = "ask about this screen")))

    @Test
    fun `a sheet on top is read before the activity behind it, and Heylana is never read`() {
        val merged = WindowMerge.merge(listOf(activity, heylana, sheet), ownPackage = own)
        assertEquals(wallet, merged.packageName)
        assertEquals("Send", merged.nodes.first().description)
        assertTrue(merged.nodes.none { it.text == "ask about this screen" })
        assertEquals(listOf(12, 5), merged.windows.map { it.layer })
        assertEquals(12, merged.signingWordsFrom?.layer)
    }

    @Test
    fun `the cap keeps money, addresses and names first, wherever they sit`() {
        val merged = WindowMerge.merge(listOf(activity, sheet), ownPackage = own, cap = 120)
        assertEquals(120, merged.nodes.size)
        assertTrue(merged.truncated)
        // Element 201 of the activity, far past the cap in reading order, is still there.
        assertTrue(merged.nodes.any { it.description == "Balance 12.5 USDC" })
        assertEquals(mapOf(12 to 10, 5 to 110), merged.windows.associate { it.layer to it.kept })
    }

    @Test
    fun `what the signing check reads now has the whole send sheet in it`() {
        val merged = WindowMerge.merge(listOf(activity, heylana, sheet), ownPackage = own)
        val text = "App: Wallet ($wallet)\n" +
            merged.nodes.mapIndexed { i, n -> "[$i] ${n.className}" + (n.text?.let { " \"$it\"" } ?: "") + (n.description?.let { " ($it)" } ?: "") }
                .joinToString("\n")
        assertTrue(SigningScan.looksLikeSigning(wallet, text, ownWallet = payer))
        val found = SigningScan.of(text)
        assertTrue(found.amounts.contains("0.05 USDC"))
        assertEquals(listOf("7c2y…SxSv"), found.shortAddresses)
    }

    @Test
    fun `only Heylana's own windows means nothing to read`() {
        val merged = WindowMerge.merge(listOf(heylana), ownPackage = own)
        assertNull(merged.packageName)
        assertTrue(merged.nodes.isEmpty())
        assertNull(merged.signingWordsFrom)
    }
}
