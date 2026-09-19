package xyz.heylana.app.ui.app

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.ui.theme.LocalHeylana

/**
 * A control that acts only when held: a round icon (power for the buddy, brain for memory)
 * with a ring that fills clockwise from the top over [HOLD_MS]. At full the phone ticks and
 * [onFire] runs; letting go sooner unwinds the ring and nothing happens. Flat, like the rest
 * of the app: no glass. TalkBack's double tap fires it directly, since holding a button is
 * not something a screen reader can ask for.
 */
@Composable
fun HoldControl(
    glyph: Glyph,
    on: Boolean,
    description: String,
    onFire: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalHeylana.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val fire by rememberUpdatedState(onFire)
    val isOn by rememberUpdatedState(on)
    Box(
        modifier.size(HOLD_SIZE).alpha(if (enabled) 1f else DISABLED_ALPHA)
            .semantics {
                role = Role.Button
                contentDescription = description
                stateDescription = if (on) "on" else "off"
                if (enabled) onClick(label = description) { fire(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var fired = false
                    val fill: Job = scope.launch {
                        val left = ((1f - progress.value) * HOLD_MS).toInt()
                        progress.animateTo(1f, tween(left, easing = LinearEasing))
                        fired = true
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        HeylanaLog.state("app: hold fired control=$description was_on=$isOn")
                        fire()
                        progress.animateTo(0f, tween(RESET_MS))
                    }
                    waitForUpOrCancellation()
                    if (!fired) {
                        fill.cancel()
                        HeylanaLog.state("app: hold released early control=$description at=${"%.2f".format(progress.value)}")
                        scope.launch { progress.animateTo(0f, tween((progress.value * UNWIND_MS).toInt())) }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = RING_WIDTH.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(palette.switchOff, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (progress.value > 0f) {
                drawArc(
                    palette.accent, -90f, 360f * progress.value, false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
        Box(
            Modifier.size(HOLD_FACE).clip(CircleShape)
                .background(if (on) palette.accent.copy(alpha = ON_ALPHA) else palette.surfaceHigh),
            contentAlignment = Alignment.Center
        ) { Icon(glyph, if (on) palette.accent else palette.ink, size = 22.dp) }
    }
}

/** How long the ring takes to fill: the whole hold. */
const val HOLD_MS = 1_200
/** A released ring takes this long to unwind from full. */
private const val UNWIND_MS = 350
/** After firing, the ring empties this quickly. */
private const val RESET_MS = 250
private val HOLD_SIZE = 52.dp
private val HOLD_FACE = 40.dp
private val RING_WIDTH = 3.dp
private const val ON_ALPHA = 0.18f
private const val DISABLED_ALPHA = 0.4f
