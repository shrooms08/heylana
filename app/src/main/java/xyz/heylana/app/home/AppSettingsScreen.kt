package xyz.heylana.app.home

import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.settings.DEBUG_STATES_ACTIVITY
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.ui.GlassSpec
import xyz.heylana.app.ui.app.AccentButton
import xyz.heylana.app.ui.app.FlatField
import xyz.heylana.app.ui.app.FlatPage
import xyz.heylana.app.ui.app.FlatRow
import xyz.heylana.app.ui.app.FlatSurface
import xyz.heylana.app.ui.app.FlatSwitch
import xyz.heylana.app.ui.app.HoldControl
import xyz.heylana.app.ui.app.Glyph
import xyz.heylana.app.ui.app.Icon
import xyz.heylana.app.ui.app.InnerTopBar
import xyz.heylana.app.ui.app.SectionHead
import xyz.heylana.app.ui.theme.GlassMode
import xyz.heylana.app.ui.theme.HeylanaType
import xyz.heylana.app.ui.theme.LocalHeylana
import xyz.heylana.app.wallet.Answer
import xyz.heylana.app.wallet.PlanText
import xyz.heylana.app.wallet.Standing
import xyz.heylana.app.wallet.WalletApi
import xyz.heylana.app.wallet.WalletProblem

/** Every word on the Settings screen that is not a switch's own, away from Compose. */
object SettingsText {
    const val SKYLAR_DETAIL = "Warm and clear. Heylana's own voice."
    const val ARCHIE_DETAIL = "Warm and friendly."
    const val SAMPLE_LINE = "Hi, I'm Heylana."
    const val DARK = "Dark"
    const val DARK_DETAIL = "Flat and black, as designed."
    const val LIGHT = "Light"
    const val LIGHT_DETAIL = "The same screens on white."
    const val SPOKEN_TEXT = "Show spoken answers as text"
    const val SPOKEN_TEXT_DETAIL = "When you ask by holding the buddy, the words stay on screen too."
    const val DARKER = "Darker buddy glass"
    const val DARKER_DETAIL = "Adds a dark tint under the buddy's glass, for light apps."
    const val PERMISSIONS = "Permissions"
    const val PERMISSIONS_DETAIL = "Over other apps, screen reading, notifications, microphone."
    const val JUDGE_HINT = "Judging Heylana? Enter your code."
    const val BAD_CODE = "That code isn't right."
    const val STOP_BUDDY = "Stop buddy"
    const val STOP_DETAIL_ON = "Hold to stop. The disc leaves the screen until you start it again."
    const val STOP_DETAIL_OFF = "The buddy isn't running."

    const val ABOUT = "About"
    /** The same facts as the system prompt's identity line (`HeylanaPrompt.IDENTITY`). */
    const val ABOUT_DETAIL = "Made by Minos, an independent developer in Lagos, for the " +
        "Solana Seeker. Not made by Solana Mobile or Solana Labs, though Heylana hopes to be adopted by the Seeker " +
        "and become part of it."

    fun version(name: String, code: Int): String = "Heylana $name ($code)"
}

/**
 * Settings: the voice, the appearance, how the buddy behaves, a judge code, Stop buddy and the
 * version. Debug builds add their switches and Debug states.
 */
@Composable
fun AppSettingsScreen(
    settings: HeylanaSettings,
    buddyOn: Boolean,
    onGlassMode: (GlassMode) -> Unit,
    onSample: (String) -> Unit,
    onStanding: (Standing) -> Unit,
    onStopBuddy: () -> Unit,
    onScreen: (Screen) -> Unit,
    onBack: () -> Unit
) {
    val palette = LocalHeylana.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var voice by remember { mutableStateOf(settings.voice) }
    var spokenText by remember { mutableStateOf(settings.showTextForVoice) }
    var darker by remember { mutableStateOf(settings.darkerGlass) }
    var code by remember { mutableStateOf("") }
    var codeLine by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    FlatPage {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Box(Modifier.padding(vertical = 14.dp)) { InnerTopBar(onBack) }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(10.dp))
                Text("Settings", style = HeylanaType.title, color = palette.ink)
                Spacer(Modifier.height(10.dp))

                SectionHead("Voice")
                listOf(
                    HeylanaSettings.VOICE_SKYLAR to SettingsText.SKYLAR_DETAIL,
                    HeylanaSettings.VOICE_ARCHIE to SettingsText.ARCHIE_DETAIL
                ).forEach { (slot, detail) ->
                    val name = settings.voiceName(slot)
                    FlatRow(name, subtitle = detail, letter = name.take(1), selected = voice == slot,
                        onClick = {
                            voice = slot
                            settings.voice = slot
                            onSample(SettingsText.SAMPLE_LINE)
                        }
                    ) { if (voice == slot) Tick() }
                }

                Spacer(Modifier.height(6.dp))
                SectionHead("Appearance")
                val mode = palette.mode
                FlatRow(SettingsText.DARK, subtitle = SettingsText.DARK_DETAIL, glyph = Glyph.LAYERS,
                    selected = mode == GlassMode.DARK, onClick = { onGlassMode(GlassMode.DARK) }) { if (mode == GlassMode.DARK) Tick() }
                FlatRow(SettingsText.LIGHT, subtitle = SettingsText.LIGHT_DETAIL, glyph = Glyph.LAYERS,
                    selected = mode == GlassMode.LIGHT, onClick = { onGlassMode(GlassMode.LIGHT) }) { if (mode == GlassMode.LIGHT) Tick() }

                Spacer(Modifier.height(6.dp))
                SectionHead("Buddy")
                FlatRow(SettingsText.SPOKEN_TEXT, subtitle = SettingsText.SPOKEN_TEXT_DETAIL) {
                    FlatSwitch(spokenText, {
                        spokenText = it
                        settings.showTextForVoice = it
                    })
                }
                FlatRow(SettingsText.DARKER, subtitle = SettingsText.DARKER_DETAIL) {
                    FlatSwitch(darker, {
                        darker = it
                        settings.darkerGlass = it
                        GlassSpec.darkerGlass = it
                    })
                }
                FlatRow(SettingsText.PERMISSIONS, subtitle = SettingsText.PERMISSIONS_DETAIL, glyph = Glyph.SHIELD,
                    onClick = { onScreen(Screen.PERMISSIONS) }) { Icon(Glyph.CHEVRON, palette.inkTertiary, size = 16.dp) }

                Spacer(Modifier.height(6.dp))
                SectionHead("Judge code")
                FlatSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(SettingsText.JUDGE_HINT, style = HeylanaType.small, color = palette.inkSecondary)
                        FlatField(code, { code = it }, placeholder = "Code")
                        AccentButton(if (busy) "Checking…" else "Use code", {
                            busy = true
                            codeLine = ""
                            scope.launch {
                                codeLine = when (val answer = WalletApi(settings).judge(code.trim())) {
                                    is Answer.Ok -> {
                                        onStanding(answer.value)
                                        code = ""
                                        PlanText.until(answer.value) ?: ""
                                    }
                                    is Answer.Refused ->
                                        if (answer.reason == "bad_code") SettingsText.BAD_CODE
                                        else WalletProblem.fromWorker(answer.reason).words
                                    is Answer.Unreachable -> WalletProblem.UNREACHABLE.words
                                }
                                busy = false
                            }
                        }, enabled = !busy && code.isNotBlank(), height = 48.dp)
                        if (codeLine.isNotEmpty()) Text(codeLine, style = HeylanaType.small, color = palette.inkSecondary)
                    }
                }

                Spacer(Modifier.height(6.dp))
                FlatRow(SettingsText.STOP_BUDDY,
                    subtitle = if (buddyOn) SettingsText.STOP_DETAIL_ON else SettingsText.STOP_DETAIL_OFF
                ) { HoldControl(Glyph.POWER, buddyOn, SettingsText.STOP_BUDDY, onFire = onStopBuddy, enabled = buddyOn) }

                if (BuildConfig.DEBUG) DebugRows(settings) {
                    context.startActivity(Intent().setComponent(ComponentName(context, DEBUG_STATES_ACTIVITY)))
                }

                Spacer(Modifier.height(6.dp))
                FlatRow(SettingsText.ABOUT, subtitle = SettingsText.ABOUT_DETAIL, glyph = Glyph.STAR)

                Spacer(Modifier.height(12.dp))
                VersionLine()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Tick() {
    val palette = LocalHeylana.current
    Box(Modifier.size(24.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
        Icon(Glyph.CHECK, palette.onAccent, size = 14.dp)
    }
}

/** Debug builds only: the switches that make a fallback happen on purpose, and Debug states. */
@Composable
private fun DebugRows(settings: HeylanaSettings, onDebugStates: () -> Unit) {
    val palette = LocalHeylana.current
    var simulateFree by remember { mutableStateOf(settings.simulateFreePlan) }
    var saveTts by remember { mutableStateOf(settings.saveTtsStream) }
    var phoneEars by remember { mutableStateOf(settings.forcePhoneEars) }
    var warmUp by remember { mutableStateOf(settings.warmUpConnection) }
    Spacer(Modifier.height(6.dp))
    SectionHead("Debug")
    FlatRow("Simulate Free plan", subtitle = "Skills count against Free's 3.") {
        FlatSwitch(simulateFree, { simulateFree = it; settings.simulateFreePlan = it })
    }
    FlatRow("Save last tts stream", subtitle = "Keeps the last answer's audio as tts_capture.pcm.") {
        FlatSwitch(saveTts, { saveTts = it; settings.saveTtsStream = it })
    }
    FlatRow("Force phone ears", subtitle = "Skips Deepgram.") {
        FlatSwitch(phoneEars, { phoneEars = it; settings.forcePhoneEars = it })
    }
    FlatRow("Warm up the connection", subtitle = "Opens the connection at the first touch.") {
        FlatSwitch(warmUp, { warmUp = it; settings.warmUpConnection = it })
    }
    FlatRow("Debug states", glyph = Glyph.LAYERS, onClick = onDebugStates) {
        Icon(Glyph.CHEVRON, palette.inkTertiary, size = 16.dp)
    }
}

/**
 * "Heylana 0.9.0 (1)". In debug builds a long press crashes the app on purpose, so a crash
 * report can be checked end to end; the made-up address must arrive in Sentry as [address].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VersionLine() {
    val palette = LocalHeylana.current
    val text = SettingsText.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    val modifier = if (BuildConfig.DEBUG) {
        Modifier.combinedClickable(remember { MutableInteractionSource() }, indication = null, onClick = {}, onLongClick = { throw AppTestCrash() })
    } else Modifier
    Text(text, modifier.fillMaxWidth(), style = HeylanaType.small, color = palette.inkTertiary, textAlign = TextAlign.Center)
}

/** Debug builds only. The address is made up, and must not survive the scrubbing. */
private class AppTestCrash : RuntimeException(
    "Heylana test crash. This address must arrive as [address]: 7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv"
)
