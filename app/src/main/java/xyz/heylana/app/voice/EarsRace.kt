package xyz.heylana.app.voice

/**
 * Two pairs of ears, one answer.
 *
 * Both start at the long press, every time: the phone's own recogniser, which is
 * always there, and Deepgram, which hears names like Kamino properly but depends
 * on a borrowed key and a socket. Waiting to see whether Deepgram came up before
 * starting the phone's ears meant a hold with a failed socket heard nothing at
 * all. Running both means there is always something listening.
 *
 * The rule, once the user lets go:
 *
 *  - Deepgram's words win if they arrive within [PREFER_DEEPGRAM_MS] of the
 *    release.
 *  - Otherwise the phone's words are used — as soon as Deepgram is known to have
 *    nothing, or once that window has passed.
 *  - If neither heard a word, it was nothing heard; if the phone's recogniser
 *    reported a real problem and Deepgram has nothing either, that problem.
 *  - Nothing is decided before the release, and nothing waits past
 *    [GIVE_UP_MS] after it.
 *
 * Kept free of Android so every ordering can be tested.
 */
class EarsRace(
    private val preferDeepgramMs: Long = PREFER_DEEPGRAM_MS,
    private val giveUpMs: Long = GIVE_UP_MS
) {

    enum class Ear { DEEPGRAM, ANDROID }

    sealed interface Verdict {
        /** Not yet. */
        data object Wait : Verdict

        /** Already decided; later reports are ignored. */
        data object Settled : Verdict

        /** Use these words. [reason] says why this ear won, for the log. */
        data class Use(val ear: Ear, val text: String, val reason: String) : Verdict

        /** Neither ear heard a word. */
        data object NothingHeard : Verdict

        /** Something went wrong that the user should be told about. */
        data class Problem(val message: String) : Verdict
    }

    private class Report {
        var done = false
        var words: String? = null
        var failure: String? = null
    }

    private val deepgram = Report()
    private val android = Report()
    private var releasedAt: Long? = null

    var settled = false
        private set

    /** The user let go at [at]. */
    fun released(at: Long): Verdict {
        if (releasedAt == null) releasedAt = at
        return decide(at)
    }

    /** [ear] has its final words. Empty words count as nothing heard. */
    fun heard(ear: Ear, at: Long, words: String): Verdict {
        report(ear).apply {
            done = true
            this.words = words.trim().ifEmpty { null }
        }
        return decide(at)
    }

    /** [ear] finished and heard nothing. */
    fun nothing(ear: Ear, at: Long): Verdict {
        report(ear).done = true
        return decide(at)
    }

    /** [ear] cannot deliver. [why] is a reason for the log, or a message for the user. */
    fun failed(ear: Ear, at: Long, why: String): Verdict {
        report(ear).apply {
            done = true
            failure = why
        }
        return decide(at)
    }

    /** Time has passed with nothing new; the windows may have closed. */
    fun tick(at: Long): Verdict = decide(at)

    /** Why Deepgram did not win, if it did not — for the log. */
    val deepgramFailure: String? get() = deepgram.failure

    private fun report(ear: Ear) = if (ear == Ear.DEEPGRAM) deepgram else android

    private fun decide(at: Long): Verdict {
        if (settled) return Verdict.Settled
        val released = releasedAt ?: return Verdict.Wait
        val since = at - released

        val deepgramWords = deepgram.words
        val androidWords = android.words

        if (deepgramWords != null && since <= preferDeepgramMs) {
            return settle(Verdict.Use(Ear.DEEPGRAM, deepgramWords, "deepgram_in_time"))
        }
        if (androidWords != null && (since >= preferDeepgramMs || deepgram.done)) {
            return settle(Verdict.Use(Ear.ANDROID, androidWords, androidReason(since)))
        }
        if (deepgramWords != null) {
            // Late, but the phone has nothing to offer instead.
            return settle(Verdict.Use(Ear.DEEPGRAM, deepgramWords, "deepgram_late_only_words"))
        }
        if (deepgram.done && android.done) return settle(nobodyHeard())
        if (since >= giveUpMs) return settle(nobodyHeard())
        return Verdict.Wait
    }

    private fun androidReason(since: Long): String = when {
        deepgram.failure != null -> "deepgram_failed_${deepgram.failure}"
        deepgram.done -> "deepgram_heard_nothing"
        since >= preferDeepgramMs -> "deepgram_late"
        else -> "deepgram_late"
    }

    private fun nobodyHeard(): Verdict {
        val problem = android.failure
        return if (problem != null) Verdict.Problem(problem) else Verdict.NothingHeard
    }

    private fun settle(verdict: Verdict): Verdict {
        settled = true
        return verdict
    }

    companion object {
        /** How long after the release Deepgram's words are still preferred. */
        const val PREFER_DEEPGRAM_MS = 1_500L

        /** How long after the release anything is waited for at all. */
        const val GIVE_UP_MS = 4_000L
    }
}
