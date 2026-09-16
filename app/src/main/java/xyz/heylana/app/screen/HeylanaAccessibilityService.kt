package xyz.heylana.app.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.brain.Routing
import xyz.heylana.app.brain.SigningScan
import xyz.heylana.app.settings.HeylanaSettings

/**
 * Reads the current screen — and only when asked.
 *
 * Privacy rule, and it is a rule and not an optimisation: **outside the two
 * moments below this service processes no accessibility events at all.** The
 * subscription itself is torn down — [AccessibilityServiceInfo.eventTypes] is set
 * to zero — so the system delivers nothing, and [onAccessibilityEvent] additionally
 * returns immediately when no watcher is registered.
 *
 * Events are switched on for exactly two things, and switched straight back off:
 *
 *  - while Heylana is walking the user through a task, so it can notice a step
 *    has been completed ([watchScreenChanges]);
 *  - for a few seconds after it points at something, so it can notice the user
 *    tapping it and get the box out of the way ([watchTaps]).
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
        // Start deaf. Nothing is delivered until something asks for it.
        applyEventTypes()
        if (BuildConfig.DEBUG) registerDebugRead()
    }

    /**
     * Only reached while a session or a tap watch is registered, and even then it
     * does nothing but say what the screen did. No event is stored or logged.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val screen = watcher
        val taps = tapWatcher
        if (screen == null && taps == null) return
        if (event == null) return
        // Our own windows moving is not the user doing anything.
        if (event.packageName?.toString() == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                taps?.invoke(ScreenSignal.Clicked(clickedKey(event)))

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                screen?.invoke()
                taps?.invoke(ScreenSignal.Changed)
            }
        }
    }

    /**
     * The clicked element in the same terms a snapshot uses, so it can be
     * compared with whatever is being pointed at. Null if the event carried no
     * usable source, which simply means "cannot tell".
     */
    private fun clickedKey(event: AccessibilityEvent): String? {
        val source = event.source ?: return null
        return try {
            val bounds = Rect().also { source.getBoundsInScreen(it) }
            ScreenNode.keyOf(
                className = simplifyClassName(source.className?.toString()),
                viewId = source.viewIdResourceName?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() },
                text = source.text?.toString()?.clean(),
                description = source.contentDescription?.toString()?.clean(),
                left = bounds.left,
                top = bounds.top
            )
        } finally {
            source.recycle()
        }
    }

    /** The union of what the things currently watching actually need. */
    private fun applyEventTypes() {
        val info = serviceInfo ?: return
        var types = 0
        if (watcher != null) {
            types = types or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        }
        if (tapWatcher != null) {
            types = types or AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        }
        info.eventTypes = types
        serviceInfo = info
    }

    override fun onInterrupt() = Unit

    private var debugRead: BroadcastReceiver? = null

    /**
     * Debug builds only: `adb shell am broadcast -a xyz.heylana.app.debug.READ_SCREEN`
     * reads the screen exactly as a question would and logs what the signing
     * check finds and how it would route — counts and package names only, and no
     * request goes anywhere.
     */
    private fun registerDebugRead() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Exactly as a question reads: if the box is open it lets the read
                // through first, and the read waits the same moment for the app.
                val through = readThrough
                through?.invoke(true)
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        debugReport(context)
                    } finally {
                        through?.invoke(false)
                    }
                }, intent.getLongExtra("settle_ms", DEBUG_READ_SETTLE_MS))
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(DEBUG_READ_ACTION), ContextCompat.RECEIVER_EXPORTED)
        debugRead = receiver
    }

    private fun debugReport(context: Context) {
                val snapshot = snapshot()
                val text = snapshot.toPromptText()
                val found = SigningScan.of(text)
                val own = HeylanaSettings.get(context).walletSession?.pubkey
                val route = Routing.forQuestion(snapshot.packageName, "what is this", text, own)
                HeylanaLog.state(
                    "debug-read: pkg=${snapshot.packageName} nodes=${snapshot.nodes.size} " +
                        "signing=${SigningScan.looksLikeSigning(snapshot.packageName, text, own)} " +
                        "addresses=${found.addresses.size} shortened=${found.shortAddresses.size} " +
                        "amounts=${found.amounts.size} route=${route.why.log} greets=${route.allowsGreeting}"
                )
    }

    private fun unregisterDebugRead() {
        debugRead?.let { runCatching { unregisterReceiver(it) } }
        debugRead = null
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        unregisterDebugRead()
        if (connected === this) {
            connected = null
            watcher = null
            tapWatcher = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        unregisterDebugRead()
        if (connected === this) {
            connected = null
            watcher = null
            tapWatcher = null
        }
        super.onDestroy()
    }

    /**
     * Reads every window the user can see except Heylana's own, topmost first, so
     * a sheet or dialog on top is read before the activity behind it. Logs counts
     * and packages per window, never a word of what is on them.
     */
    fun snapshot(): ScreenSnapshot {
        val merged = WindowMerge.merge(readWindows(), ownPackage = packageName)
        HeylanaLog.state(
            "screen: windows=${runCatching { windows.size }.getOrDefault(-1)} read=${merged.windows.size} " +
                "kept=${merged.nodes.size} truncated=${merged.truncated}"
        )
        for (window in merged.windows) {
            HeylanaLog.state("screen: window pkg=${window.packageName} layer=${window.layer} nodes=${window.read} kept=${window.kept}")
        }
        HeylanaLog.state(
            "screen: signing words from " +
                (merged.signingWordsFrom?.let { "pkg=${it.packageName} layer=${it.layer}" } ?: "none")
        )
        val target = merged.packageName ?: return ScreenSnapshot.empty()
        val nodes = merged.nodes.mapIndexed { index, raw ->
            ScreenNode(
                id = index,
                depth = raw.depth,
                className = raw.className,
                text = raw.text,
                contentDescription = raw.description,
                viewId = raw.viewId,
                clickable = raw.clickable,
                editable = raw.editable,
                scrollable = raw.scrollable,
                checked = raw.checked,
                bounds = Rect(raw.left, raw.top, raw.right, raw.bottom)
            )
        }
        return ScreenSnapshot(
            packageName = target,
            appLabel = appLabel(target),
            nodes = nodes,
            truncated = merged.truncated
        )
    }

    /**
     * Every application window's tree. The keyboard is not an application window,
     * and Heylana's own are dropped in the merge. When the system reports no window
     * list at all, the active window alone.
     */
    private fun readWindows(): List<WindowMerge.Window> {
        val found = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                val nodes = ArrayList<WindowMerge.Raw>()
                val truncated = collect(root, depth = 0, out = nodes)
                WindowMerge.Window(root.packageName?.toString() ?: "unknown", window.layer, nodes, truncated)
            }
        if (found.any { it.packageName != packageName }) return found
        val root = rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName } ?: return found
        val nodes = ArrayList<WindowMerge.Raw>()
        val truncated = collect(root, depth = 0, out = nodes)
        return found + WindowMerge.Window(root.packageName?.toString() ?: "unknown", Int.MIN_VALUE, nodes, truncated)
    }

    /** Depth-first walk. Returns true if the node cap was hit. */
    @Suppress("DEPRECATION") // isChecked has no pre-API-36 replacement
    private fun collect(node: AccessibilityNodeInfo, depth: Int, out: MutableList<WindowMerge.Raw>): Boolean {
        if (out.size >= MAX_READ_PER_WINDOW) return true
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
                WindowMerge.Raw(
                    depth = depth,
                    className = simplifyClassName(node.className?.toString()),
                    text = text,
                    description = description,
                    viewId = viewId,
                    clickable = clickable,
                    editable = editable,
                    scrollable = scrollable,
                    checked = if (checkable) node.isChecked else null,
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom
                )
            )
            childDepth = depth + 1
        }

        for (i in 0 until childSource.childCount) {
            val child = childSource.getChild(i) ?: continue
            if (collect(child, childDepth, out)) return true
        }
        return out.size >= MAX_READ_PER_WINDOW
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

        /** Per window, before the merge picks what the model gets. */
        private const val MAX_READ_PER_WINDOW = 300

        /** Debug builds only: read the screen as a question would, log counts, ask nobody. */
        const val DEBUG_READ_ACTION = "xyz.heylana.app.debug.READ_SCREEN"
        private const val DEBUG_READ_SETTLE_MS = 350L

        /**
         * Set by the buddy while its overlay exists: lets a debug read open the box
         * to accessibility the way a real question does.
         */
        @Volatile
        var readThrough: ((Boolean) -> Unit)? = null

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
            connected?.applyEventTypes()
        }

        /**
         * Set for the few seconds after Heylana points at something, so it can
         * see the user act on it. Pass null the moment that is over.
         */
        @Volatile
        private var tapWatcher: ((ScreenSignal) -> Unit)? = null

        fun watchTaps(onSignal: ((ScreenSignal) -> Unit)?) {
            tapWatcher = onSignal
            connected?.applyEventTypes()
        }

        /** True while anything at all is being listened for. */
        val isWatching: Boolean get() = watcher != null || tapWatcher != null

        /**
         * True only when screen reading actually works: listed, the master
         * accessibility switch on, and the service bound into this process.
         *
         * Being listed is not enough. When the app crashes with the service
         * bound, Android moves it to its crashed list and stops binding it, but
         * leaves the name in the setting — so a check on the name alone says
         * "on" while every screen read fails.
         */
        fun isRunning(context: Context): Boolean =
            isEnabled(context) && masterSwitchOn(context) && isConnected

        private fun masterSwitchOn(context: Context): Boolean =
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1

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
