package xyz.heylana.app.voice

/**
 * How long it takes Heylana to start speaking, from the moment the user lets go.
 *
 * Four stretches, each measured where it really happens:
 *
 *  - **ears**: the release until the words are in hand (the trailing audio, the finalize
 *    wait, and the race between the ears).
 *  - **brain**: the question going out until the answer is back.
 *  - **tts first byte**: asking for the voice until the first audio arrives.
 *  - **play**: that first audio until the speaker actually starts (the pre-roll).
 *
 * `total` is the release to the first sound: what the user feels. Times only — no words, no
 * question, no answer. Kept free of Android so the arithmetic is tested on the JVM.
 */
class AnswerClock(private val releasedAt: Long) {

    private var heardAt = 0L
    private var askedAt = 0L
    private var answeredAt = 0L
    private var voiceAskedAt = 0L
    private var firstAudioAt = 0L
    private var spokeAt = 0L

    /** The ears are done: these are the words that won. */
    fun heard(at: Long) {
        if (heardAt == 0L) heardAt = at
    }

    /** The question is on its way. */
    fun asked(at: Long) {
        if (askedAt == 0L) askedAt = at
    }

    /** The answer is back. */
    fun answered(at: Long) {
        if (answeredAt == 0L) answeredAt = at
    }

    /** The voice was asked for. */
    fun voiceAsked(at: Long) {
        if (voiceAskedAt == 0L) voiceAskedAt = at
    }

    /**
     * The voice rode with the question itself: one trip, the worker speaking each sentence
     * as it was written. The model and the voice then overlap, so **tts_first_byte** is the
     * whole way from the question to the first sound, and **brain** runs inside it.
     */
    fun voiceRidesWithTheQuestion() {
        overlapped = true
        if (voiceAskedAt == 0L) voiceAskedAt = askedAt
    }

    /** True when the answer and its voice came back down one connection. */
    var overlapped = false
        private set

    /** Its first audio arrived. */
    fun firstAudio(at: Long) {
        if (firstAudioAt == 0L) firstAudioAt = at
    }

    /** The speaker started: this is the first spoken word. */
    fun spoke(at: Long) {
        if (spokeAt == 0L) spokeAt = at
    }

    val done: Boolean get() = spokeAt > 0L

    /**
     * Every stretch is in: the first word has been heard **and** the whole answer is back.
     * On a one-trip answer the speaker starts first, so the line waits for the reply.
     */
    val complete: Boolean get() = spokeAt > 0L && answeredAt > 0L

    private fun gap(from: Long, to: Long): Long = if (from > 0L && to >= from) to - from else -1

    val earsMs: Long get() = gap(releasedAt, heardAt)
    val brainMs: Long get() = gap(askedAt, answeredAt)
    val ttsFirstByteMs: Long get() = gap(voiceAskedAt, firstAudioAt)
    val playMs: Long get() = gap(firstAudioAt, spokeAt)
    val totalMs: Long get() = gap(releasedAt, spokeAt)

    /**
     * The line for the log: the four stretches and the total, and a "rest" for whatever fell
     * between them (handing words about, the queue, drawing). Numbers only.
     */
    fun line(): String {
        // One trip: the model runs inside the voice's stretch, so it is not counted twice.
        val stretches = if (overlapped) listOf(earsMs, ttsFirstByteMs, playMs) else listOf(earsMs, brainMs, ttsFirstByteMs, playMs)
        val measured = stretches.filter { it >= 0 }.sum()
        val rest = if (totalMs >= 0) (totalMs - measured).coerceAtLeast(0) else -1
        return "speed: ears_ms=$earsMs brain_ms=$brainMs tts_first_byte_ms=$ttsFirstByteMs " +
            "play_ms=$playMs rest_ms=$rest total_ms=$totalMs trips=${if (overlapped) "one" else "two"}"
    }

    companion object {
        /** What the whole thing should stay under, from the release to the first word. */
        const val TARGET_MS = 1500L
    }
}
