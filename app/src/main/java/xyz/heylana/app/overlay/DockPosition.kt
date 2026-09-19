package xyz.heylana.app.overlay

/**
 * Where the docked disc's window really ends up. The dock inset is measured to the
 * visible disc, so with the bloom room around it the wanted position runs past the
 * screen edge. The docked window may hang off the edge by [overhang] — its own
 * transparent bloom margin, with `FLAG_LAYOUT_NO_LIMITS` — and no further; the rest is
 * clamped. A flight has to aim at the clamped position, or it lands off the edge and
 * the window then jumps back: two movements instead of one.
 */
object DockPosition {

    /** The window's left (or top) for a [wanted] one, a view of [viewSize] in [usable] px. */
    fun clamped(wanted: Int, viewSize: Int, usable: Int, overhang: Int = 0): Int {
        val max = usable - viewSize
        return if (max < 0) 0 else wanted.coerceIn(-overhang, max + overhang)
    }

    /** The docked left on either side, given the (possibly negative) inset to the visible disc. */
    fun dockLeft(onLeft: Boolean, inset: Int, viewSize: Int, usableWidth: Int, overhang: Int = 0): Int =
        clamped(if (onLeft) inset else usableWidth - viewSize - inset, viewSize, usableWidth, overhang)
}
