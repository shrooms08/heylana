package xyz.heylana.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.settings.VoiceCopy
import xyz.heylana.app.ui.app.AccentButton
import xyz.heylana.app.ui.app.FlatField
import xyz.heylana.app.ui.app.FlatPage
import xyz.heylana.app.ui.app.FlatRow
import xyz.heylana.app.ui.app.FlatSurface
import xyz.heylana.app.ui.app.Glyph
import xyz.heylana.app.ui.app.Icon
import xyz.heylana.app.ui.app.InnerTopBar
import xyz.heylana.app.ui.app.SectionHead
import xyz.heylana.app.ui.app.tap
import xyz.heylana.app.ui.theme.HeylanaType
import xyz.heylana.app.ui.theme.LocalHeylana

/** A provider on the Advanced screen. Only [wired] ones can be picked. */
data class Provider(val id: String, val name: String, val detail: String, val letter: String, val wired: Boolean)

/** Every word on the Advanced screen, away from Compose so it can be tested. */
object AdvancedText {
    const val TITLE = "Use my own key"
    const val SUBTITLE = "Bring your own provider and pay them directly."
    const val NOTE = "Kept encrypted in the phone's keystore. It goes to Heylana's server with each question, is used for that question only, and is never stored or logged there."
    const val SOON = "soon"
    const val SAVED = "Saved. Questions now go on your own key."
    const val EMPTY = "Paste a key first."
    const val STOPPED = "Stopped. Questions go through Heylana again."
    const val IN_USE = "Your key is in use for questions. The voice and the ears still go through Heylana."
    const val STOP = "Stop using my key"

    /** Anthropic is wired; the other two are shown, and marked soon. */
    val PROVIDERS = listOf(
        Provider("anthropic", "Anthropic", "Claude · default", "A", wired = true),
        Provider("openai", "OpenAI", "GPT models", "O", wired = false),
        Provider("gemini", "Gemini", "Google models", "G", wired = false)
    )
}

/** Frame 5 of the export: three providers, the key, and where it is kept. */
@Composable
fun AdvancedScreen(settings: HeylanaSettings, onBack: () -> Unit) {
    val palette = LocalHeylana.current
    var key by remember { mutableStateOf("") }
    var masked by remember { mutableStateOf(settings.maskedApiKey()) }
    var inUse by remember { mutableStateOf(settings.useOwnKey && settings.hasApiKey) }
    var line by remember { mutableStateOf("") }

    FlatPage {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Box(Modifier.padding(vertical = 14.dp)) { InnerTopBar(onBack) }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(10.dp))
                Text(AdvancedText.TITLE, style = HeylanaType.title, color = palette.ink)
                Text(AdvancedText.SUBTITLE, style = HeylanaType.bodyLight, color = palette.inkSecondary)
                Spacer(Modifier.height(18.dp))
                SectionHead("Provider")
                AdvancedText.PROVIDERS.forEach { provider ->
                    FlatRow(provider.name,
                        subtitle = provider.detail,
                        letter = provider.letter,
                        selected = provider.wired,
                        enabled = provider.wired
                    ) {
                        if (provider.wired) {
                            Box(Modifier.size(24.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                                Icon(Glyph.CHECK, palette.onAccent, size = 14.dp)
                            }
                        } else {
                            Box(
                                Modifier.clip(RoundedCornerShape(999.dp)).background(palette.surfaceHigh)
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            ) { Text(AdvancedText.SOON, style = HeylanaType.tiny, color = palette.inkSecondary) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                SectionHead("API key")
                FlatSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FlatField(
                            key, { key = it },
                            placeholder = masked ?: "sk-ant-…",
                            style = HeylanaType.body,
                            secret = true
                        )
                        AccentButton("Save key", {
                            val typed = key.trim()
                            if (typed.isEmpty()) {
                                line = AdvancedText.EMPTY
                            } else {
                                settings.apiKey = typed
                                settings.useOwnKey = true
                                key = ""
                                masked = settings.maskedApiKey()
                                inUse = true
                                line = AdvancedText.SAVED
                                // Whether, never what.
                                HeylanaLog.state("app: own key saved")
                            }
                        }, height = 52.dp)
                    }
                }
                if (inUse) {
                    Text(AdvancedText.IN_USE, style = HeylanaType.small, color = palette.inkSecondary)
                    Text(AdvancedText.STOP, Modifier.tap {
                        settings.useOwnKey = false
                        inUse = false
                        line = AdvancedText.STOPPED
                        HeylanaLog.state("app: own key switched off")
                    }, style = HeylanaType.label, color = palette.accentText)
                }
                if (line.isNotEmpty()) Text(line, style = HeylanaType.small, color = palette.inkSecondary)
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Glyph.LOCK, palette.inkTertiary, size = 16.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(AdvancedText.NOTE, style = HeylanaType.small, color = palette.inkTertiary)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** What leaves the phone and where it goes: PRODUCT.md's list, in the app's own words. */
object PrivacyCopy {
    const val TITLE = "Privacy"
    const val LEAD = "Nothing is read unless you ask — with one exception, below."
    const val PIXELS = "Heylana never captures pixels: no screenshots and no recordings. When you ask, " +
        "the screen is turned into a short text list of the labels on it, used for that one answer and dropped."
    const val WATCHING = "The one exception: while \"Watch signing screens\" is on (Settings), the buddy " +
        "notices a new screen coming up and reads it on the phone, to see whether it is a wallet asking for a " +
        "signature or a page asking for your recovery phrase. That read never leaves the phone and is never " +
        "sent anywhere — it is used for the one line it shows you, and dropped. Switch it off and nothing is " +
        "read unless you ask."
    const val BLOCKLIST = "A scam watchlist built from ScamSniffer's feed and Phantom's blocklist (Phantom's part is a " +
        "snapshot, no longer updated) is downloaded to your phone once a day and checked here. No web address you " +
        "visit is ever sent to Heylana or to anyone else."
    const val SIGN = "Heylana prepares, you sign. Heylana never signs or sends anything itself."

    /** The honest caveat: a wallet told to trust Heylana signs without asking. */
    const val TRUSTED = "Every send needs your approval in Seed Vault, unless you marked Heylana as trusted there. We recommend you don't."

    data class Item(val title: String, val detail: String)

    /** Where the voice goes: Deepgram, and AssemblyAI when it is listening too — named only then. */
    fun voiceDetail(assemblyai: Boolean): String =
        if (assemblyai) {
            "To Deepgram and AssemblyAI, to be written down. The phone connects to each directly with a pass that " +
                "stops working within two minutes (AssemblyAI's opens one session only). Both are sent a fixed list " +
                "of Solana words to listen for; never anything from your screen."
        } else {
            "To Deepgram, to be written down. The phone connects to Deepgram directly with a key that stops " +
                "working after two minutes. Deepgram is also sent a fixed list of Solana words to listen for; " +
                "never anything from your screen."
        }

    /** The seven things that leave the phone, with the voice provider the worker speaks through. */
    fun items(provider: String, assemblyai: Boolean = false): List<Item> = listOf(
        Item(
            "Your question and a text list of the screen",
            "To Heylana's server (a Cloudflare Worker), which passes it to Anthropic to write the answer. " +
                "With it go up to your last three questions and answers from the same app (at most 600 characters, " +
                "forgotten after ten minutes or when the buddy stops) and, on the first answer after the buddy starts, " +
                "the name you asked to be called. Questions asked here in the app carry no screen."
        ),
        Item("Your voice, only while you hold the buddy or the mic", voiceDetail(assemblyai)),
        Item("The text of the spoken answer", "${speechSentence(provider)} ${VoiceCopy.LIVE_SENTENCE}"),
        Item(
            "Your wallet address, a signed sign-in message, and the name you choose",
            "To Heylana's server, kept against your wallet."
        ),
        Item(
            "Payments",
            "Seed Vault signs and sends the transaction itself; Heylana's server reads it from the Solana chain to check it."
        ),
        Item("A random install id", "To Heylana's server, to count the daily budget."),
        Item(
            "Memory, only if you turn it on",
            "To Heylana's server, kept against your wallet: short notes you asked it to keep, things you say about " +
                "yourself (\"I use Jupiter for swaps\"; a Remembered chip shows, and \"forget that\" takes it back), " +
                "preferences you said yes to, and lessons you finished, at most 60 lines. Never what's on your screen, never an address or " +
                "an amount. Up to 12 go with your questions, never with quick actions. See, delete or wipe them in " +
                "Menu, Memory; off keeps nothing."
        ),
        Item(
            "Solana lookups, only for Solana questions",
            "Heylana's server looks things up before answering: your connected wallet's address, and any address " +
                "or .skr/.sol name in your question or on a signing screen, go to the Solana RPC provider (Helius); " +
                "token prices come from Jupiter; .sol names from Bonfida's public resolver. Nothing else from the " +
                "screen goes to them. For how Solana works and for errors, Heylana searches its own library of public " +
                "docs, Solana Stack Exchange answers (credited to their authors) and release notes: the search words go " +
                "to Cloudflare Workers AI, and nothing about you is ever added to the library."
        )
    )

    /** Where the spoken answer text goes, naming the provider in use. */
    fun speechSentence(provider: String): String = when (provider) {
        VoiceCopy.DEEPGRAM -> "Through Heylana's server to Deepgram (Aura), to become speech."
        VoiceCopy.CARTESIA -> "Through Heylana's server to Cartesia, to become speech."
        else -> "Through Heylana's server to Google (Gemini), to become speech."
    }
}

/** The privacy screen: the pixels line, then the list. */
@Composable
fun PrivacyScreen(provider: String, onBack: () -> Unit, assemblyai: Boolean = false) {
    val palette = LocalHeylana.current
    FlatPage {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.padding(vertical = 14.dp)) { InnerTopBar(onBack) }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(10.dp))
                Text(PrivacyCopy.TITLE, style = HeylanaType.title, color = palette.ink)
                Text(PrivacyCopy.LEAD, style = HeylanaType.bodyLight, color = palette.inkSecondary)
                Spacer(Modifier.height(10.dp))
                FlatRow("No pixels, ever", subtitle = PrivacyCopy.PIXELS, glyph = Glyph.EYE, selected = true)
                FlatRow("Watching signing screens", subtitle = PrivacyCopy.WATCHING, glyph = Glyph.SHIELD)
                FlatRow("The scam watchlist", subtitle = PrivacyCopy.BLOCKLIST, glyph = Glyph.DOC)
                Spacer(Modifier.height(6.dp))
                SectionHead("What leaves the phone")
                PrivacyCopy.items(provider, assemblyai).forEachIndexed { i, item ->
                    FlatRow(item.title, subtitle = item.detail, letter = "${i + 1}")
                }
                Spacer(Modifier.height(6.dp))
                FlatRow("You sign", subtitle = PrivacyCopy.SIGN, glyph = Glyph.LOCK)
                FlatRow("Approve each send", subtitle = PrivacyCopy.TRUSTED, glyph = Glyph.SHIELD)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
