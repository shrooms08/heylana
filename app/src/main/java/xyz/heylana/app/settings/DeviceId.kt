package xyz.heylana.app.settings

import java.util.UUID

/**
 * The one thing the proxy knows about a phone: a random id made when Heylana is
 * first installed and kept until it is uninstalled.
 *
 * It is not a person and it is not a login. It exists so a runaway app cannot
 * spend the whole budget in an afternoon, and it is deliberately the only thing
 * that goes up with a request that could tell two phones apart.
 *
 * The store is passed in so this can be tested without Android.
 */
object DeviceId {

    fun readOrCreate(read: () -> String?, write: (String) -> Unit): String {
        val existing = read()?.trim().orEmpty()
        if (looksLikeOne(existing)) return existing
        val made = UUID.randomUUID().toString()
        write(made)
        return made
    }

    /** The same shape the worker insists on, so a bad one never leaves. */
    fun looksLikeOne(value: String): Boolean =
        value.length in 16..64 && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '-' }
}
