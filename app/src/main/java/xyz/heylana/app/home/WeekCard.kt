package xyz.heylana.app.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.ui.HeylanaTokens
import xyz.heylana.app.wallet.Answer
import xyz.heylana.app.wallet.WalletApi
import xyz.heylana.app.wallet.WeekCatch
import xyz.heylana.app.wallet.WeekItem
import java.io.File

/**
 * "What I caught this week" — the card on Home, once a week.
 *
 * The worker counts; this only shows. Everything here is a number and the words for what it
 * counted ("14 screens explained"), which is all the worker sends: no address, no amount, no
 * word of a screen, a question or an answer, in the card, the list or the picture it shares.
 * It follows memory: with memory off the worker counts nothing and there is no card.
 */
class WeekModel(
    private val context: Context,
    private val settings: HeylanaSettings,
    private val api: WalletApi,
    private val scope: CoroutineScope
) {

    /** What the worker last said; null until it has answered. */
    var caught by mutableStateOf<WeekCatch?>(null)
        private set

    /** The list is open over Home. */
    var open by mutableStateOf(false)
        private set

    /** Set once the card has been put away, so it does not come back until next week. */
    private var dismissed by mutableStateOf(false)

    /** Whether Home shows the card at all: something caught, this week, not already seen. */
    val show: Boolean
        get() {
            val week = caught ?: return false
            return week.memoryOn && week.anything && !dismissed && settings.weekCardSeen != week.weekStart
        }

    /** Asked for once when Home appears; a wallet is needed, and memory must be on. */
    fun load() {
        if (caught != null) return
        scope.launch {
            when (val answer = api.week()) {
                is Answer.Ok -> {
                    caught = answer.value
                    HeylanaLog.state("week: ${answer.value.items.size} kinds caught memory=${answer.value.memoryOn}")
                }
                else -> HeylanaLog.state("week: not shown, ${xyz.heylana.app.wallet.describe(answer)}")
            }
        }
    }

    fun openList() {
        open = true
    }

    fun closeList() {
        open = false
    }

    /** Put away until next week. */
    fun dismiss() {
        caught?.let { settings.weekCardSeen = it.weekStart }
        dismissed = true
        open = false
    }

    /** Debug builds only: a week of made-up counts, so the card can be seen without waiting one. */
    fun seed() {
        if (!BuildConfig.DEBUG) return
        caught = WeekCatch(
            weekStart = "seeded",
            memoryOn = true,
            line = "This week: 14 screens explained, 2 sends checked, 1 stopped before signing.",
            items = listOf(
                WeekItem("screens", "screens explained", 14),
                WeekItem("transactions", "transactions explained", 3),
                WeekItem("sends_prepared", "sends checked", 2),
                WeekItem("sends_stopped", "stopped before signing", 1),
                WeekItem("new_addresses", "new addresses looked up", 4),
                WeekItem("lessons", "lessons finished", 1),
                WeekItem("questions", "questions answered", 31),
            )
        )
        dismissed = false
        settings.weekCardSeen = ""
        HeylanaLog.state("week: seeded (debug)")
    }

    /** The card as a picture, handed to whatever the user shares with. */
    fun share() {
        val week = caught ?: return
        val file = runCatching {
            val picture = WeekImage.draw(week)
            val folder = File(context.cacheDir, SHARE_FOLDER).apply { mkdirs() }
            val out = File(folder, SHARE_NAME)
            out.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            picture.recycle()
            out
        }.getOrElse {
            HeylanaLog.state("week: share failed ${it.javaClass.simpleName}")
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.shares", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        HeylanaLog.state("week: shared as an image lines=${week.items.size}")
        runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    companion object {
        private const val SHARE_FOLDER = "shares"
        private const val SHARE_NAME = "heylana-week.png"
    }
}

/**
 * The shared picture: the same numbers and words the card shows, drawn on Heylana's own
 * background. Nothing else can get in — it is built from [WeekCatch.items] alone.
 */
object WeekImage {

    const val WIDTH = 1080
    const val TOP = 132f
    const val LINE_HEIGHT = 96f
    const val SIDE = 84f

    fun height(items: Int): Int = (TOP + LINE_HEIGHT * (items + 2) + 120f).toInt()

    fun draw(week: WeekCatch): Bitmap {
        val shown = week.items
        val picture = Bitmap.createBitmap(WIDTH, height(shown.size), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(picture)
        canvas.drawColor(HeylanaTokens.bg)

        // A wash of the accent in the top-right corner, as the app's own screens have.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(
                WIDTH.toFloat(), 0f, WIDTH * 0.8f,
                intArrayOf(HeylanaTokens.withAlpha(HeylanaTokens.accent, 0.16f), HeylanaTokens.withAlpha(HeylanaTokens.accent, 0f)),
                floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), picture.height.toFloat(), glow)

        val title = paint(HeylanaTokens.textPrimary, 64f, Typeface.NORMAL)
        val label = paint(HeylanaTokens.text2, 44f, Typeface.NORMAL)
        val number = paint(HeylanaTokens.accentText, 52f, Typeface.BOLD, mono = true)
        val foot = paint(HeylanaTokens.textSecondary, 34f, Typeface.NORMAL)

        canvas.drawText("What Heylana caught this week", SIDE, TOP, title)
        var y = TOP + LINE_HEIGHT * 1.2f
        for (item in shown) {
            canvas.drawText(item.count.toString(), SIDE, y, number)
            canvas.drawText(item.label, SIDE + 130f, y, label)
            y += LINE_HEIGHT
        }
        canvas.drawText("Counts only — no addresses, no amounts, nothing from your screen.", SIDE, y + 30f, foot)
        return picture
    }

    private fun paint(colour: Int, size: Float, style: Int, mono: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colour
            textSize = size
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF, style)
        }
}
