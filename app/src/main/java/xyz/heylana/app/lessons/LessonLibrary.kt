package xyz.heylana.app.lessons

import android.content.Context
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.brain.ProxyClient
import xyz.heylana.app.memory.MemoryDesk
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.wallet.Answer
import xyz.heylana.app.wallet.WalletApi

/**
 * The curriculum: `skills/lessons/` at the top of the repo, packed into the APK's assets
 * with the skills (the skills list reads only the top of that folder, so lessons never
 * appear there). Read once, in the order of [ORDER] within each track.
 */
class LessonLibrary(private val context: Context) {

    val notes: List<LessonNote> by lazy {
        val names = runCatching { context.assets.list(DIR) }.getOrNull()?.filter { it.endsWith(".md") }.orEmpty()
        val parsed = names.mapNotNull { name ->
            val text = runCatching { context.assets.open("$DIR/$name").bufferedReader().use { it.readText() } }.getOrNull()
                ?: return@mapNotNull null
            when (val result = LessonNote.parse(text)) {
                is LessonNote.Companion.Parsed.Ok -> result.note
                is LessonNote.Companion.Parsed.Bad -> null.also { HeylanaLog.state("lesson: unreadable file=$name reason=${result.reason}") }
            }
        }
        HeylanaLog.state("lesson: library notes=${parsed.size}")
        sorted(parsed)
    }

    fun track(track: String): List<LessonNote> = notes.filter { it.track == track }

    companion object {
        const val DIR = "lessons"

        /** The order the topic list shows, Build first: from the ground up. */
        val ORDER = listOf(
            "account-model", "transactions", "programs-cpi", "pdas", "rent-fees", "tokens-atas",
            "wallets-seed-vault", "mobile-wallet-adapter", "dapp-store", "anchor",
            "validators-slots", "poh-tower-bft", "turbine-gulf-stream", "sealevel", "fees-compute",
            "rpc", "state-snapshots", "agave-firedancer", "staking", "token-programs", "solana-mobile-stack"
        )

        fun sorted(notes: List<LessonNote>): List<LessonNote> =
            notes.sortedWith(compareBy({ ORDER.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.id }))
    }
}

/**
 * A lesson wired to the proxy (quick model) and to memory: the finished lesson is kept as
 * skill_progress only with a wallet and memory switched on; otherwise nothing is sent.
 */
fun lessonFor(note: LessonNote, brain: ProxyClient, settings: HeylanaSettings, api: WalletApi): Lesson =
    Lesson(
        note = note,
        ask = { message, step -> brain.lessonTurn(message, note.id, step) },
        keep = { content ->
            if (settings.walletSession == null || !settings.memoryOn) {
                HeylanaLog.state("lesson: progress not kept why=${if (settings.walletSession == null) "no_wallet" else "memory_off"}")
            } else {
                val answer = api.remember(MemoryDesk.SKILL_PROGRESS, content, MemoryDesk.INFERRED, null, "lesson")
                HeylanaLog.state(
                    "lesson: progress kept=${answer is Answer.Ok}" + if (answer is Answer.Refused) " reason=${answer.reason}" else ""
                )
            }
        },
        log = { HeylanaLog.state(it) }
    )
