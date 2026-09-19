package xyz.heylana.app.memory

import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.actions.QuickActions
import xyz.heylana.app.brain.Routing
import xyz.heylana.app.wallet.Answer

/** One kept line, as the Memory screen lists it. */
data class MemoryRecord(
    val id: String,
    val category: String,
    val content: String,
    val consent: String,
    val created: String
)

data class MemoryState(val on: Boolean, val records: List<MemoryRecord>)

/**
 * Where "remember that…", a preference and the yes that keeps it, and "forget that", are
 * handled before anything goes to the model — none of it costs a question. Shared by the
 * buddy and the app's own chat. A fact the user states about themselves ("I use Jupiter for
 * swaps") is kept by [autoSave] alongside the question, which still goes on as usual.
 *
 * [handle] returns the line to say, or null when the words are not about memory and the
 * question should go on as usual.
 */
class MemoryDesk(
    private val memoryOn: () -> Boolean,
    private val hasWallet: () -> Boolean,
    /** Whether a preference has been offered on this phone before: each is offered once. */
    private val offeredBefore: (String) -> Boolean,
    private val markOffered: (String) -> Unit,
    /** Keeps one line; the answer is the new record's id. */
    private val save: suspend (category: String, content: String, consent: String, said: String?) -> Answer<String?>,
    private val forget: suspend (id: String) -> Answer<Unit> = { Answer.Ok(Unit) },
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { HeylanaLog.state(it) }
) {
    private var pending: String? = null
    private var pendingAt = 0L

    /**
     * Whether [text] is a memory matter, decided at once and without the network: "remember
     * that…", a yes or no to an offer, or a preference that can still be offered. An offer
     * not answered by the next words lapses here.
     */
    fun claims(text: String): Boolean {
        val offered = pending?.takeIf { now() - pendingAt < PENDING_MS }
        if (offered != null && (MemoryWords.isYes(text) || MemoryWords.isNo(text))) return true
        pending = null
        if (MemoryWords.isForget(text)) return true
        if (MemoryWords.explicit(text) != null) return true
        val preference = MemoryWords.inferred(text) ?: return false
        return hasWallet() && memoryOn() && !offeredBefore(preference)
    }

    suspend fun handle(text: String): String? {
        val offered = pending?.takeIf { now() - pendingAt < PENDING_MS }
        pending = null
        if (offered != null) {
            when {
                MemoryWords.isYes(text) -> return keep(PREFERENCE, offered, INFERRED, said = null)
                MemoryWords.isNo(text) -> {
                    log("memory: offer declined")
                    return MemoryWords.DECLINED
                }
            }
        }

        if (MemoryWords.isForget(text)) return forgetLast()

        MemoryWords.explicit(text)?.let { content ->
            if (!hasWallet()) return MemoryWords.NO_WALLET
            if (!memoryOn()) return MemoryWords.OFF
            return keep(FACT, content, EXPLICIT, said = text)
        }

        val preference = MemoryWords.inferred(text) ?: return null
        if (!hasWallet() || !memoryOn() || offeredBefore(preference)) return null
        markOffered(preference)
        pending = preference
        pendingAt = now()
        log("memory: offered a preference")
        return MemoryWords.OFFER
    }

    /**
     * A fact the user just stated about themselves, kept without being asked while memory is
     * on: true when a line was kept (the strip then shows "Remembered" for two seconds). Never
     * for an action, a message or a send, whose words are not about the user.
     */
    suspend fun autoSave(text: String): Boolean {
        if (!hasWallet() || !memoryOn()) return false
        if (QuickActions.isQuickAction(text) || QuickActions.isMessage(text) || Routing.isSendQuestion(text)) return false
        val fact = MemoryWords.aboutUser(text) ?: return false
        return when (val answer = save(FACT, fact, EXPLICIT, text)) {
            is Answer.Ok -> {
                answer.value?.let { lastSaved = Saved(it, now()) }
                log("memory: auto-saved category=$FACT chars=${fact.length}")
                true
            }
            is Answer.Refused -> false.also { log("memory: auto-save refused reason=${answer.reason}") }
            is Answer.Unreachable -> false.also { log("memory: auto-save failed") }
        }
    }

    /** "Forget that": the line kept last, on this phone, in the last ten minutes. */
    private suspend fun forgetLast(): String {
        val last = lastSaved?.takeIf { now() - it.at < FORGET_WINDOW_MS }
        if (last == null || !hasWallet()) {
            log("memory: forget nothing_recent")
            return MemoryWords.NOTHING_TO_FORGET
        }
        return when (forget(last.id)) {
            is Answer.Ok -> {
                lastSaved = null
                log("memory: forgot the last line")
                MemoryWords.FORGOTTEN
            }
            else -> MemoryWords.FAILED.also { log("memory: forget failed") }
        }
    }

    private suspend fun keep(category: String, content: String, consent: String, said: String?): String =
        when (val answer = save(category, content, consent, said)) {
            is Answer.Ok -> {
                answer.value?.let { lastSaved = Saved(it, now()) }
                log("memory: saved category=$category consent=$consent")
                MemoryWords.SAVED
            }
            is Answer.Refused -> {
                log("memory: refused reason=${answer.reason}")
                if (answer.reason == "memory_off") MemoryWords.OFF else MemoryWords.REFUSED
            }
            is Answer.Unreachable -> MemoryWords.FAILED
        }

    companion object {
        const val FACT = "fact"
        const val PREFERENCE = "preference"
        const val SKILL_PROGRESS = "skill_progress"
        const val EXPLICIT = "explicit"
        const val INFERRED = "inferred"

        /** An offer is answered within a minute, or it lapses. */
        const val PENDING_MS = 60_000L

        /** How long "forget that" still means the line just kept. */
        const val FORGET_WINDOW_MS = 10 * 60_000L

        private data class Saved(val id: String, val at: Long)

        /** The line kept last, by the buddy or the app: one phone, one "that". Memory only, never stored. */
        @Volatile
        private var lastSaved: Saved? = null

        /** For tests: forget what was kept last. */
        fun clearLastSaved() {
            lastSaved = null
        }
    }
}

/** A desk wired to this phone's settings and the worker. */
fun memoryDeskFor(settings: xyz.heylana.app.settings.HeylanaSettings, api: xyz.heylana.app.wallet.WalletApi, source: String) =
    MemoryDesk(
        memoryOn = { settings.memoryOn },
        hasWallet = { settings.walletSession != null },
        offeredBefore = { it in settings.memoryOffered },
        markOffered = { settings.memoryOffered = settings.memoryOffered + it },
        save = { category, content, consent, said -> api.remember(category, content, consent, said, source) },
        forget = { id ->
            when (val answer = api.forget(id)) {
                is Answer.Ok -> Answer.Ok(Unit)
                is Answer.Refused -> answer
                is Answer.Unreachable -> answer
            }
        }
    )
