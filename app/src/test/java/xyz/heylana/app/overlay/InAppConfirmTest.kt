package xyz.heylana.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.overlay.InAppConfirm.Item

class InAppConfirmTest {

    private val jupiter = InAppConfirm.appOf("ag.jup.jupiter.android")!!

    // Jupiter's Trade screen under the fingerprint prompt, as the Seeker showed it on Sept 21
    // (pixels): 0.0009 SOL for about 0.1071 USDC, the wallet holding 0.009 SOL.
    private val swapForm = listOf(
        Item("Swap", 52, 285, 158, 335), Item("Perps", 210, 285, 322, 335),
        Item("Market", 51, 400, 367, 484), Item("Limit", 388, 400, 704, 484),
        Item("Sell", 93, 565, 160, 603), Item("0.009", 1013, 565, 1108, 603),
        Item("SOL", 200, 697, 280, 745), Item("0.0009", 821, 665, 1108, 730), Item("\$0.107", 1004, 750, 1108, 790),
        Item("Buy", 93, 895, 163, 935), Item("0.1159", 1013, 895, 1108, 935),
        Item("USDC", 200, 1020, 313, 1070), Item("0.1071", 861, 995, 1108, 1060), Item("\$0.107", 1004, 1080, 1108, 1120),
        Item("1 SOL ≈ 119.1 USDC", 132, 1210, 391, 1250),
        Item("Swap", 547, 1709, 654, 1759), Item("MAX", 52, 1840, 305, 1960), Item("CLEAR", 52, 2270, 305, 2380),
        Item("Home", 120, 2480, 230, 2540), Item("Trade", 580, 2480, 690, 2540),
    )

    @Test
    fun `the Jupiter swap form under the prompt is read, and the line says its amounts`() {
        val sheet = InAppConfirm.read(jupiter, swapForm)!!
        assertEquals("0.0009", sheet.payAmount)
        assertEquals("SOL", sheet.payToken)
        assertEquals("0.1071", sheet.getAmount)
        assertEquals("USDC", sheet.getToken)
        assertEquals("0.009", sheet.balance)
        assertEquals("Jupiter wants you to confirm: 0.0009 SOL for about 0.1071 USDC.", InAppConfirm.spoken(sheet))
        assertFalse(InAppConfirm.spoken(sheet).contains("safe", ignoreCase = true))
    }

    @Test
    fun `a normal Jupiter screen is no confirm form`() {
        // Home: the portfolio, the watchlist and the bottom bar.
        val home = listOf(
            Item("\$1.18", 440, 330, 760, 440), Item("Send", 150, 740, 225, 790), Item("Swap", 970, 740, 1050, 790),
            Item("Watchlist", 100, 1100, 310, 1160), Item("SOL", 305, 1210, 380, 1260), Item("0.009078181", 890, 1945, 1100, 1995),
            Item("Home", 120, 2480, 230, 2540), Item("Trade", 580, 2480, 690, 2540),
        )
        assertNull(InAppConfirm.read(jupiter, home))
    }

    @Test
    fun `only Android's fingerprint prompt triggers, never the shade or an unlock`() {
        assertTrue(InAppConfirm.biometricPrompt("com.android.systemui", "com.android.systemui.biometrics.AuthContainerView"))
        assertTrue(InAppConfirm.biometricPrompt("com.android.systemui", "android.widget.FrameLayout Touch the fingerprint sensor"))
        assertFalse(InAppConfirm.biometricPrompt("com.android.systemui", "com.android.systemui.shade.NotificationShadeWindowView"))
        assertFalse(InAppConfirm.biometricPrompt("com.android.systemui", "com.android.systemui.volume.VolumeDialogImpl"))
        // The Seeker's prompt says nothing about fingerprints; the form under it decides.
        assertTrue(InAppConfirm.biometricPrompt("com.android.systemui", "android.widget.FrameLayout Swap"))
        assertFalse(InAppConfirm.biometricPrompt("com.android.systemui", "BiometricPrompt Unlock Jupiter"))
        assertFalse(InAppConfirm.biometricPrompt("ag.jup.jupiter.android", "fingerprint"))
    }

    @Test
    fun `a prompt over a form it cannot read names the app and says where to look, never an amount`() {
        val line = InAppConfirm.unreadLine(jupiter)
        assertEquals("Jupiter wants you to confirm a swap. Check the amounts before you touch the sensor.", line)
        assertFalse(line.any { it.isDigit() })
        assertFalse(line.contains("safe", ignoreCase = true))
    }

    @Test
    fun `most of the balance leaving, and a copied token name, are said`() {
        val most = InAppConfirm.read(jupiter, swapForm.map { if (it.text == "0.0009") it.copy(text = "0.0085") else it })!!
        assertTrue(InAppConfirm.detail(most).contains("almost all the SOL in this wallet"))
        val copied = InAppConfirm.read(jupiter, swapForm.map { if (it.text == "USDC") it.copy(text = "USDCC") else it })!!
        assertTrue(InAppConfirm.detail(copied).contains("Check the token: USDCC is not USDC."))
        assertNull(InAppConfirm.lookAlikeToken("USDC"))
        assertNotNull(InAppConfirm.lookAlikeToken("S0L"))
    }
}
