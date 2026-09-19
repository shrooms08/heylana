package xyz.heylana.app.voice

/**
 * Three pairs of ears, one answer.
 *
 * All start at the long press, every time: the phone's own recogniser, which is always
 * there, and the two cloud ears — Deepgram and AssemblyAI — which hear names like Kamino
 * properly but depend on a borrowed key and a socket each. Running them all means there is
 * always something listening.
 *
 * The rule, once the user lets go:
 *
 *  - A cloud ear's words win if they arrive within [PREFER_DEEPGRAM_MS] of the release. The
 *    first cloud final waits up to [COMPARE_MS] for the other cloud ear's; with both in
 *    hand, the higher confidence wins (a tie goes to the first). The other cloud ear known
 *    to have nothing, the first is used at once.
 *  - Otherwise the phone's words are used — as soon as every cloud ear is known to have
 *    nothing, or once that window has passed.
 *  - If no ear heard a word, it was nothing heard; if the phone's recogniser reported a
 *    real problem and the cloud has nothing either, that problem.
 *  - Nothing is decided before the release, and nothing waits past [GIVE_UP_MS] after it.
 *
 * Only the ears in [racing] take part; one left out counts as having nothing. With
 * Deepgram and the phone alone it is the old two-ear race exactly.
 *
 * Kept free of Android so every ordering can be tested.
 */
class EarsRace(
    private val preferDeepgramMs: Long = PREFER_DEEPGRAM_MS,
    private val giveUpMs: Long = GIVE_UP_MS,
    private val racing: Set<Ear> = setOf(Ear.DEEPGRAM, Ear.ANDROID),
    private val compareMs: Long = COMPARE_MS
) {

    enum class Ear { DEEPGRAM, ASSEMBLYAI, ANDROID }

    sealed interface Verdict {
        /** Not yet. */
        data object Wait : Verdict

        /** Already decided; later reports are ignored. */
        data object Settled : Verdict

        /** Use these words. [reason] says why this ear won, for the log. */
        data class Use(val ear: Ear, val text: String, val reason: String, val confidence: Float? = null) : Verdict

        /** No ear heard a word. */
        data object NothingHeard : Verdict

        /** Something went wrong that the user should be told about. */
        data class Problem(val message: String) : Verdict
    }

    private class Report {
        var done = false
        var words: String? = null
        var failure: String? = null
        var confidence: Float? = null
        var at: Long? = null
    }

    private val reports = Ear.entries.associateWith { ear ->
        Report().apply { if (ear !in racing) { done = true; failure = "not_racing" } }
    }

    private val cloud = listOf(Ear.DEEPGRAM, Ear.ASSEMBLYAI).filter { it in racing }

    private var releasedAt: Long? = null

    var settled = false
        private set

    /** The user let go at [at]. */
    fun released(at: Long): Verdict {
        if (releasedAt == null) releasedAt = at
        return decide(at)
    }

    /** [ear] has its final words, and how sure it was. Empty words count as nothing heard. */
    fun heard(ear: Ear, at: Long, words: String, confidence: Float? = null): Verdict {
        report(ear).apply {
            done = true
            this.words = words.trim().ifEmpty { null }
            this.confidence = confidence
            this.at = at
        }
        return decide(at)
    }

    /** [ear] finished and heard nothing. */
    fun nothing(ear: Ear, at: Long): Verdict {
        report(ear).apply {
            done = true
            this.at = at
        }
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
    val deepgramFailure: String? get() = reports.getValue(Ear.DEEPGRAM).failure

    /**
     * Every ear's result for the log: confidence and milliseconds after the release, or why
     * it had nothing. "deepgram=0.91/820ms assemblyai=0.95/640ms android=words/1200ms".
     */
    fun summary(): String = Ear.entries.filter { it in racing }.joinToString(" ") { ear ->
        val r = report(ear)
        val ms = r.at?.let { at -> releasedAt?.let { maxOf(0L, at - it) } }
        val what = when {
            r.words != null -> r.confidence?.let { "%.2f".format(it) } ?: "words"
            r.failure != null -> "out:${r.failure}"
            r.done -> "nothing"
            else -> "pending"
        }
        "${ear.name.lowercase()}=$what${ms?.let { "/${it}ms" } ?: ""}"
    }

    /** Debug builds only, for the ears capture: what [ear] heard, if anything. */
    fun wordsOf(ear: Ear): String? = report(ear).words

    private fun report(ear: Ear) = reports.getValue(ear)

    private fun decide(at: Long): Verdict {
        if (settled) return Verdict.Settled
        val released = releasedAt ?: return Verdict.Wait
        val since = at - released

        val cloudWords = cloud.filter { report(it).words != null }
        val android = report(Ear.ANDROID)

        if (cloudWords.isNotEmpty() && since <= preferDeepgramMs) {
            val waiting = cloud.filter { !report(it).done }
            val first = cloudWords.minBy { report(it).at ?: Long.MAX_VALUE }
            val firstAt = report(first).at ?: at
            // The other cloud ear may be a moment behind: its words are worth a short wait.
            if (waiting.isNotEmpty() && at - firstAt < compareMs) return Verdict.Wait
            return settle(useCloud(cloudWords, inTime = true))
        }
        val cloudDone = cloud.all { report(it).done }
        val androidWords = android.words
        if (androidWords != null && (since >= preferDeepgramMs || cloudDone)) {
            return settle(Verdict.Use(Ear.ANDROID, androidWords, androidReason(since), android.confidence))
        }
        if (cloudWords.isNotEmpty()) {
            // Late, but the phone has nothing to offer instead.
            return settle(useCloud(cloudWords, inTime = false))
        }
        if (Ear.entries.all { report(it).done }) return settle(nobodyHeard())
        if (since >= giveUpMs) return settle(nobodyHeard())
        return Verdict.Wait
    }

    /** The cloud ear to use among those with words: the more confident, or the first on a tie. */
    private fun useCloud(withWords: List<Ear>, inTime: Boolean): Verdict.Use {
        val ranked = withWords.sortedWith(
            compareByDescending<Ear> { report(it).confidence ?: -1f }.thenBy { report(it).at ?: Long.MAX_VALUE }
        )
        val pick = ranked.first()
        val r = report(pick)
        val name = pick.name.lowercase()
        val reason = when {
            withWords.size > 1 -> if ((report(ranked[0]).confidence ?: -1f) > (report(ranked[1]).confidence ?: -1f))
                "${name}_higher_confidence" else "${name}_first_on_tie"
            !inTime -> "${name}_late_only_words"
            else -> "${name}_in_time"
        }
        return Verdict.Use(pick, r.words!!, reason, r.confidence)
    }

    private fun androidReason(since: Long): String {
        val failed = cloud.firstOrNull { report(it).failure != null }
        return when {
            cloud.isEmpty() -> "only_ear"
            failed != null && cloud.all { report(it).done } -> "${failed.name.lowercase()}_failed_${report(failed).failure}"
            cloud.all { report(it).done } -> "${cloud.joinToString("_") { it.name.lowercase() }}_heard_nothing"
            else -> "${cloud.joinToString("_") { it.name.lowercase() }}_late"
        }
    }

    private fun nobodyHeard(): Verdict {
        val problem = report(Ear.ANDROID).failure?.takeUnless { it == "not_racing" }
        return if (problem != null) Verdict.Problem(problem) else Verdict.NothingHeard
    }

    private fun settle(verdict: Verdict): Verdict {
        settled = true
        return verdict
    }

    companion object {
        /**
         * How long after the release a cloud ear's words are still preferred: the 400ms of
         * trailing audio plus the 1500ms each gets to answer its end-of-speech request, and a
         * little room for the answer to travel.
         */
        const val PREFER_DEEPGRAM_MS = 2_000L

        /** How long the first cloud final waits for the other cloud ear's, to compare. */
        const val COMPARE_MS = 400L

        /** How long after the release anything is waited for at all. */
        const val GIVE_UP_MS = 4_000L
    }
}
