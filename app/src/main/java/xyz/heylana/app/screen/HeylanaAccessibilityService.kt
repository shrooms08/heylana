package xyz.heylana.app.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads the current screen — and only when asked.
 *
 * Privacy rule, and it is a rule and not an optimisation: **outside an active
 * guidance session this service processes no accessibility events at all.** The
 * subscription itself is torn down — [AccessibilityServiceInfo.eventTypes] is set
 * to zero — so the system delivers nothing, and [onAccessibilityEvent] additionally
 * returns immediately when no watcher is registered. Events are switched on only
 * while Heylana is walking the user through a task, so it can notice that a step
 * has been completed, and switched straight back off when the task ends.
 *
 * [snapshot] walks the live UI tree at the moment it is asked to, and the result
 * is never logged and never persisted.
 *
 * It performs no actions on the user's behalf: no clicks, no typing, no gestures.
 */
class HeylanaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
        // Start deaf. Nothing is delivered until a guidance session asks for it.
        applyEventTypes(enabled = false)
    }

    /**
     * Only reached while a session has registered a watcher; even then it does
     * nothing but tell that watcher the screen moved.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val listener = watcher ?: return
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> listener.invoke()
        }
    }

    private fun applyEventTypes(enabled: Boolean) {
        val info = serviceInfo ?: return
        info.eventTypes = if (enabled) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } else {
            0
        }
        serviceInfo = info
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (connected === this) {
            connected = null
            watcher = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (connected === this) {
            connected = null
            watcher = null
        }
        super.onDestroy()
    }

    /** Reads the topmost window that is not Heylana's own overlay. */
    fun snapshot(): ScreenSnapshot {
        val root = topmostForeignRoot() ?: return ScreenSnapshot.empty()
        val targetPackage = root.packageName?.toString() ?: "unknown"

        val nodes = ArrayList<ScreenNode>(ScreenSnapshot.MAX_NODES)
        val truncated = collect(root, depth = 0, out = nodes)

        return ScreenSnapshot(
            packageName = targetPackage,
            appLabel = appLabel(targetPackage),
            nodes = nodes,
            truncated = truncated
        )
    }

    /**
     * Picks the visible application window with the highest layer whose package
     * is not ours, so our own overlay never shadows the app being asked about.
     */
    private fun topmostForeignRoot(): AccessibilityNodeInfo? {
        val candidates = windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() != packageName }
            .toList()

        return candidates.firstOrNull()
            ?: rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }
    }

    /** Depth-first walk. Returns true if the node cap was hit. */
    @Suppress("DEPRECATION") // isChecked has no pre-API-36 replacement
    private fun collect(node: AccessibilityNodeInfo, depth: Int, out: MutableList<ScreenNode>): Boolean {
        if (out.size >= ScreenSnapshot.MAX_NODES) return true
        if (!node.isVisibleToUser) return false

        var text = node.text?.toString()?.clean()
        var description = node.contentDescription?.toString()?.clean()
        val viewId = node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val editable = node.isEditable
        val clickable = node.isClickable
        val checkable = node.isCheckable
        val scrollable = node.isScrollable

        // Whose children to walk next. Normally this node's own.
        var childSource = node

        // A tappable wrapper whose label lives on a lone child inside it is one
        // thing to the user, so send one line rather than two or three.
        if (clickable && text == null && description == null) {
            soleLabelledDescendant(node)?.let { inner ->
                text = inner.text?.toString()?.clean()
                description = inner.contentDescription?.toString()?.clean()
                childSource = inner
            }
        }

        // Keep only what the user can read or act on. Pure layout and decoration
        // is dropped: it was most of the list and none of the meaning.
        val worthKeeping = text != null || description != null ||
            clickable || editable || checkable || scrollable

        var childDepth = depth
        if (worthKeeping) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            out.add(
                ScreenNode(
                    id = out.size,
                    depth = depth,
                    className = simplifyClassName(node.className?.toString()),
                    text = text,
                    contentDescription = description,
                    viewId = viewId,
                    clickable = clickable,
                    editable = editable,
                    scrollable = scrollable,
                    checked = if (checkable) node.isChecked else null,
                    bounds = bounds
                )
            )
            childDepth = depth + 1
        }

        for (i in 0 until childSource.childCount) {
            val child = childSource.getChild(i) ?: continue
            if (collect(child, childDepth, out)) return true
        }
        return out.size >= ScreenSnapshot.MAX_NODES
    }

    /**
     * Follows a single-child chain down from a tappable wrapper and returns the
     * one node inside that actually carries a label, or null if the wrapper holds
     * more than one thing.
     */
    private fun soleLabelledDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node
        repeat(MAX_COLLAPSE_DEPTH) {
            val visibleChildren = (0 until current.childCount)
                .mapNotNull { current.getChild(it) }
                .filter { it.isVisibleToUser }
            if (visibleChildren.size != 1) return null
            val only = visibleChildren.first()
            if (!only.text.isNullOrBlank() || !only.contentDescription.isNullOrBlank()) return only
            current = only
        }
        return null
    }

    private fun appLabel(pkg: String): String? = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        null
    }

    private fun String.clean(): String? {
        val collapsed = trim().replace(WHITESPACE, " ")
        if (collapsed.isEmpty()) return null
        return if (collapsed.length > ScreenSnapshot.MAX_TEXT) {
            collapsed.take(ScreenSnapshot.MAX_TEXT) + "…"
        } else {
            collapsed
        }
    }

    companion object {
        @Volatile
        private var connected: HeylanaAccessibilityService? = null

        private val WHITESPACE = Regex("\\s+")

        /** How far to follow a single-child chain when collapsing a wrapper. */
        private const val MAX_COLLAPSE_DEPTH = 3

        /** True once Android has actually bound and connected the service. */
        val isConnected: Boolean get() = connected != null

        /**
         * Set while a guidance session is running, cleared the moment it ends.
         * While it is null the service subscribes to nothing and processes nothing.
         */
        @Volatile
        private var watcher: (() -> Unit)? = null

        /** Reads the screen, or returns null if the service is not running. */
        fun snapshotOrNull(): ScreenSnapshot? = connected?.snapshot()

        /**
         * Turns screen-change events on for the duration of a guidance session.
         * Pass null to stop listening entirely.
         */
        fun watchScreenChanges(onChanged: (() -> Unit)?) {
            watcher = onChanged
            connected?.applyEventTypes(enabled = onChanged != null)
        }

        /** True if the user has switched Heylana on in Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = ComponentName(context, HeylanaAccessibilityService::class.java)
            return enabled.split(':').any {
                it.equals(expected.flattenToString(), ignoreCase = true) ||
                    it.equals(expected.flattenToShortString(), ignoreCase = true)
            }
        }

        private fun simplifyClassName(raw: String?): String {
            val name = raw?.substringAfterLast('.') ?: return "View"
            return when {
                name.contains("EditText") || name.contains("AutoComplete") -> "EditText"
                name.contains("ImageButton") -> "Button"
                name.contains("Button") -> "Button"
                name.contains("CheckBox") -> "CheckBox"
                name.contains("RadioButton") -> "RadioButton"
                name.contains("Switch") || name.contains("ToggleButton") -> "Switch"
                name.contains("SeekBar") || name.contains("ProgressBar") -> "Slider"
                name.contains("TextView") -> "Text"
                name.contains("ImageView") || name.contains("Icon") -> "Image"
                name.contains("WebView") -> "WebView"
                name.contains("RecyclerView") || name.contains("ListView") ||
                    name.contains("GridView") || name.contains("ScrollView") -> "List"
                name.contains("TabLayout") || name.contains("TabWidget") -> "Tabs"
                name.contains("Toolbar") || name.contains("ActionBar") -> "Toolbar"
                name.contains("Dialog") -> "Dialog"
                name.contains("Layout") || name == "View" || name == "ViewGroup" -> "Group"
                else -> name
            }
        }
    }
}
