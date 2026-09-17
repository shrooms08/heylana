package xyz.heylana.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.actions.QuickGuard.Verdict

class QuickGuardTest {

    private fun allowed(action: QuickAction, said: String) = (QuickGuard.check(action, said) as? Verdict.Allowed)?.action
    private fun refused(action: QuickAction, said: String) = (QuickGuard.check(action, said) as? Verdict.Refused)?.reason

    @Test
    fun `an alarm needs the time the user said`() {
        assertEquals(QuickAction.Alarm(7, 0, null), allowed(QuickAction.Alarm(7, 0, null), "set an alarm for 7 tomorrow"))
        assertTrue(allowed(QuickAction.Alarm(7, 30, null), "wake me at 7:30") != null)
        assertTrue(allowed(QuickAction.Alarm(19, 0, null), "alarm at 7pm") != null)
        assertTrue(allowed(QuickAction.Alarm(6, 45, null), "alarm for 0645") != null)
        assertTrue(allowed(QuickAction.Alarm(7, 30, null), "wake me at half past seven") != null)
        assertTrue(allowed(QuickAction.Alarm(12, 0, null), "alarm at noon") != null)
        assertEquals("time_not_said", refused(QuickAction.Alarm(8, 0, null), "set an alarm for 7 tomorrow"))
        assertEquals("time_not_said", refused(QuickAction.Alarm(7, 15, null), "set an alarm for 7 tomorrow"))
    }

    @Test
    fun `7 tomorrow is 7 in the morning, whatever the model wrote`() {
        assertEquals(QuickAction.Alarm(7, 0, null), allowed(QuickAction.Alarm(19, 0, null), "set an alarm for 7 tomorrow"))
        assertEquals(QuickAction.Alarm(7, 0, null), allowed(QuickAction.Alarm(7, 0, null), "set an alarm for 7 tomorrow"))
        assertEquals(QuickAction.Alarm(7, 0, null), allowed(QuickAction.Alarm(19, 0, null), "alarm at 7 in the morning"))
        assertEquals(QuickAction.Alarm(19, 0, null), allowed(QuickAction.Alarm(7, 0, null), "alarm at 7 this evening"))
        assertEquals(QuickAction.Alarm(19, 0, null), allowed(QuickAction.Alarm(19, 0, null), "alarm at 7pm"))
        assertEquals(QuickAction.Alarm(21, 0, null), allowed(QuickAction.Alarm(9, 0, null), "wake me at 9 tonight"))
        // The 24-hour number said outright stays as it is.
        assertEquals(QuickAction.Alarm(19, 0, null), allowed(QuickAction.Alarm(19, 0, null), "alarm at 19:00"))
        assertEquals(QuickAction.Alarm(12, 0, null), allowed(QuickAction.Alarm(12, 0, null), "alarm at noon"))
        assertEquals(QuickAction.Alarm(7, 0, null), allowed(QuickAction.Alarm(19, 0, null), "I am up at 7, set an alarm"))
        assertEquals(QuickAction.Alarm(7, 0, null), allowed(QuickAction.Alarm(19, 0, null), "alarm 7 a.m."))
    }

    @Test
    fun `7am rule - hour 19 becomes 7 unless pm, evening, tonight or night was said`() {
        for (said in listOf("set an alarm for 7 tomorrow", "wake me up tomorrow at 7", "alarm 7:00", "alarm for seven tomorrow")) {
            assertEquals(said, 7, (allowed(QuickAction.Alarm(19, 0, null), said) as QuickAction.Alarm).hour)
        }
        for (said in listOf("alarm at 7 pm", "alarm at 7 this evening", "alarm for 7 tonight", "alarm at 7 at night")) {
            assertEquals(said, 19, (allowed(QuickAction.Alarm(19, 0, null), said) as QuickAction.Alarm).hour)
        }
    }

    @Test
    fun `the dialer line reads out no digits`() {
        assertEquals("Opening the dialer.", QuickText.line(QuickAction.Dial("08001234567", null), java.time.LocalTime.NOON))
        assertTrue(QuickText.line(QuickAction.Dial("+2348012345678", null), java.time.LocalTime.NOON).none(Char::isDigit))
        assertEquals("Opening the dialer. Search for Mum there.", QuickText.line(QuickAction.Dial(null, "Mum"), java.time.LocalTime.NOON))
    }

    @Test
    fun `the line says the morning for 7 tomorrow`() {
        val allowedAlarm = allowed(QuickAction.Alarm(19, 0, null), "set an alarm for 7 tomorrow")!!
        assertEquals("Alarm set for 7 AM tomorrow.", QuickText.line(allowedAlarm, java.time.LocalTime.of(21, 0)))
    }

    @Test
    fun `an alarm's label is kept only if it was said`() {
        assertEquals("gym", (allowed(QuickAction.Alarm(6, 0, "gym"), "alarm at 6 for gym") as QuickAction.Alarm).message)
        assertNull((allowed(QuickAction.Alarm(6, 0, "Send 5 SOL"), "alarm at 6") as QuickAction.Alarm).message)
    }

    @Test
    fun `a timer needs the length the user said`() {
        assertTrue(allowed(QuickAction.Timer(300), "set a timer for 5 minutes") != null)
        assertTrue(allowed(QuickAction.Timer(300), "timer five minutes") != null)
        assertTrue(allowed(QuickAction.Timer(1800), "timer for half an hour") != null)
        assertTrue(allowed(QuickAction.Timer(4200), "a timer for an hour and 10 minutes") != null)
        assertTrue(allowed(QuickAction.Timer(5400), "timer for an hour and a half") != null)
        assertTrue(allowed(QuickAction.Timer(90), "90 seconds timer") != null)
        assertEquals("duration_not_said", refused(QuickAction.Timer(600), "set a timer for 5 minutes"))
        assertEquals("duration_not_said", refused(QuickAction.Timer(300), "set a timer"))
    }

    @Test
    fun `an app, a place or a contact has to be named by the user`() {
        assertTrue(allowed(QuickAction.OpenApp("Wallet"), "open the wallet") != null)
        assertEquals("app_not_said", refused(QuickAction.OpenApp("Phantom"), "open the wallet"))
        assertTrue(allowed(QuickAction.Navigate("Lekki Phase 1"), "directions to lekki phase 1") != null)
        assertEquals("place_not_said", refused(QuickAction.Navigate("Victoria Island"), "take me home"))
        assertTrue(allowed(QuickAction.Dial(null, "Mum"), "call mum") != null)
        assertEquals("name_not_said", refused(QuickAction.Dial(null, "Dad"), "call mum"))
    }

    @Test
    fun `a number has to be the digits the user said`() {
        assertEquals(QuickAction.Dial("08001234567", null), allowed(QuickAction.Dial("0800 123 4567", null), "call 0800 123 4567"))
        assertEquals(QuickAction.Dial("+2348012345678", null), allowed(QuickAction.Dial("+234 801 234 5678", "Tolu"), "dial +234 801 234 5678"))
        assertEquals("number_not_said", refused(QuickAction.Dial("0800 999 4567", null), "call 0800 123 4567"))
        assertEquals("number_not_said", refused(QuickAction.Dial("12", null), "call 12"))
    }

    @Test
    fun `a web page must be https and its site named`() {
        assertEquals("https://solana.com", (allowed(QuickAction.OpenUrl("solana.com"), "open solana.com") as QuickAction.OpenUrl).url)
        assertTrue(allowed(QuickAction.OpenUrl("https://www.jup.ag/swap"), "open jup dot ag") != null)
        assertEquals("not_https", refused(QuickAction.OpenUrl("http://solana.com"), "open http://solana.com"))
        assertEquals("not_https", refused(QuickAction.OpenUrl("javascript:alert(1)"), "open javascript"))
        assertEquals("url_not_said", refused(QuickAction.OpenUrl("https://drainer.xyz"), "open solana.com"))
    }

    @Test
    fun `a malformed action is nothing`() {
        assertNull(QuickAction.of(mapOf("type" to "intent", "intent" to "alarm", "hour" to 25)))
        assertNull(QuickAction.of(mapOf("type" to "intent", "intent" to "call", "number" to "123")))
        assertNull(QuickAction.of(mapOf("type" to "send", "intent" to "timer", "seconds" to 60)))
        assertNull(QuickAction.of(mapOf("type" to "intent", "intent" to "dial")))
        assertEquals(QuickAction.Alarm(7, 0, null), QuickAction.of(mapOf("type" to "intent", "intent" to "alarm", "hour" to 7)))
        assertEquals(QuickAction.Timer(300), QuickAction.of(mapOf("type" to "intent", "intent" to "timer", "seconds" to 300.0)))
    }
}

class QuickIntentsTest {

    @Test
    fun `alarm and timer go to the Clock with the time, and show it`() {
        val alarm = QuickIntents.spec(QuickAction.Alarm(7, 0, "gym"))
        assertEquals("android.intent.action.SET_ALARM", alarm.action)
        assertEquals(7, alarm.extras["android.intent.extra.alarm.HOUR"])
        assertEquals(0, alarm.extras["android.intent.extra.alarm.MINUTES"])
        assertEquals("gym", alarm.extras["android.intent.extra.alarm.MESSAGE"])
        assertEquals(false, alarm.extras["android.intent.extra.alarm.SKIP_UI"])
        val timer = QuickIntents.spec(QuickAction.Timer(300))
        assertEquals("android.intent.action.SET_TIMER", timer.action)
        assertEquals(300, timer.extras["android.intent.extra.alarm.LENGTH"])
    }

    @Test
    fun `dialing only ever fills in the dialer`() {
        val spec = QuickIntents.spec(QuickAction.Dial("08001234567", null))
        assertEquals("android.intent.action.DIAL", spec.action)
        assertEquals("tel:08001234567", spec.data)
        assertNull(QuickIntents.spec(QuickAction.Dial(null, "Mum")).data)
        for (action in listOf(QuickAction.Alarm(7, 0, null), QuickAction.Timer(1), QuickAction.OpenUrl("https://a.b"),
            QuickAction.Navigate("x"), QuickAction.Dial("123", null))) {
            assertTrue(QuickIntents.spec(action).action != "android.intent.action.CALL")
        }
    }

    @Test
    fun `web pages, places and apps`() {
        assertEquals(IntentSpec("android.intent.action.VIEW", data = "https://solana.com"), QuickIntents.spec(QuickAction.OpenUrl("https://solana.com")))
        assertEquals("geo:0,0?q=Lekki%20Phase%201", QuickIntents.spec(QuickAction.Navigate("Lekki Phase 1")).data)
        assertEquals("com.solanamobile.wallet", QuickIntents.spec(QuickAction.OpenApp("wallet"), "com.solanamobile.wallet").launchPackage)
    }

    @Test
    fun `one short line for each`() {
        val morning = java.time.LocalTime.of(9, 0)
        assertEquals("Alarm set for 7 AM tomorrow.", QuickText.line(QuickAction.Alarm(7, 0, null), morning))
        assertEquals("Alarm set for 7:30 PM today.", QuickText.line(QuickAction.Alarm(19, 30, null), morning))
        assertEquals("Timer set for 5 minutes.", QuickText.line(QuickAction.Timer(300), morning))
        assertEquals("Timer set for 1 hour 1 minute.", QuickText.line(QuickAction.Timer(3660), morning))
        assertEquals("Opening Wallet.", QuickText.line(QuickAction.OpenApp("the wallet"), morning, "Wallet"))
        assertEquals("Opening solana.com.", QuickText.line(QuickAction.OpenUrl("https://www.solana.com/x"), morning))
    }

    @Test
    fun `the log names an action without what could identify someone`() {
        assertEquals("intent=dial digits=11 name=false", QuickLog.describe(QuickAction.Dial("08001234567", null)))
        assertEquals("intent=open_url host=solana.com", QuickLog.describe(QuickAction.OpenUrl("https://solana.com/path?me")))
    }
}

class AppMatcherTest {

    private val apps = listOf(
        LauncherApp("Seed Vault Wallet", "com.solanamobile.wallet"),
        LauncherApp("Jupiter", "ag.jup.jupiter.android"),
        LauncherApp("Chrome", "com.android.chrome"),
        LauncherApp("dApp Store", "com.solanamobile.dappstore"),
        LauncherApp("Clock", "com.google.android.deskclock")
    )

    private fun found(query: String, list: List<LauncherApp> = apps) =
        (AppMatcher.best(query, list) as? AppMatcher.Match.Found)?.app?.packageName

    @Test
    fun `a word of the name, the whole name, or a near spelling finds the app`() {
        assertEquals("com.solanamobile.wallet", found("the wallet"))
        assertEquals("ag.jup.jupiter.android", found("Jupiter"))
        assertEquals("ag.jup.jupiter.android", found("jupitor"))
        assertEquals("com.solanamobile.dappstore", found("dapp store"))
        assertEquals("com.android.chrome", found("chrome app"))
    }

    @Test
    fun `the exact name beats a longer one`() {
        val list = apps + LauncherApp("Wallet", "rsv.walletapp.reserve")
        assertEquals("rsv.walletapp.reserve", found("wallet", list))
    }

    @Test
    fun `two equally good matches ask which one, and nothing close is none`() {
        val list = apps + LauncherApp("Phantom Wallet", "app.phantom")
        assertTrue(AppMatcher.best("wallet", list) is AppMatcher.Match.Ambiguous)
        assertEquals(AppMatcher.Match.None, AppMatcher.best("spotify", apps))
    }
}


class QuickActionClassifierTest {

    @Test
    fun `the four smoke questions are quick actions`() {
        for (q in listOf("set an alarm for 7 tomorrow", "set a timer for 5 minutes", "open the wallet", "call 0800 123 4567")) {
            assertTrue(q, QuickActions.isQuickAction(q))
        }
    }

    @Test
    fun `polite and spoken forms are too`() {
        for (q in listOf("can you wake me at 6", "please open Jupiter", "Could you launch chrome", "timer for 90 seconds",
            "directions to Lekki Phase 1", "how do I get to the airport", "dial 112", "Hey Heylana, open solana.com")) {
            assertTrue(q, QuickActions.isQuickAction(q))
        }
    }

    @Test
    fun `questions about the screen are not`() {
        for (q in listOf("how do I open settings in this app", "what does the call button do", "is this timer running",
            "what is on this screen", "where is the open button", "does this app have an alarm", "send 0.05 USDC to bob.skr")) {
            assertTrue(q, !QuickActions.isQuickAction(q))
        }
    }

    @Test
    fun `a quick action routes on its own - quick model, no Solana, no tools, no greeting`() {
        val route = xyz.heylana.app.brain.Routing.forQuestion("com.android.launcher3", "open the wallet")
        assertEquals(xyz.heylana.app.brain.Routing.Why.QUICK_ACTION, route.why)
        assertEquals(xyz.heylana.app.brain.ProxyClient.MODE_QUICK, route.mode)
        assertNull(route.solana)
        assertTrue(!route.toolsWanted && !route.allowsGreeting)
        // A send still wins, and a signing screen still comes first.
        assertEquals(xyz.heylana.app.brain.Routing.Why.SEND_QUESTION, xyz.heylana.app.brain.Routing.forQuestion(null, "send 1 SOL to bob.skr and call him").why)
    }
}
