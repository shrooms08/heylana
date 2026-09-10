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
) {

    /**
     * Identity that survives a re-read of the screen. Elements that say something
     * are identified by what they say, so scrolling does not make them look gone;
     * anonymous ones fall back to where they sit.
     */
    val key: String
        get() = buildString {
            append(className).append('|')
            append(viewId.orEmpty()).append('|')
            append(text.orEmpty()).append('|')
            append(contentDescription.orEmpty())
            if (viewId == null && text == null && contentDescription == null) {
                append('|').append(bounds.left).append(',').append(bounds.top)
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
