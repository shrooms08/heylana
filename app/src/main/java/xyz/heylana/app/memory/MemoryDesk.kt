package xyz.heylana.app.memory

import xyz.heylana.app.HeylanaLog
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
 * Where "remember that…", a preference and the yes that keeps it are handled, before
 * anything goes to the model — none of it costs a question. Shared by the buddy and the
 * app's own chat.
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
    private val save: suspend (category: String, content: String, consent: String, said: String?) -> Answer<Unit>,
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

    private suspend fun keep(category: String, content: String, consent: String, said: String?): String =
        when (val answer = save(category, content, consent, said)) {
            is Answer.Ok -> {
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
    }
}

/** A desk wired to this phone's settings and the worker. */
fun memoryDeskFor(settings: xyz.heylana.app.settings.HeylanaSettings, api: xyz.heylana.app.wallet.WalletApi, source: String) =
    MemoryDesk(
        memoryOn = { settings.memoryOn },
        hasWallet = { settings.walletSession != null },
        offeredBefore = { it in settings.memoryOffered },
        markOffered = { settings.memoryOffered = settings.memoryOffered + it },
        save = { category, content, consent, said -> api.remember(category, content, consent, said, source) }
    )
