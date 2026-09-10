package xyz.heylana.app.screen

import android.graphics.Rect

/**
 * One UI element read off the screen.
 *
 * Snapshots are read on demand, handed straight to the brain, and dropped.
 * They are never logged and never written to disk.
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
    val checked: Boolean?,
    val bounds: Rect
)

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

    /** Compact indented list for the model — one element per line. */
    fun toPromptText(): String = buildString {
        append("Foreground app: ")
        append(appLabel ?: "unknown")
        append(" (")
        append(packageName)
        append(")\n")
        if (nodes.isEmpty()) {
            append("(no readable elements on screen)")
            return@buildString
        }
        append("Screen elements:\n")
        for (node in nodes) {
            append(INDENT.repeat(node.depth.coerceAtMost(MAX_INDENT)))
            append('[').append(node.id).append("] ").append(node.className)
            node.viewId?.let { append(" #").append(it) }
            if (node.clickable) append(" clickable")
            if (node.editable) append(" editable")
            node.checked?.let { append(if (it) " checked" else " unchecked") }
            node.text?.let { append(" \"").append(it).append('"') }
            node.contentDescription?.let { append(" desc=\"").append(it).append('"') }
            append(" (")
                .append(node.bounds.left).append(',')
                .append(node.bounds.top).append(',')
                .append(node.bounds.right).append(',')
                .append(node.bounds.bottom)
                .append(")\n")
        }
        if (truncated) append("(list truncated at ").append(MAX_NODES).append(" elements)\n")
    }.trimEnd()

    companion object {
        const val MAX_NODES = 250
        const val MAX_TEXT = 120
        private const val INDENT = "  "
        private const val MAX_INDENT = 8

        fun empty(packageName: String = "unknown") =
            ScreenSnapshot(packageName, null, emptyList(), false)
    }
}
