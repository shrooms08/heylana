package xyz.heylana.app.brain

import org.sol4k.Base58

/**
 * Addresses as people should see and hear them: never all 44 characters.
 *
 * The one formatter for everything that reaches the strip or the voice. Any base58
 * run of 32 to 44 characters becomes its first four, an ellipsis and its last
 * four; .skr and .sol names are kept whole.
 */
object AddressText {

    private const val B58 = "1-9A-HJ-NP-Za-km-z"

    /** A full address, but not the label of a .skr or .sol name. */
    private val FULL = Regex("(?<![$B58])[$B58]{32,44}(?![$B58])(?!\\.(?i:skr|sol)(?![\\p{L}\\p{N}]))")

    /** A shortened address as wallets print it: 7c2y…SxSv, or with three dots. */
    private val SHORT = Regex("(?<![$B58])([$B58]{4,8})(?:…|\\.\\.\\.)([$B58]{4,8})(?![$B58])")

    fun short(address: String): String =
        if (address.length > SHORT_LENGTH) "${address.take(ENDS)}…${address.takeLast(ENDS)}" else address

    /** Every full address in [text], shortened. Nothing else changes. */
    fun shorten(text: String): String = FULL.replace(text) { short(it.value) }

    /** Full addresses in [text] that really are 32-byte keys, in order, each once. */
    fun fullAddresses(text: String): List<String> =
        FULL.findAll(text).map { it.value }.filter { isKey(it) }.distinct().toList()

    /** Shortened addresses in [text], always written first…last. */
    fun shortAddresses(text: String): List<String> =
        SHORT.findAll(text).map { "${it.groupValues[1]}…${it.groupValues[2]}" }.distinct().toList()

    /** Whether a shortened address could be this full one. */
    fun matches(shortForm: String, full: String): Boolean {
        val parts = shortForm.split("…", "...")
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return false
        return full.length >= parts[0].length + parts[1].length &&
            full.startsWith(parts[0]) && full.endsWith(parts[1])
    }

    fun isKey(candidate: String): Boolean =
        runCatching { Base58.decode(candidate).size == KEY_BYTES }.getOrDefault(false)

    private const val ENDS = 4
    private const val SHORT_LENGTH = 10
    private const val KEY_BYTES = 32
}

/**
 * The full addresses the user typed or pasted since the buddy started, so one of
 * them can be recognised later when a wallet shows it shortened. In memory only,
 * gone when the buddy stops.
 */
class TypedAddresses(private val keep: Int = 20) {

    private val seen = ArrayDeque<String>()

    fun record(text: String) {
        for (address in AddressText.fullAddresses(text)) {
            seen.remove(address)
            seen.addLast(address)
            while (seen.size > keep) seen.removeFirst()
        }
    }

    fun all(): List<String> = seen.toList()

    fun clear() = seen.clear()
}
