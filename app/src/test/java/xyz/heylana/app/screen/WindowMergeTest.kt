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
    fun `more below is the app in front's, never a window behind it or Heylana's`() {
        val chrome = WindowMerge.Window("com.android.chrome", 2, listOf(raw("answer")), moreBelow = true)
        assertEquals(true, WindowMerge.merge(listOf(chrome), "xyz.heylana.app").moreBelow)
        val sheet = WindowMerge.Window("com.solanamobile.wallet", 5, listOf(raw("Send")))
        val behind = WindowMerge.Window("com.android.chrome", 2, listOf(raw("page")), moreBelow = true)
        assertEquals(false, WindowMerge.merge(listOf(sheet, behind), "xyz.heylana.app").moreBelow)
        val own = WindowMerge.Window("xyz.heylana.app", 9, listOf(raw("box")), moreBelow = true)
        assertEquals(false, WindowMerge.merge(listOf(own, sheet), "xyz.heylana.app").moreBelow)
    }

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

    /** The notification shade over the app: a System UI window with someone else's words on it. */
    private val shade = WindowMerge.Window(
        "com.android.systemui", layer = 2_000_000,
        nodes = listOf(raw(text = "Ada: see you at 6"), raw(text = "Quick settings"), raw(text = "Wi-Fi"))
    )

    @Test
    fun `the notification shade, quick settings and lock screen are never read`() {
        val merged = WindowMerge.merge(listOf(shade, sheet, heylana), ownPackage = own)
        assertEquals(wallet, merged.packageName)
        assertTrue(merged.nodes.none { it.text == "Ada: see you at 6" || it.text == "Wi-Fi" })
        assertEquals(listOf(wallet), merged.windows.map { it.packageName })
        // Logged by package and layer only, never read.
        assertEquals(listOf(WindowMerge.WindowCount("com.android.systemui", 2_000_000, 0, 0)), merged.skipped)
    }

    @Test
    fun `with only the shade in front there is nothing to read`() {
        val merged = WindowMerge.merge(listOf(shade), ownPackage = own)
        assertNull(merged.packageName)
        assertTrue(merged.nodes.isEmpty())
        assertTrue(WindowMerge.isSkipped("com.android.systemui", own))
        assertTrue(WindowMerge.isSkipped(own, own))
        assertTrue(WindowMerge.isSkipped(null, own))
        assertEquals(false, WindowMerge.isSkipped(wallet, own))
    }
}
