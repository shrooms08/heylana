package xyz.heylana.app.home

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.ui.app.BarButton
import xyz.heylana.app.ui.app.FlatPage
import xyz.heylana.app.ui.app.FlatSurface
import xyz.heylana.app.ui.app.Glyph
import xyz.heylana.app.ui.app.Icon
import xyz.heylana.app.ui.app.LibraryOrb
import xyz.heylana.app.ui.app.OrbMode
import xyz.heylana.app.ui.app.tap
import xyz.heylana.app.ui.theme.HeylanaMono
import xyz.heylana.app.ui.theme.HeylanaType
import xyz.heylana.app.ui.theme.LocalHeylana
import kotlin.math.PI
import kotlin.math.sin

/** What the label under the timer says. */
enum class VoiceLabel(val words: String) {
    READY("tap or hold to talk"),
    LISTENING("listening"),
    THINKING("thinking"),
    SPEAKING("speaking"),
    PAUSED("paused");

    companion object {
        fun of(phase: VoiceSession.Phase, thinking: Boolean, speaking: Boolean): VoiceLabel = when {
            phase == VoiceSession.Phase.LISTENING -> LISTENING
            phase == VoiceSession.Phase.WAITING || thinking -> THINKING
            speaking -> SPEAKING
            phase == VoiceSession.Phase.PAUSED -> PAUSED
            else -> READY
        }
    }
}

/**
 * The voice screen, frame 2 of the export: an aurora wave across the upper third moved by
 * the microphone (and by Heylana's voice while she answers), a timer, the state in small
 * capitals, and the big mic between pause and close. The mic is tap-to-talk (tap again to
 * send) or hold-to-talk (let go to send). The answer plays and shows in the strip.
 */
@Composable
fun VoiceScreen(
    voice: VoiceSession,
    chat: AppChat,
    startOnOpen: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    val palette = LocalHeylana.current
    val label = VoiceLabel.of(voice.phase, chat.thinking, chat.speaking)

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        HeylanaLog.state("app: voice microphone granted=$granted")
        if (granted) voice.start()
    }
    // Leaving the voice screen, by any way out, stops listening; nothing half-heard is sent.
    DisposableEffect(voice) { onDispose { voice.cancel() } }
    LaunchedEffect(Unit) {
        if (!startOnOpen) return@LaunchedEffect
        if (!voice.start()) askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    // The timer: running while listening, held where it stopped otherwise.
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(voice.phase) {
        while (voice.phase == VoiceSession.Phase.LISTENING) {
            now = SystemClock.uptimeMillis()
            delay(200)
        }
    }
    val shownMs = if (voice.phase == VoiceSession.Phase.LISTENING) now - voice.startedAt else voice.ranMs

    val level = when (label) {
        VoiceLabel.LISTENING -> voice.level
        VoiceLabel.SPEAKING -> chat.level
        VoiceLabel.THINKING -> 0.15f
        else -> 0f
    }
    val smooth by animateFloatAsState(level, tween(120), label = "level")

    FlatPage(background = {
        // The purple that rises from the bottom edge.
        Box(Modifier.fillMaxSize().drawBehind {
            val w = size.width
            val h = size.height
            drawRect(
                Brush.radialGradient(
                    0f to palette.accentHover.copy(alpha = 0.95f),
                    0.38f to palette.accent.copy(alpha = 0.55f),
                    0.62f to palette.accentDeep.copy(alpha = 0.22f),
                    0.82f to palette.accent.copy(alpha = 0f),
                    center = Offset(w / 2f, h * 1.02f),
                    radius = w * 1.1f
                )
            )
            drawRect(
                Brush.radialGradient(
                    0f to palette.aurora[1].copy(alpha = 0.55f),
                    0.62f to palette.aurora[1].copy(alpha = 0f),
                    center = Offset(w / 2f, h * 1.04f),
                    radius = w * 0.7f
                )
            )
        })
        AuroraWave(smooth, label, Modifier.fillMaxWidth().statusBarsPadding().padding(top = 116.dp).height(210.dp))
    }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Back, and the orb in the header: listening, thinking or speaking with the voice.
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                BarButton(Glyph.BACK, "Back", onBack)
                LibraryOrb(
                    when (label) {
                        VoiceLabel.LISTENING -> OrbMode.LISTENING
                        VoiceLabel.THINKING -> OrbMode.THINKING
                        VoiceLabel.SPEAKING -> OrbMode.SPEAKING
                        else -> OrbMode.IDLE
                    },
                    Modifier.align(Alignment.Center),
                    diameter = HEADER_ORB_DP.dp,
                    level = smooth
                )
            }
            Spacer(Modifier.height(116.dp + 210.dp - 64.dp + 40.dp))
            Text(
                MicPress.clock(shownMs),
                Modifier.fillMaxWidth(),
                // A ticking number: monospace with tabular figures, so it does not jitter.
                style = HeylanaType.display.copy(fontFamily = HeylanaMono, fontFeatureSettings = "tnum"),
                color = palette.ink,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                label.words.uppercase(),
                Modifier.fillMaxWidth(),
                style = HeylanaType.caps,
                color = palette.inkTertiary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))

            // What is being heard, a line of our own, or the answer.
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp), contentAlignment = Alignment.TopCenter) {
                val note = voice.note
                when {
                    label == VoiceLabel.LISTENING || label == VoiceLabel.THINKING && voice.heard.isNotEmpty() ->
                        Text(voice.heard, style = HeylanaType.bodyLight, color = palette.inkSecondary, textAlign = TextAlign.Center, maxLines = 3)
                    label == VoiceLabel.THINKING -> Text(chat.asked.orEmpty(), style = HeylanaType.bodyLight, color = palette.inkSecondary, textAlign = TextAlign.Center, maxLines = 3)
                    note != null -> Text(note, style = HeylanaType.bodyLight, color = palette.inkSecondary, textAlign = TextAlign.Center)
                    chat.exchanges.isNotEmpty() -> {
                        var history by remember { androidx.compose.runtime.mutableStateOf(false) }
                        AnswerStrip(chat, history, onHistory = { history = !history })
                    }
                }
            }

            // Pause, the mic, close.
            Row(
                Modifier.fillMaxWidth().padding(bottom = 64.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SideButton(if (voice.phase == VoiceSession.Phase.PAUSED) Glyph.PLAY else Glyph.PAUSE, "Pause") {
                    if (voice.phase == VoiceSession.Phase.LISTENING) voice.pause()
                    else if (voice.phase == VoiceSession.Phase.PAUSED && !voice.start()) askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
                Spacer(Modifier.size(44.dp))
                BigMic(voice, smooth, label, onNeedMic = { askMic.launch(Manifest.permission.RECORD_AUDIO) })
                Spacer(Modifier.size(44.dp))
                SideButton(Glyph.CLOSE, "Close", onClose)
            }
        }
    }
}

@Composable
private fun SideButton(glyph: Glyph, description: String, onClick: () -> Unit) {
    val palette = LocalHeylana.current
    FlatSurface(Modifier.size(52.dp).tap(onClick = onClick), radius = 26.dp, fill = palette.surfaceHigh) {
        Icon(glyph, palette.ink, Modifier.align(Alignment.Center), size = 20.dp)
    }
}

/** The 92dp mic with its two rings, which breathe out with the level. */
@Composable
private fun BigMic(voice: VoiceSession, level: Float, label: VoiceLabel, onNeedMic: () -> Unit) {
    val palette = LocalHeylana.current
    Box(
        Modifier.size(92.dp).drawBehind {
            val r = size.minDimension / 2f
            val swell = 1f + 0.12f * level
            drawCircle(palette.ink.copy(alpha = 0.28f), radius = (r + 16.dp.toPx()) * swell, style = Stroke(1.dp.toPx()))
            drawCircle(palette.ink.copy(alpha = 0.14f), radius = (r + 32.dp.toPx()) * swell, style = Stroke(1.dp.toPx()))
            drawCircle(
                Brush.radialGradient(
                    listOf(palette.accent.copy(alpha = 0.8f), palette.accent.copy(alpha = 0f)),
                    center, r * 1.65f
                ),
                radius = r * 1.65f
            )
        }
            .clip(CircleShape)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        0f to palette.accentHover,
                        0.52f to palette.accent,
                        1f to palette.accentDeep,
                        center = Offset(size.width * 0.36f, size.height * 0.30f),
                        radius = size.width * 0.95f
                    )
                )
            }
            .pointerInput(voice) {
                detectTapGestures(onPress = {
                    val down = SystemClock.uptimeMillis()
                    val wasListening = voice.phase == VoiceSession.Phase.LISTENING
                    var started = false
                    if (!wasListening) {
                        if (voice.phase == VoiceSession.Phase.WAITING) return@detectTapGestures
                        if (!voice.start()) {
                            onNeedMic()
                            return@detectTapGestures
                        }
                        started = true
                    }
                    tryAwaitRelease()
                    val held = SystemClock.uptimeMillis() - down
                    if (MicPress.onRelease(held, started) == MicPress.OnRelease.FINISH) voice.finish()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(Glyph.MIC, palette.onAccent, size = 34.dp)
    }
}

/**
 * Thirteen strands across the upper third, the export's gradient (cyan, purple, pink,
 * orange, fading at both ends). They drift on a slow float and open out with [level].
 */
@Composable
private fun AuroraWave(level: Float, label: VoiceLabel, modifier: Modifier) {
    val palette = LocalHeylana.current
    val transition = rememberInfiniteTransition(label = "wave")
    val drift by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(WAVE_FLOAT_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "drift"
    )
    val calm = label == VoiceLabel.READY || label == VoiceLabel.PAUSED
    val open by animateFloatAsState(if (calm) 0.55f else 0.8f + 0.9f * level, tween(160), label = "open")
    val brush = Brush.horizontalGradient(
        0f to palette.aurora[3].copy(alpha = 0.15f),
        0.28f to palette.aurora[0].copy(alpha = 0.85f),
        0.62f to palette.aurora[1].copy(alpha = 0.9f),
        1f to palette.aurora[2].copy(alpha = 0.2f)
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val phase = drift * 2f * PI.toFloat()
        val float = sin(phase) * 8.dp.toPx()
        val stroke = Stroke(1.1.dp.toPx())
        for (i in 0 until STRANDS) {
            val k = i - STRANDS / 2
            val offset = k * 6.dp.toPx()
            val amplitude = (65f - 6f * kotlin.math.abs(k)).coerceAtLeast(20f) / 210f * h * open
            val path = Path()
            val steps = 48
            for (s in 0..steps) {
                val x = -10f + (w + 20f) * s / steps
                val t = x / w
                val y = mid + float + offset - amplitude * sin(t * 2f * PI.toFloat() + phase + k * 0.05f)
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, brush, style = stroke)
        }
    }
}

/** The orb in the header. */
private const val HEADER_ORB_DP = 48

private const val STRANDS = 13
private const val WAVE_FLOAT_MS = 7_000
