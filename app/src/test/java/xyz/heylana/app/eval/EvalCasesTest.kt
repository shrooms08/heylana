package xyz.heylana.app.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.brain.HeylanaPrompt
import xyz.heylana.app.brain.Variety
import xyz.heylana.app.lessons.LessonNote
import xyz.heylana.app.skills.SkillFile
import java.io.File

/**
 * The eval's twenty questions, built with the app's own prompt code and written to
 * `scripts/eval/cases.json`, so `scripts/eval.py` replays exactly what the phone would send
 * and cannot drift from it. Screen listings are written here in the phone's listing format,
 * from screens walked on the Seeker, with made-up balances: no real screen was recorded.
 *
 * Run the unit tests to regenerate the file; commit it with the prompt change that moved it.
 */
class EvalCasesTest {

    private val repo = listOf(File(".."), File(".")).first { File(it, "skills").isDirectory }
    private val out = File(repo, "scripts/eval/cases.json")

    /** Where this question's say is held: the app's own caps. */
    private val chatCap = 60

    private val walletSkill = SkillFile.parse(File(repo, "skills/seed-vault-wallet.md").readText(), builtIn = true)
        .let { (it as SkillFile.Parsed.Ok).skill }
    private val kaminoSkill = SkillFile.parse(File(repo, "skills/kamino.md").readText(), builtIn = true)
        .let { (it as SkillFile.Parsed.Ok).skill }
    private val pdas = (LessonNote.parse(File(repo, "skills/lessons/pdas.md").readText()) as LessonNote.Companion.Parsed.Ok).note

    private val now = "It is now Sat 19 Sep 2026, 10:00 where the user is (UTC+01:00), 09:00 UTC."

    private val walletHome = """
        App: Seed Vault Wallet (com.solanamobile.wallet)
        [1] Text "Balance"
        [2] Text "${'$'}1,234.56"
        [3] Button tap "Swap"
        [4] Button tap "Receive"
        [5] Button tap "Send"
        [6] Text "Earn"
        [7] Text "Earn 4.06% on your USDC"
        [8] Button tap "Start"
        [9] Text "Tokens"
        [10] Text "Solana"
        [11] Text "2.5 SOL"
        [12] Text "${'$'}1,234.50"
        [13] Button tap "View all"
    """.trimIndent()

    private val swapForm = """
        App: Seed Vault Wallet (com.solanamobile.wallet)
        [1] Text "Swap"
        [2] Text "Sell"
        [3] Text "0"
        [4] Button tap "SOL"
        [5] Text "Balance: 2.5"
        [6] Text "Buy"
        [7] Button tap "USDC"
        [8] Button tap "25%"
        [9] Button tap "50%"
        [10] Button tap "75%"
        [11] Button tap "Max"
        [12] Button tap "Swap"
    """.trimIndent()

    private val kaminoDeposit = """
        App: Seed Vault Wallet (com.solanamobile.wallet)
        [1] Text "Deposit"
        [2] Text "${'$'}0"
        [3] Text "Available"
        [4] Text "${'$'}12.00"
        [5] Text "Earn APY"
        [6] Text "4.06%"
        [7] Text "By clicking \"Continue\", I acknowledge the USDC Earn Vault Disclosures"
        [8] Button tap "Continue"
    """.trimIndent()

    private val docsPage = """
        App: Chrome (com.android.chrome)
        [1] EditText tap type "solana.com/docs/core/pda"
        [2] Text "Program Derived Addresses (PDAs)"
        [3] Text "Program Derived Addresses (PDAs) are 32-byte account addresses that are deterministically derived from a program ID and a set of seeds."
        [4] Text "Only the program whose ID was used in the derivation can sign for a PDA."
    """.trimIndent()

    private val treasury = "7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv"

    private data class Case(val id: String, val kind: String, val mode: String, val system: String, val user: String,
                            val extra: Map<String, Any?> = emptyMap(), val expect: Map<String, Any?>)

    private fun chat(id: String, question: String, seed: String? = null, expect: Map<String, Any?> = emptyMap()) = Case(
        id, "chat", "quick", HeylanaPrompt.system(solana = false),
        HeylanaPrompt.chatMessage(question, now = now, seed = seed),
        mapOf("said" to question), mapOf("cap" to chatCap) + expect
    )

    private fun screen(id: String, question: String, listing: String, ids: Int, wallet: Boolean, lens: String? = null,
                       skill: xyz.heylana.app.skills.Skill? = walletSkill, mode: String = "task") = Case(
        id, "screen", mode, HeylanaPrompt.system(solana = skill != null, skill = skill),
        HeylanaPrompt.userMessage(listing, question, lens = lens),
        // No lookups here, so every number in the answer has to come off the screen.
        mapOf("said" to question),
        mapOf("cap" to chatCap, "ids" to (1..ids).toList(), "numbers_from" to if (wallet) listing else null)
    )

    private fun send(id: String, question: String, to: String, amount: String?, token: String) = Case(
        id, "send", "task", HeylanaPrompt.system(solana = true),
        HeylanaPrompt.userMessage(walletHome, question),
        mapOf("said" to question, "tools" to true, "intent" to "send"),
        mapOf("to" to to, "amount" to amount, "token" to token)
    )

    private fun lesson(id: String, instruction: String) = pdas.slices().let { slices ->
        Case(
            id, "lesson", "quick", HeylanaPrompt.LESSON_SYSTEM,
            HeylanaPrompt.lessonMessage(pdas, 1, slices.size, slices[0], slices.getOrNull(1), instruction),
            emptyMap(), mapOf("cap" to 39)
        )
    }

    private fun cases(): List<Case> = listOf(
        chat("chat-who-made", "who made you", expect = mapOf("mentions" to listOf("Minos"))),
        chat("chat-solana-mobile", "are you from Solana Mobile", expect = mapOf("mentions" to listOf("Minos"))),
        chat("chat-joke", "tell me a joke", seed = "lantern"),
        chat("chat-how-are-you", "how are you"),
        chat("chat-capital", "what's the capital of Nigeria", expect = mapOf("mentions" to listOf("Abuja"))),
        chat("chat-tokyo-time", "what's the time in Tokyo", expect = mapOf("mentions_any" to listOf("6", "18"))),
        chat("chat-fun-fact", "tell me a fun fact", seed = "compass"),
        chat("chat-opinion", "what do you think of pineapple on pizza"),
        chat("chat-world-cup", "who won the 2022 World Cup", expect = mapOf("mentions" to listOf("Argentina"))),
        chat("chat-greeting", "good morning"),
        screen("screen-balance", "what's my balance", walletHome, 13, wallet = true),
        screen("screen-where-swap", "where do I swap", walletHome, 13, wallet = false),
        screen("screen-max", "what does Max do", swapForm, 12, wallet = true),
        screen("screen-kamino-apy", "what is Earn APY here", kaminoDeposit, 8, wallet = true, skill = kaminoSkill),
        screen("screen-docs-explain", "explain this", docsPage, 4, wallet = false, lens = HeylanaPrompt.DOCS_EXPLAIN_LINE,
            skill = null, mode = "quick"),
        send("send-sol", "send 0.001 SOL to $treasury", treasury, "0.001", "SOL"),
        send("send-usdc", "send 0.01 USDC to $treasury", treasury, "0.01", "USDC"),
        send("send-screen-address", "send 0.002 SOL to $treasury please", treasury, "0.002", "SOL"),
        lesson("lesson-teach", HeylanaPrompt.LESSON_TEACH),
        lesson("lesson-answer", HeylanaPrompt.lessonAnswer("What is a PDA made from?", "seeds and the program id", last = false))
    )

    @Test
    fun `twenty cases, written where the eval reads them`() {
        val all = cases()
        assertEquals(20, all.size)
        assertEquals(listOf(10, 5, 3, 2), listOf("chat", "screen", "send", "lesson").map { k -> all.count { it.kind == k } })
        // The chat seeds are the app's own sentence, not a stand-in.
        assertTrue(all[2].user.contains(Variety.line("lantern")))
        out.parentFile.mkdirs()
        out.writeText(json(all.map { it.toMap() }) + "\n")
        assertTrue(out.readText().contains("\"id\": \"lesson-answer\""))
    }

    private fun Case.toMap(): Map<String, Any?> = mapOf(
        "id" to id, "kind" to kind,
        "body" to mapOf("mode" to mode, "max_tokens" to 300, "system" to system,
            "messages" to listOf(mapOf("role" to "user", "content" to user))) + extra,
        "expect" to expect
    )

    /** org.json is a stub in unit tests, so a small writer: maps, lists, strings, numbers, booleans, null. */
    private fun json(value: Any?, indent: String = ""): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (c in value) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
            "$indent  ${json(k.toString())}: ${json(v, "$indent  ")}"
        }
        is List<*> -> if (value.isEmpty()) "[]" else value.joinToString(",\n", "[\n", "\n$indent]") { "$indent  ${json(it, "$indent  ")}" }
        else -> json(value.toString())
    }
}
