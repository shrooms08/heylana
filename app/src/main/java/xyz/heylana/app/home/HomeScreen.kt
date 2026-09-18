package xyz.heylana.app.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.heylana.app.ui.app.BarButton
import xyz.heylana.app.ui.app.FlatChip
import xyz.heylana.app.ui.app.FlatPage
import xyz.heylana.app.ui.app.FlatSurface
import xyz.heylana.app.ui.app.Glyph
import xyz.heylana.app.ui.app.Icon
import xyz.heylana.app.ui.app.LibraryOrb
import xyz.heylana.app.ui.app.MarkPill
import xyz.heylana.app.ui.app.OrbDebug
import xyz.heylana.app.ui.app.OrbMode
import xyz.heylana.app.ui.app.tap
import xyz.heylana.app.ui.theme.HeylanaType
import xyz.heylana.app.ui.theme.LocalHeylana

/**
 * Home, frames 1 and 1b of the export: the orb, a greeting, the chips, the message bar
 * and the mic. What is asked here is conversation with the app — no screen is read.
 */
@Composable
fun HomeScreen(
    name: String,
    chat: AppChat,
    muted: Boolean,
    onMenu: () -> Unit,
    onMute: (Boolean) -> Unit,
    listening: Boolean,
    micLevel: Float,
    onMicDown: () -> Boolean,
    onMicUp: (heldMs: Long, started: Boolean) -> Unit,
    onAskAboutScreen: () -> Unit,
    onLearn: () -> Unit = {}
) {
    val palette = LocalHeylana.current
    var message by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(false) }
    val thinking = chat.thinking
    val orbMode = OrbDebug.forcedMode ?: when {
        listening -> OrbMode.LISTENING
        thinking -> OrbMode.THINKING
        chat.speaking -> OrbMode.SPEAKING
        else -> OrbMode.IDLE
    }
    val last = chat.exchanges.lastOrNull()

    FlatPage {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // Top bar: menu, the name, the speaker.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton(Glyph.MENU, "Menu", onMenu)
                Spacer(Modifier.weight(1f))
                MarkPill(if (thinking) "thinking…" else "your buddy", subtitleAccent = thinking)
                Spacer(Modifier.weight(1f))
                BarButton(if (muted) Glyph.SPEAKER_OFF else Glyph.SPEAKER, if (muted) "Speaker off" else "Speaker on", { onMute(!muted) })
            }

            // The orb is the character: no mark inside it.
            Spacer(Modifier.height(24.dp))
            LibraryOrb(
                orbMode,
                Modifier.align(Alignment.CenterHorizontally),
                diameter = ORB_DP.dp,
                level = if (listening) micLevel else chat.level
            )
            Spacer(Modifier.height(28.dp))

            // Under the orb: the greeting, "one moment" while thinking, or the answer strip.
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.TopCenter) {
                when {
                    thinking -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("One moment.", style = HeylanaType.bodyLight, color = palette.inkSecondary)
                        Text("Thinking…", style = HeylanaType.display, color = palette.ink)
                    }
                    last != null -> AnswerStrip(chat, history, onHistory = { history = !history })
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (name.isBlank()) "Hi." else "Hi, $name.", style = HeylanaType.bodyLight, color = palette.inkSecondary)
                        Text("What do you need?", style = HeylanaType.display, color = palette.ink, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.weight(1f))

            // The chips give way to the question just asked, and to the keyboard.
            val typing = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            if (!typing) Box(Modifier.fillMaxWidth().height(102.dp)) {
                androidx.compose.animation.AnimatedVisibility(!thinking, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChipRow(HomeChips.FIRST_ROW, { chat.send(it) }, onAskAboutScreen, onLearn)
                        ChipRow(HomeChips.SECOND_ROW, { chat.send(it) }, onAskAboutScreen, onLearn)
                    }
                }
                androidx.compose.animation.AnimatedVisibility(thinking, Modifier.fillMaxSize().padding(horizontal = 20.dp), enter = fadeIn(), exit = fadeOut()) {
                    FlatSurface(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("You asked", style = HeylanaType.label, color = palette.inkSecondary)
                            Text(chat.asked.orEmpty(), style = HeylanaType.body, color = palette.ink, maxLines = 2)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // The message bar and the mic.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                FlatSurface(Modifier.weight(1f).height(58.dp), radius = 29.dp, fill = palette.surfaceHigh) {
                    Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (message.isEmpty()) Text("Message…", style = HeylanaType.bodyLight, color = palette.inkTertiary)
                            BasicTextField(
                                value = message,
                                onValueChange = { message = it },
                                singleLine = true,
                                textStyle = HeylanaType.body.copy(color = palette.ink),
                                cursorBrush = SolidColor(palette.accent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    chat.send(message)
                                    message = ""
                                }),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (message.isNotBlank()) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(palette.accent).tap {
                                    chat.send(message)
                                    message = ""
                                },
                                contentAlignment = Alignment.Center
                            ) { Icon(Glyph.SEND, palette.onAccent, size = 18.dp) }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.size(58.dp).drawBehind {
                        drawCircle(
                            androidx.compose.ui.graphics.Brush.radialGradient(
                                listOf(palette.accent.copy(alpha = 0.45f), palette.accent.copy(alpha = 0f)), center, size.minDimension * 0.9f
                            ),
                            radius = size.minDimension * 0.9f
                        )
                    }.clip(CircleShape).background(palette.accent).pointerInput(Unit) {
                        // Tap opens the voice screen listening; hold talks and sends on release.
                        detectTapGestures(onPress = {
                            val down = SystemClock.uptimeMillis()
                            val started = onMicDown()
                            tryAwaitRelease()
                            onMicUp(SystemClock.uptimeMillis() - down, started)
                        })
                    },
                    contentAlignment = Alignment.Center
                ) { Icon(if (thinking) Glyph.PAUSE else Glyph.MIC, palette.onAccent, size = 22.dp) }
            }
        }
    }
}

/** The orb on Home. */
private const val ORB_DP = 200

@Composable
private fun ChipRow(chips: List<HomeChip>, send: (String) -> Unit, startBuddy: () -> Unit, learn: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        chips.forEach { chip ->
            FlatChip(chip.glyph, chip.label, onClick = { HomeChips.tap(chip, send, startBuddy, learn) })
        }
    }
}

/** The answer, up to six lines and scrolling past that; the chevron opens the last three exchanges. */
@Composable
fun AnswerStrip(chat: AppChat, history: Boolean, onHistory: () -> Unit) {
    val palette = LocalHeylana.current
    val shown = if (history) chat.exchanges.toList() else listOfNotNull(chat.exchanges.lastOrNull())
    FlatSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 14.dp)) {
            // While a lesson runs: which one, and how far along.
            chat.lessonProgress?.let { Text("Lesson · $it", Modifier.padding(bottom = 6.dp), style = HeylanaType.label, color = palette.inkSecondary) }
            Column(
                Modifier.heightIn(max = if (history) 260.dp else 22.dp * 6).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                shown.forEach { exchange ->
                    if (history) Text(exchange.question, style = HeylanaType.small, color = palette.inkSecondary)
                    Text(exchange.answer, style = HeylanaType.body, color = palette.ink)
                }
            }
            if (chat.exchanges.size > 1) {
                Row(Modifier.fillMaxWidth().tap(onClick = onHistory).padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                    Text(if (history) "Latest" else "Earlier", style = HeylanaType.label, color = palette.inkTertiary)
                    Spacer(Modifier.width(4.dp))
                    Icon(if (history) Glyph.CHEVRON_DOWN else Glyph.CHEVRON_UP, palette.inkTertiary, size = 16.dp)
                }
            }
        }
    }
}
