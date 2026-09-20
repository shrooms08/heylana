package xyz.heylana.app.home

/** What the system says right now about the four things Heylana asks for. */
data class PermissionState(
    val overlay: Boolean = false,
    val screenReading: Boolean = false,
    val notifications: Boolean = false,
    val microphone: Boolean = false
)

/** Which system page a row opens. */
enum class PermissionTarget { OVERLAY, SCREEN_READING, NOTIFICATIONS, MICROPHONE }

/** One row: what it is, why Heylana wants it (one line), and whether it is on. */
data class PermissionRow(val target: PermissionTarget, val title: String, val why: String, val on: Boolean, val optional: Boolean)

/**
 * The permissions screen's rows, from the system's answer. The screen calls [refresh] on
 * every resume — the user comes back from a system page having changed something — so a
 * row is never stale.
 */
class PermissionsModel(private val check: () -> PermissionState) {

    var state: PermissionState = check()
        private set

    fun refresh(): PermissionState {
        state = check()
        return state
    }

    val rows: List<PermissionRow> get() = rowsFor(state)

    /** The three Heylana cannot work without are on (the microphone is optional). */
    val required: Boolean get() = state.overlay && state.screenReading && state.notifications

    companion object {
        const val OVERLAY_WHY = "So the buddy can float over your apps."
        const val SCREEN_READING_WHY = "Reads your screen when you ask, and on signing screens."
        const val NOTIFICATIONS_WHY = "Keeps the buddy running, with a notice you can always see."
        const val MICROPHONE_WHY = "Hold the buddy and talk. Optional: typing works too."

        fun rowsFor(state: PermissionState): List<PermissionRow> = listOf(
            PermissionRow(PermissionTarget.OVERLAY, "Show over other apps", OVERLAY_WHY, state.overlay, optional = false),
            PermissionRow(PermissionTarget.SCREEN_READING, "Screen reading", SCREEN_READING_WHY, state.screenReading, optional = false),
            PermissionRow(PermissionTarget.NOTIFICATIONS, "Notifications", NOTIFICATIONS_WHY, state.notifications, optional = false),
            PermissionRow(PermissionTarget.MICROPHONE, "Microphone", MICROPHONE_WHY, state.microphone, optional = true)
        )
    }
}
