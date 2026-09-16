package xyz.heylana.app.screen

import xyz.heylana.app.brain.AddressText
import xyz.heylana.app.brain.SigningScan

/**
 * Every window the user can see, merged into one reading, topmost first.
 *
 * A wallet's send sheet, a dialog or a permission prompt is a window of its own
 * above the activity behind it. Reading only one window missed exactly the one
 * that mattered, so all application windows are read, Heylana's own are dropped,
 * and the rest are ordered by layer: whatever is on top comes first.
 *
 * The model still gets at most [ScreenSnapshot.MAX_NODES] elements. When there are
 * more, the ones a money question needs — amounts, addresses short or full,
 * .skr/.sol names, and to/from/fee/send/approve words — are kept first, and the
 * kept ones stay in reading order. Plain ints throughout, so it can be tested.
 */
object WindowMerge {

    data class Raw(
        val depth: Int,
        val className: String,
        val text: String?,
        val description: String?,
        val viewId: String?,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checked: Boolean?,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val words: String get() = listOfNotNull(text, description).joinToString(" ")
    }

    data class Window(val packageName: String, val layer: Int, val nodes: List<Raw>, val truncated: Boolean = false)

    /** For the log: which window, how much was read from it, how much was kept. */
    data class WindowCount(val packageName: String, val layer: Int, val read: Int, val kept: Int)

    data class Merged(
        /** The topmost window's app: what the question is about. Null when nothing was readable. */
        val packageName: String?,
        val nodes: List<Raw>,
        val truncated: Boolean,
        val windows: List<WindowCount>,
        /** The topmost window that says sending, network fee, approve and the like. */
        val signingWordsFrom: WindowCount?
    )

    private val PRIORITY_WORDS = Regex(
        "(?<![\\p{L}])(to|from|fee|network fee|send|sending|approve|confirm|sign|signature|review|recipient|amount)(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )

    private val NAME = Regex("[\\p{L}\\p{N}-]{1,63}\\.(skr|sol)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    fun isPriority(node: Raw): Boolean {
        val words = node.words
        if (words.isBlank()) return false
        return SigningScan.amounts(words).isNotEmpty() ||
            AddressText.shortAddresses(words).isNotEmpty() ||
            AddressText.fullAddresses(words).isNotEmpty() ||
            NAME.containsMatchIn(words) ||
            PRIORITY_WORDS.containsMatchIn(words)
    }

    fun merge(windows: List<Window>, ownPackage: String, cap: Int = ScreenSnapshot.MAX_NODES): Merged {
        val readable = windows.filter { it.packageName != ownPackage }.sortedByDescending { it.layer }

        data class Placed(val window: Int, val index: Int, val node: Raw, val priority: Boolean)
        val all = readable.flatMapIndexed { w, window ->
            window.nodes.mapIndexed { i, node -> Placed(w, i, node, isPriority(node)) }
        }
        val chosen = (all.filter { it.priority } + all.filterNot { it.priority })
            .take(cap)
            .map { it.window to it.index }
            .toSet()
        val kept = all.filter { (it.window to it.index) in chosen }

        val counts = readable.mapIndexed { w, window ->
            WindowCount(window.packageName, window.layer, window.nodes.size, kept.count { it.window == w })
        }
        val signingWindow = readable.indexOfFirst { window -> window.nodes.any { SigningScan.hasSigningWords(it.words) } }

        return Merged(
            packageName = readable.firstOrNull()?.packageName,
            nodes = kept.map { it.node },
            truncated = all.size > cap || readable.any { it.truncated },
            windows = counts,
            signingWordsFrom = counts.getOrNull(signingWindow)
        )
    }
}
