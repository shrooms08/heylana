package xyz.heylana.app

/**
 * Parts of the product that are built but switched off. Each can come back by flipping its
 * flag; nothing else has to change.
 */
object Features {
    /**
     * The Skill market: the menu's row, the market and old Skills screens (switches, Get
     * more, Install, Remove, the "n of n active" counters) and the public index download.
     * Off: on the roadmap. Built-in skills load exactly as before, invisibly, whatever this is.
     */
    const val SKILL_MARKET = false
}
