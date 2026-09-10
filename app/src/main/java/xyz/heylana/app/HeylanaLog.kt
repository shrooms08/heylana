package xyz.heylana.app

import android.util.Log

/**
 * Debug-only trace of what Heylana is doing, so a gesture that goes wrong can be
 * read back off the phone instead of guessed at:
 *
 * ```
 * adb logcat -s HeylanaState
 * ```
 *
 * **Names of things that happened, and nothing else.** No screen contents, no
 * transcript, no answer, no key — the same rule as everywhere else, and the
 * reason this takes a label rather than a message. Release builds print nothing.
 */
object HeylanaLog {

    const val STATE_TAG = "HeylanaState"

    // Info rather than debug: this phone drops app debug lines from logcat
    // entirely, and a trace nobody can read is no use.
    fun state(label: String) {
        if (BuildConfig.DEBUG) Log.i(STATE_TAG, label)
    }
}
