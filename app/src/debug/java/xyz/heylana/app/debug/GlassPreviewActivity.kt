package xyz.heylana.app.debug

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import xyz.heylana.app.overlay.ChatPanelView
import xyz.heylana.app.ui.GlassDrawable
import xyz.heylana.app.ui.HeylanaTokens

/**
 * Debug-only. Renders the message box over a bright backdrop and over black, so
 * the glass can be judged on both without starting the buddy or spending a
 * single API call.
 *
 * Note what this cannot show: the real overlay blurs the live screen behind it
 * through the window manager, and an ordinary activity cannot. What you are
 * checking here is the fill, the border, the top highlight, the purple band and
 * the shadow — the layers Heylana draws itself.
 */
class GlassPreviewActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            panel("over a bright backdrop", bright = true),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(
            panel("over black", bright = false),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    private fun panel(caption: String, bright: Boolean): View {
        val frame = FrameLayout(this)
        frame.background = if (bright) brightBackdrop() else null
        if (!bright) frame.setBackgroundColor(Color.BLACK)

        // The real overlay always lays its dim over the app first, so a preview
        // without one would flatter the glass on a bright backdrop.
        frame.addView(
            View(this).apply { setBackgroundColor(HeylanaTokens.scrim) },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(HeylanaTokens.SPACE_5_DP)
            setPadding(pad, pad, pad, pad)
        }

        column.addView(
            TextView(this).apply {
                text = caption
                setTextColor(HeylanaTokens.textPrimary)
                typeface = HeylanaTokens.typeface(context, HeylanaTokens.WEIGHT_MEDIUM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, HeylanaTokens.LABEL_SP)
                letterSpacing = HeylanaTokens.LABEL_TRACKING_EM
                background = GlassDrawable(
                    context, HeylanaTokens.RADIUS_FULL_DP, blurBehind = false,
                    kind = GlassDrawable.Kind.PILL
                )
                setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // The real panel, not a mock-up of it.
        val box = ChatPanelView(this).apply {
            // Blur is a window flag an activity cannot ask for, so this shows the
            // heavier no-blur fill — the harder of the two cases to get right.
            applyGlass(blurBehind = false)
            showAnswer("Tokyo is nine hours ahead of London.")
        }
        column.addView(
            box,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(HeylanaTokens.SPACE_4_DP) }
        )

        val readout = TextView(this).apply {
            setTextColor(HeylanaTokens.textPrimary)
            typeface = HeylanaTokens.typeface(context, HeylanaTokens.WEIGHT_MEDIUM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HeylanaTokens.LABEL_SP)
            background = GlassDrawable(
                context, HeylanaTokens.RADIUS_FULL_DP, blurBehind = false,
                kind = GlassDrawable.Kind.PILL
            )
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        }
        column.addView(
            readout,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(HeylanaTokens.SPACE_3_DP) }
        )
        box.post {
            val backdrop = if (bright) Color.WHITE else Color.BLACK
            val ratio = measureContrast(box, backdrop)
            val verdict = if (ratio >= MIN_CONTRAST) "PASS" else "FAIL"
            readout.text = String.format("body text %.2f:1  %s", ratio, verdict)
        }

        frame.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        return frame
    }

    /** Stands in for a bright photo: saturated blobs on a pale ground. */
    private fun brightBackdrop() = ShapeDrawable(RectShape()).apply {
        setShaderFactory(object : ShapeDrawable.ShaderFactory() {
            override fun resize(width: Int, height: Int): Shader {
                val base = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(
                        Color.parseColor("#FFF3D9"),
                        Color.parseColor("#9FE7FF"),
                        Color.parseColor("#FFC9E4")
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                val blob = RadialGradient(
                    width * 0.7f, height * 0.3f, width * 0.5f,
                    intArrayOf(Color.parseColor("#FFFFFFFF"), Color.parseColor("#00FFFFFF")),
                    null, Shader.TileMode.CLAMP
                )
                return android.graphics.ComposeShader(
                    base, blob, android.graphics.PorterDuff.Mode.SRC_OVER
                )
            }
        })
    }

    /**
     * Measures the real thing rather than the recipe: renders the panel over a
     * flat backdrop, samples every pixel behind the answer text, and reports the
     * worst contrast ratio found. Anything under 4.5:1 is a failure.
     */
    private fun measureContrast(panel: View, backdrop: Int): Double {
        if (panel.width == 0 || panel.height == 0) return 0.0
        val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backdrop)
        canvas.drawColor(HeylanaTokens.scrim)
        // The glass only. Drawing the whole panel would sample the letters
        // themselves, which of course measure 1:1 against themselves.
        panel.background?.apply {
            setBounds(0, 0, panel.width, panel.height)
            draw(canvas)
        }

        val textLuminance = luminance(HeylanaTokens.textPrimary)
        var worst = Double.MAX_VALUE
        // The band the answer sits in: the top third, inside the padding.
        val left = dp(HeylanaTokens.SPACE_4_DP)
        val right = panel.width - left
        val top = dp(HeylanaTokens.SPACE_4_DP)
        val bottom = minOf(panel.height, top + dp(HeylanaTokens.SPACE_6_DP))
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                worst = minOf(worst, contrast(textLuminance, luminance(bitmap.getPixel(x, y))))
                x += 4
            }
            y += 4
        }
        bitmap.recycle()
        return worst
    }

    private fun contrast(a: Double, b: Double): Double {
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(colour: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(colour)) +
            0.7152 * channel(Color.green(colour)) +
            0.0722 * channel(Color.blue(colour))
    }

    private fun dp(value: Float): Int = HeylanaTokens.dpInt(this, value)

    private companion object {
        /** WCAG AA for body text. */
        const val MIN_CONTRAST = 4.5
    }
}
