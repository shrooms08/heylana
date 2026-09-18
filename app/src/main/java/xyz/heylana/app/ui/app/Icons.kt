package xyz.heylana.app.ui.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The export's line icons, drawn on its 24-unit grid with a 1.7 stroke. No icon library. */
enum class Glyph {
    MENU, SPEAKER, SPEAKER_OFF, MIC, BACK, CHEVRON, CAMERA, TIMER, SWAP, DOC, PLAY, SEND, POWER, STAR,
    BAG, SLIDERS, SHIELD, GEAR, WALLET, LOCK, CHECK, PAUSE, CLOSE, CHEVRON_UP, CHEVRON_DOWN, BALANCE, BELL, EYE, LAYERS
}

@Composable
fun Icon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = 1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(vararg points: Float): Path = Path().apply {
            moveTo(points[0] * u, points[1] * u)
            var i = 2
            while (i + 1 < points.size) { lineTo(points[i] * u, points[i + 1] * u); i += 2 }
        }
        fun line(vararg points: Float) = drawPath(p(*points), tint, style = stroke)
        fun circle(x: Float, y: Float, r: Float) = drawCircle(tint, r * u, Offset(x * u, y * u), style = stroke)
        fun rect(l: Float, t: Float, w: Float, h: Float, r: Float) = drawRoundRect(
            tint, Offset(l * u, t * u), Size(w * u, h * u), androidx.compose.ui.geometry.CornerRadius(r * u), style = stroke
        )
        fun arc(l: Float, t: Float, s: Float, start: Float, sweep: Float) =
            drawArc(tint, start, sweep, false, Offset(l * u, t * u), Size(s * u, s * u), style = stroke)
        when (glyph) {
            Glyph.MENU -> { line(5f, 8f, 19f, 8f); line(5f, 12f, 19f, 12f); line(5f, 16f, 13f, 16f) }
            Glyph.SPEAKER, Glyph.SPEAKER_OFF -> {
                line(4f, 10f, 4f, 14f, 8f, 14f, 13f, 18f, 13f, 6f, 8f, 10f, 4f, 10f)
                if (glyph == Glyph.SPEAKER) { arc(12f, 8.5f, 7f, -50f, 100f); arc(11f, 5f, 12f, -55f, 110f) } else {
                    line(16f, 10f, 20f, 14f); line(20f, 10f, 16f, 14f)
                }
            }
            Glyph.MIC -> { rect(9f, 3f, 6f, 11f, 3f); arc(5.5f, 5f, 13f, 0f, 180f); line(12f, 18f, 12f, 21f) }
            Glyph.BACK -> line(15f, 5f, 8f, 12f, 15f, 19f)
            Glyph.CHEVRON -> line(10f, 7f, 15f, 12f, 10f, 17f)
            Glyph.CHEVRON_UP -> line(7f, 14f, 12f, 9f, 17f, 14f)
            Glyph.CHEVRON_DOWN -> line(7f, 10f, 12f, 15f, 17f, 10f)
            Glyph.CAMERA -> { rect(3f, 7f, 18f, 13f, 3f); circle(12f, 13.5f, 3.5f); line(9f, 7f, 10f, 4.5f, 14f, 4.5f, 15f, 7f) }
            Glyph.TIMER -> { circle(12f, 13f, 7f); line(12f, 13f, 12f, 9.5f); line(10f, 3f, 14f, 3f) }
            Glyph.SWAP -> { line(4f, 8f, 17f, 8f, 14f, 5f); line(20f, 16f, 7f, 16f, 10f, 19f) }
            Glyph.DOC -> { rect(4f, 3f, 16f, 18f, 3f); line(8f, 8f, 16f, 8f); line(8f, 12f, 13f, 12f) }
            Glyph.PLAY -> { circle(12f, 12f, 9f); line(10f, 8.5f, 15.5f, 12f, 10f, 15.5f, 10f, 8.5f) }
            Glyph.SEND -> { line(4f, 12f, 18f, 12f); line(13f, 7f, 18f, 12f, 13f, 17f); line(20f, 6f, 20f, 18f) }
            Glyph.POWER -> { line(12f, 4f, 12f, 11f); arc(5.5f, 5.5f, 13f, -60f, 300f) }
            Glyph.STAR -> line(12f, 3.5f, 14.6f, 9f, 20.5f, 9.6f, 16f, 13.6f, 17.3f, 19.5f, 12f, 16.5f, 6.7f, 19.5f, 8f, 13.6f, 3.5f, 9.6f, 9.4f, 9f, 12f, 3.5f)
            Glyph.BAG -> { line(5f, 8f, 19f, 8f, 18f, 20f, 6f, 20f, 5f, 8f); arc(8.5f, 3.5f, 7f, 180f, 180f) }
            Glyph.SLIDERS -> { line(4f, 7f, 20f, 7f); line(4f, 12f, 14f, 12f); line(4f, 17f, 20f, 17f); circle(17f, 12f, 2f) }
            Glyph.SHIELD -> line(12f, 3f, 19f, 6f, 19f, 11f, 12f, 21f, 5f, 11f, 5f, 6f, 12f, 3f)
            Glyph.GEAR -> {
                circle(12f, 12f, 3f)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    line(12f + 6f * kotlin.math.cos(a).toFloat(), 12f + 6f * kotlin.math.sin(a).toFloat(),
                        12f + 8.5f * kotlin.math.cos(a).toFloat(), 12f + 8.5f * kotlin.math.sin(a).toFloat())
                }
                circle(12f, 12f, 6f)
            }
            Glyph.WALLET -> { rect(4f, 6f, 16f, 13f, 2.5f); line(20f, 11f, 16f, 11f); circle(16f, 12.5f, 0.3f) }
            Glyph.LOCK -> { rect(5f, 11f, 14f, 10f, 2.5f); arc(8f, 4f, 8f, 180f, 180f); line(8f, 8f, 8f, 11f); line(16f, 8f, 16f, 11f) }
            Glyph.CHECK -> line(5f, 12.5f, 10f, 17f, 19f, 7.5f)
            Glyph.PAUSE -> { line(9f, 6f, 9f, 18f); line(15f, 6f, 15f, 18f) }
            Glyph.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Glyph.BALANCE -> { rect(3f, 6f, 18f, 13f, 3f); line(3f, 10f, 21f, 10f) }
            Glyph.BELL -> { line(6f, 16f, 6f, 11f); arc(6f, 5f, 12f, 180f, 180f); line(18f, 11f, 18f, 16f, 20f, 18f, 4f, 18f, 6f, 16f); line(10f, 21f, 14f, 21f) }
            Glyph.EYE -> { line(2.5f, 12f, 6f, 7.5f, 12f, 5.5f, 18f, 7.5f, 21.5f, 12f, 18f, 16.5f, 12f, 18.5f, 6f, 16.5f, 2.5f, 12f); circle(12f, 12f, 3f) }
            Glyph.LAYERS -> { line(12f, 4f, 21f, 9f, 12f, 14f, 3f, 9f, 12f, 4f); line(3f, 14f, 12f, 19f, 21f, 14f) }
        }
    }
}
