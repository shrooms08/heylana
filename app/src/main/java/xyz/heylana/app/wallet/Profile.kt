package xyz.heylana.app.wallet

/**
 * What Heylana calls the user, kept by the worker against their wallet.
 *
 * [name] is what the wallet is known as — its Seeker ID (.skr) name when one
 * could be found, otherwise empty. [callMe] is what the user chose.
 */
data class Profile(val name: String, val callMe: String) {

    /** What the name sheet starts with: their choice, else their .skr name, else nothing. */
    val suggestion: String get() = callMe.ifBlank { name }
}

const val MAX_NAME = 40

/**
 * One short line of plain text. The name goes into what the model is told, so
 * no line breaks, no control characters, and never more than [MAX_NAME].
 */
fun cleanName(raw: String): String =
    raw.filterNot { it.isISOControl() && it != '\n' && it != '\t' }
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_NAME)
        .trim()
