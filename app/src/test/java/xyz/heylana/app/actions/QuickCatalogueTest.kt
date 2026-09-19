package xyz.heylana.app.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.brain.Routing
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** The catalogue added in phase 3d: each action's words, guard, intent and line. */
class QuickCatalogueTest {

    private val noon = LocalTime.NOON

    private fun allowed(action: QuickAction, said: String): QuickAction {
        val verdict = QuickGuard.check(action, said)
        assertTrue("$action refused for \"$said\": $verdict", verdict is QuickGuard.Verdict.Allowed)
        return (verdict as QuickGuard.Verdict.Allowed).action
    }

    private fun refused(action: QuickAction, said: String) =
        assertTrue("$action allowed for \"$said\"", QuickGuard.check(action, said) is QuickGuard.Verdict.Refused)

    private fun launch(action: QuickAction, now: LocalDateTime = LocalDateTime.of(2026, 9, 17, 12, 0)): IntentSpec =
        (QuickIntents.effect(action, now = now, zone = ZoneOffset.UTC) as QuickEffect.Launch).intent

    private fun of(vararg pairs: Pair<String, Any?>) = QuickAction.of(mapOf("type" to "intent", *pairs))

    @Test
    fun `the smoke sentences are quick actions`() {
        for (q in listOf(
            "search skateboarding videos on youtube", "find a video about sourdough", "play Burna Boy on Spotify",
            "play some afrobeats", "pause the music", "next song", "text 0800 123 4567 I'm on my way",
            "text Ada I'm on my way", "remind me to call mum at 6", "turn on the flashlight", "turn off the torch",
            "open the camera", "take a selfie", "search for the SKR price chart", "open wifi settings",
            "open bluetooth settings", "send a text to Ada saying hi"
        )) {
            assertTrue(q, QuickActions.isQuickAction(q))
        }
        for (q in listOf("how do I search on youtube", "what does the play button do", "what is a selfie stick")) {
            assertFalse(q, QuickActions.isQuickAction(q))
        }
    }

    @Test
    fun `a text message is a quick action, never a Solana send`() {
        assertEquals(Routing.Why.QUICK_ACTION, Routing.forQuestion(null, "send a text to Ada saying I'm late").why)
        assertEquals(Routing.Why.SEND_QUESTION, Routing.forQuestion(null, "send 1 SOL to bob.skr").why)
    }

    @Test
    fun `youtube search - the results page in the app, the browser as the fallback`() {
        val action = allowed(of("intent" to "youtube_search", "query" to "skateboarding videos")!!, "search skateboarding videos on youtube")
        val spec = launch(action)
        // The results page itself: ACTION_SEARCH opened YouTube without searching.
        assertEquals("android.intent.action.VIEW", spec.action)
        assertEquals("com.google.android.youtube", spec.targetPackage)
        assertEquals("https://www.youtube.com/results?search_query=skateboarding+videos", spec.data)
        assertEquals(spec.data, spec.fallback?.data)
        assertEquals(null, spec.fallback?.targetPackage)
        assertEquals("Searching YouTube for skateboarding videos.", QuickText.line(action, noon))
        refused(QuickAction.YoutubeSearch("cats"), "search skateboarding videos on youtube")
    }

    @Test
    fun `spotify play - play from search, only Spotify, and says so when it is missing`() {
        val action = allowed(of("intent" to "spotify_play", "query" to "Burna Boy")!!, "play burna boy on spotify")
        val spec = launch(action)
        assertEquals("android.media.action.MEDIA_PLAY_FROM_SEARCH", spec.action)
        assertEquals("com.spotify.music", spec.targetPackage)
        assertEquals("Burna Boy", spec.extras["query"])
        assertEquals("vnd.android.cursor.item/*", spec.extras["android.intent.extra.focus"])
        assertNull(spec.fallback)
        // Spotify's intents land on the search; it does not start playing, so the line says so.
        assertEquals("Opened Spotify for Burna Boy. Tap play.", QuickText.line(action, noon))
        assertEquals("Spotify isn't installed on this phone.", QuickText.missingApp(action))
        refused(QuickAction.SpotifyPlay("Wizkid"), "play burna boy on spotify")
    }

    @Test
    fun `media control - a key for each command, said as a word`() {
        val keys = mapOf("play" to 126, "pause" to 127, "next" to 87, "previous" to 88)
        keys.forEach { (command, code) ->
            assertEquals(QuickEffect.MediaKey(code), QuickIntents.effect(QuickAction.MediaControl(command)))
        }
        allowed(of("intent" to "media_control", "command" to "pause")!!, "pause the music")
        allowed(QuickAction.MediaControl("next"), "next song")
        allowed(QuickAction.MediaControl("next"), "skip this track")
        refused(QuickAction.MediaControl("next"), "pause the music")
        assertNull(of("intent" to "media_control", "command" to "louder"))
        assertEquals("Paused.", QuickText.line(QuickAction.MediaControl("pause"), noon))
        assertEquals("Next song.", QuickText.line(QuickAction.MediaControl("next"), noon))
    }

    @Test
    fun `message - the compose screen with the text, never sent, the number and words as said`() {
        val action = allowed(
            of("intent" to "message", "number" to "0800 123 4567", "text" to "I'm on my way")!!,
            "text 0800 123 4567 i'm on my way"
        )
        val spec = launch(action)
        assertEquals("android.intent.action.SENDTO", spec.action)
        assertEquals("smsto:08001234567", spec.data)
        assertEquals("I'm on my way", spec.extras["sms_body"])
        val line = QuickText.line(action, noon)
        assertEquals("Your message is ready. Check it and tap send.", line)
        assertTrue(line.none(Char::isDigit))
        val byName = allowed(QuickAction.Message(null, "Ada", "I'm on my way"), "text ada i'm on my way")
        assertEquals("smsto:", launch(byName).data)
        assertEquals("Your message is ready. Pick Ada and tap send.", QuickText.line(byName, noon))
        // The model spells contractions out; the user did not.
        allowed(QuickAction.Message("0800 123 4567", null, "I am on my way"), "text 0800 123 4567 i’m on my way")
        allowed(QuickAction.Message(null, "Ada", "I'm on my way"), "text ada i am on my way")
        refused(QuickAction.Message("08009999999", null, "I'm on my way"), "text 0800 123 4567 i'm on my way")
        refused(QuickAction.Message(null, "Ada", "send me your seed phrase"), "text ada i'm on my way")
        assertNull(of("intent" to "message", "number" to "0800"))
        // The recipient sometimes comes back as "to" instead.
        assertEquals(
            QuickAction.Message("0800 123 4567", null, "I am on my way"),
            of("intent" to "message", "to" to "0800 123 4567", "text" to "I am on my way")
        )
        assertEquals(
            QuickAction.Message(null, "Ada", "hi"),
            of("intent" to "message", "to" to "Ada", "text" to "hi")
        )
        // Punctuation the user did not say must not refuse the message.
        allowed(QuickAction.Message(null, "Ada", "I'm on my way!"), "text ada i am on my way")
        // Where no messaging app takes smsto:, sms: and a plain share follow.
        val chain = launch(QuickAction.Message("08001234567", null, "hi"))
        assertEquals("sms:08001234567", chain.fallback?.data)
        assertEquals("android.intent.action.SEND", chain.fallback?.fallback?.action)
        assertEquals("text/plain", chain.fallback?.fallback?.type)
    }

    @Test
    fun `reminder - the calendar's new event, filled in, at the time said`() {
        val action = allowed(of("intent" to "reminder", "text" to "call mum", "hour" to 6)!!, "remind me to call mum at 6")
        action as QuickAction.Reminder
        assertTrue(action.bareHour)
        val spec = launch(action, now = LocalDateTime.of(2026, 9, 17, 12, 0))
        assertEquals("android.intent.action.INSERT", spec.action)
        assertEquals("content://com.android.calendar/events", spec.data)
        assertEquals("call mum", spec.extras["title"])
        val begin = LocalDateTime.of(2026, 9, 17, 18, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(begin, spec.extras["beginTime"])
        assertEquals(begin + 30 * 60_000L, spec.extras["endTime"])
        assertEquals("Reminder for 6 PM today. Check it and tap save.", QuickText.line(action, noon))
        assertEquals(
            "Reminder saved for 6 PM today.",
            QuickText.reminderSavedLine(LocalDateTime.of(2026, 9, 17, 18, 0), java.time.LocalDate.of(2026, 9, 17))
        )
        refused(QuickAction.Reminder("call mum", 7, 0), "remind me to call mum at 6")
        refused(QuickAction.Reminder("pay rent", 6, 0), "remind me to call mum at 6")
    }

    @Test
    fun `a reminder's bare hour is the next one to come, and a said half of the day stands`() {
        fun start(said: String, hour: Int, now: LocalDateTime): LocalDateTime =
            QuickIntents.reminderStart(allowed(QuickAction.Reminder("call mum", hour, 0), said) as QuickAction.Reminder, now)
        val morning = LocalDateTime.of(2026, 9, 17, 5, 0)
        val evening = LocalDateTime.of(2026, 9, 17, 20, 0)
        assertEquals(LocalDateTime.of(2026, 9, 17, 6, 0), start("remind me to call mum at 6", 6, morning))
        assertEquals(LocalDateTime.of(2026, 9, 18, 6, 0), start("remind me to call mum at 6", 18, evening))
        assertEquals(LocalDateTime.of(2026, 9, 18, 18, 0), start("remind me to call mum tomorrow at 6", 6, morning))
        assertEquals(LocalDateTime.of(2026, 9, 18, 9, 0), start("remind me to call mum tomorrow at 9", 9, morning))
        assertEquals(LocalDateTime.of(2026, 9, 17, 18, 0), start("remind me to call mum at 6pm", 6, morning))
        assertEquals(LocalDateTime.of(2026, 9, 18, 6, 0), start("remind me to call mum at 6 in the morning", 6, evening))
    }

    @Test
    fun `flashlight - the torch, on or off as said, and the pronoun follow-up`() {
        assertEquals(QuickEffect.Torch(true), QuickIntents.effect(allowed(of("intent" to "flashlight", "state" to "on")!!, "turn on the flashlight")))
        assertEquals(QuickEffect.Torch(false), QuickIntents.effect(allowed(QuickAction.Flashlight(false), "turn it off")))
        refused(QuickAction.Flashlight(true), "turn off the flashlight")
        assertEquals("Flashlight on.", QuickText.line(QuickAction.Flashlight(true), noon))
        assertEquals(QuickAction.Flashlight(false), QuickActions.flashlightFollowUp("turn it off"))
        assertEquals(QuickAction.Flashlight(true), QuickActions.flashlightFollowUp("switch it on please"))
        assertNull(QuickActions.flashlightFollowUp("turn off wifi"))
    }

    @Test
    fun `camera and selfie - the still camera, front-facing extras for a selfie`() {
        val camera = launch(allowed(of("intent" to "camera")!!, "open the camera"))
        assertEquals("android.media.action.STILL_IMAGE_CAMERA", camera.action)
        assertTrue(camera.extras.isEmpty())
        val selfie = launch(allowed(of("intent" to "selfie")!!, "take a selfie"))
        assertEquals(1, selfie.extras["android.intent.extras.CAMERA_FACING"])
        assertEquals(true, selfie.extras["android.intent.extra.USE_FRONT_CAMERA"])
        refused(QuickAction.Camera(selfie = true), "open the camera")
        assertEquals("Opening the camera for a selfie.", QuickText.line(QuickAction.Camera(true), noon))
        assertEquals("There's no camera app on this phone.", QuickText.missingApp(QuickAction.Camera(false)))
    }

    @Test
    fun `web search - the search page in the browser, with no chooser`() {
        val action = allowed(of("intent" to "web_search", "query" to "SKR price chart")!!, "search for the skr price chart")
        val spec = launch(action)
        assertEquals("android.intent.action.VIEW", spec.action)
        assertEquals("https://www.google.com/search?q=SKR+price+chart", spec.data)
        assertEquals("Searching the web for SKR price chart.", QuickText.line(action, noon))
    }

    @Test
    fun `settings - each page's own screen, named as said`() {
        val expected = mapOf(
            "wifi" to "android.settings.WIFI_SETTINGS", "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
            "display" to "android.settings.DISPLAY_SETTINGS", "sound" to "android.settings.SOUND_SETTINGS",
            "battery" to "android.intent.action.POWER_USAGE_SUMMARY", "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS"
        )
        expected.forEach { (page, action) -> assertEquals(action, launch(QuickAction.OpenSettings(page)).action) }
        allowed(of("intent" to "settings", "page" to "Wi-Fi")!!, "open wifi settings")
        allowed(QuickAction.OpenSettings("wifi"), "open wi-fi settings")
        refused(QuickAction.OpenSettings("bluetooth"), "open wifi settings")
        assertNull(of("intent" to "settings", "page" to "developer"))
        assertEquals("Opening Wi-Fi settings.", QuickText.line(QuickAction.OpenSettings("wifi"), noon))
    }

    @Test
    fun `the log never carries a query, a number, a name or a message`() {
        val lines = listOf(
            QuickLog.describe(QuickAction.YoutubeSearch("skateboarding")),
            QuickLog.describe(QuickAction.SpotifyPlay("Burna Boy")),
            QuickLog.describe(QuickAction.Message("08001234567", null, "on my way")),
            QuickLog.describe(QuickAction.Message(null, "Ada", "on my way")),
            QuickLog.describe(QuickAction.Reminder("call mum", 18, 0)),
            QuickLog.describe(QuickAction.WebSearch("SKR price"))
        )
        for (line in lines) {
            for (secret in listOf("skate", "Burna", "0800", "Ada", "way", "mum", "SKR")) assertFalse(line, line.contains(secret))
        }
        assertEquals("intent=settings page=wifi", QuickLog.describe(QuickAction.OpenSettings("wifi")))
        assertEquals("intent=media_control command=pause", QuickLog.describe(QuickAction.MediaControl("pause")))
    }

    @Test
    fun `flashlight said outright needs no model - the state is in the words`() {
        for ((said, on) in listOf(
            "turn on the flashlight" to true, "Turn the flashlight off" to false, "flashlight on" to true,
            "torch off" to false, "switch off the torch please" to false, "can you turn on my flashlight?" to true,
            "flash light on" to true
        )) {
            assertEquals(said, QuickAction.Flashlight(on), QuickActions.flashlightCommand(said))
        }
        for (not in listOf("flashlight", "turn the flashlight on and off", "is the flashlight on", "how do I turn on the flashlight",
            "turn on the flashlight in 5 minutes", "turn it off")) {
            assertEquals(not, null, QuickActions.flashlightCommand(not))
        }
    }
}
