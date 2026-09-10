package xyz.heylana.app.screen

import android.accessibilityservice.AccessibilityService
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
 * This service deliberately does no continuous work: [onAccessibilityEvent] is a
 * no-op and nothing is cached. [snapshot] walks the live UI tree at the moment
 * the user sends a question, and the result is never logged or persisted.
 *
 * It performs no actions on the user's behalf: no clicks, no typing, no gestures.
 */
class HeylanaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
    }

    /** Intentionally empty — Heylana does not react to screen activity. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (connected === this) connected = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (connected === this) connected = null
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

        val text = node.text?.toString()?.clean()
        val description = node.contentDescription?.toString()?.clean()
        val viewId = node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val editable = node.isEditable
        val clickable = node.isClickable

        // Keep only nodes that say something useful, or that the user can act on.
        val worthKeeping = text != null || description != null || clickable || editable
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
                    checked = if (node.isCheckable) node.isChecked else null,
                    bounds = bounds
                )
            )
            childDepth = depth + 1
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (collect(child, childDepth, out)) return true
        }
        return out.size >= ScreenSnapshot.MAX_NODES
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

        /** True once Android has actually bound and connected the service. */
        val isConnected: Boolean get() = connected != null

        /** Reads the screen, or returns null if the service is not running. */
        fun snapshotOrNull(): ScreenSnapshot? = connected?.snapshot()

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
