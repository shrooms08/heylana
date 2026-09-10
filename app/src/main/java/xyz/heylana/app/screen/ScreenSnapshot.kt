package xyz.heylana.app.screen

import android.graphics.Rect

/**
 * One UI element read off the screen.
 *
 * Snapshots are read on demand, handed straight to the brain, and dropped.
 * They are never logged and never written to disk.
 *
 * [bounds] stays on this side of the wire: the model only ever sees ids, and the
 * id it picks is turned back into coordinates from the snapshot we kept.
 */
data class ScreenNode(
    val id: Int,
    val depth: Int,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val bounds: Rect
) {

    /**
     * Identity that survives a re-read of the screen. Elements that say something
     * are identified by what they say, so scrolling does not make them look gone;
     * anonymous ones fall back to where they sit.
     */
    val key: String
        get() = keyOf(className, viewId, text, contentDescription, bounds.left, bounds.top)

    companion object {
        /**
         * The same identity, built from loose parts. The tap watch needs it for a
         * node that arrived on an accessibility event rather than in a snapshot,
         * and the two must agree or a tap on the highlighted element is missed.
         */
        fun keyOf(
            className: String,
            viewId: String?,
            text: String?,
            description: String?,
            left: Int,
            top: Int
        ): String = buildString {
            append(className).append('|')
            append(viewId.orEmpty()).append('|')
            append(text.orEmpty()).append('|')
            append(description.orEmpty())
            if (viewId == null && text == null && description == null) {
                append('|').append(left).append(',').append(top)
            }
        }
    }

    /** What to call this element when recounting a step to the model. */
    val label: String
        get() = text ?: contentDescription ?: viewId ?: className
}

/** Everything Heylana can see on screen right now. */
data class ScreenSnapshot(
    val packageName: String,
    val appLabel: String?,
    val nodes: List<ScreenNode>,
    val truncated: Boolean
) {

    val isEmpty: Boolean get() = nodes.isEmpty()

    /** Resolves an id the model gave back. Unknown ids simply mean "no pointer". */
    fun node(id: Int?): ScreenNode? = id?.let { wanted -> nodes.firstOrNull { it.id == wanted } }

    /** True if an element that was pointed at earlier is still on screen. */
    fun contains(key: String): Boolean = nodes.any { it.key == key }

    /**
     * The screen as the model sees it: one short line per element, no coordinates.
     *
     * Bounds are deliberately absent — they were a large share of every request and
     * the model never needed them. It answers with an id; the coordinates are
     * looked up here from the snapshot we kept.
     */
    fun toPromptText(): String = buildString {
        append("App: ")
        append(appLabel ?: "unknown")
        append(" (")
        append(packageName)
        append(")\n")
        if (nodes.isEmpty()) {
            append("(nothing readable on screen)")
            return@buildString
        }
        for (node in nodes) {
            append(INDENT.repeat(node.depth.coerceAtMost(MAX_INDENT)))
            append('[').append(node.id).append("] ").append(node.className)
            node.viewId?.let { append(" #").append(it) }
            if (node.clickable) append(" tap")
            if (node.editable) append(" type")
            if (node.scrollable) append(" scroll")
            node.checked?.let { append(if (it) " on" else " off") }
            node.text?.let { append(" \"").append(it).append('"') }
            node.contentDescription?.let { append(" (").append(it).append(')') }
            append('\n')
        }
        if (truncated) append("(cut at ").append(MAX_NODES).append(")\n")
    }.trimEnd()

    companion object {
        const val MAX_NODES = 120
        const val MAX_TEXT = 60
        private const val INDENT = " "
        private const val MAX_INDENT = 6

        fun empty(packageName: String = "unknown") =
            ScreenSnapshot(packageName, null, emptyList(), false)
    }
}
