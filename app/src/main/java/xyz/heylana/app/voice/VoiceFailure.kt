package xyz.heylana.app.voice

/**
 * Why Heylana stayed silent, as `voice_failed reason=…` logs it. There is no second
 * voice: whatever the reason, the answer is shown as text instead.
 */
object VoiceFailure {

    /** The worker's own daily cap for this device. */
    const val DAILY_CAP = "429 daily_cap"

    /** The voice provider's quota or rate limit, passed on by the worker. */
    const val QUOTA = "429 quota"

    /** No audio within [FIRST_AUDIO_MS], or the stream stalled that long. */
    const val TIMEOUT = "timeout"

    /** Anything else: a refused or broken request, or a reply with no audio in it. */
    const val ERROR = "error"

    /** How long the first audio (and each read after it) may take before Heylana gives up. */
    const val FIRST_AUDIO_MS = 6_000L

    /**
     * The reason for a refused /tts: the worker's status and its `reason`, as it gave them
     * ("429 daily_cap", "429 quota", "502 upstream"), so the log shows exactly what the
     * worker said.
     */
    fun reasonFor(code: Int, refusal: String?): String =
        "$code ${refusal?.trim()?.takeIf { it.isNotEmpty() } ?: "none"}"

    /** The device's daily allowance of spoken answers is used up. */
    fun isDailyCap(reason: String): Boolean = reason == DAILY_CAP

    /** Over a limit: the day's cap, or the voice provider's quota. The silence then gets a reason. */
    fun isOverLimit(reason: String): Boolean = reason == DAILY_CAP || reason == QUOTA

    /** Shown under the answer when the voice is over its limit, so the silence has a reason. */
    const val OVER_LIMIT_LINE = xyz.heylana.app.brain.PlainError.VOICE_LIMIT
}
