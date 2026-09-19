package xyz.heylana.app.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import xyz.heylana.app.net.Proxy
import xyz.heylana.app.settings.HeylanaSettings
import java.io.IOException

/** Where a wallet (or a phone without one) stands: the plan and its talks. */
data class Standing(
    val plan: String,
    val used: Int,
    /** Null means unlimited. */
    val limit: Int?,
    val skillsCap: Int,
    val proUntil: String?,
    val judgeUntil: String?,
    val wallet: String?,
    /** The Solana the worker takes payments on. */
    val cluster: Cluster = Cluster.MAINNET,
    /** Which provider speaks, and the two voices' names; null from an older worker. */
    val voice: VoiceInfo? = null
)

/** What /me says about the voice. */
data class VoiceInfo(
    val provider: String,
    val skylar: String?,
    val archie: String?,
    /** The cloud ears the worker can lend a pass for; an older worker says nothing, which is Deepgram's alone. */
    val ears: List<String> = listOf("deepgram")
)

/** What the worker says to send for Pro. [amount] is in the token's base units. */
data class Quote(
    val currency: String,
    val mint: String,
    val amount: Long,
    val decimals: Int,
    val tokenProgram: String,
    val treasury: String,
    val reference: String,
    val expiresAt: String,
    /** The dollar price this amount was worked out from, e.g. "15". */
    val priceUsd: String? = null
)

/** A signed-in wallet, as the worker hands it back. */
data class Verified(val session: String, val welcomeGranted: Boolean, val standing: Standing)

/** One line for the log: "confirmed", "409 not_confirmed", "unreachable IOException". */
fun describe(answer: Answer<*>): String = when (answer) {
    is Answer.Ok -> "ok"
    is Answer.Refused -> "${answer.code} ${answer.reason}"
    is Answer.Unreachable -> "unreachable ${answer.cause}"
}

/** How a call to the worker went. */
sealed interface Answer<out T> {
    data class Ok<T>(val value: T) : Answer<T>

    /** The worker answered, and the answer was no. [reason] is its one-word code. */
    data class Refused(val code: Int, val reason: String, val detail: String = "") : Answer<Nothing>

    /** The worker could not be reached. */
    data class Unreachable(val cause: String) : Answer<Nothing>
}

/**
 * The wallet, plan and payment routes on Heylana's proxy. Every call carries the
 * device id and, once a wallet is connected, its session.
 */
class WalletApi(private val settings: HeylanaSettings) {

    private val proxy = Proxy(settings)

    suspend fun challenge(pubkey: String): Answer<Pair<String, String>> =
        post("wallet/challenge", JSONObject().put("pubkey", pubkey)) {
            it.getString("nonce") to it.getString("message")
        }

    suspend fun verify(pubkey: String, nonce: String, signature: String): Answer<Verified> =
        post(
            "wallet/verify",
            JSONObject().put("pubkey", pubkey).put("nonce", nonce).put("signature", signature)
        ) {
            Verified(
                session = it.getString("session"),
                welcomeGranted = it.optBoolean("welcome_granted"),
                standing = standingOf(it.getJSONObject("me"))
            )
        }

    suspend fun me(): Answer<Standing> = call(proxy.get("me")) { standingOf(it) }

    /** What the connected wallet is called, and what Heylana calls its owner. */
    suspend fun profile(): Answer<Profile> = call(proxy.get("profile")) { profileOf(it) }

    // ------------------------------------------------------------------ memory

    /** Whether memory is on for this wallet, and what it keeps. */
    suspend fun memory(): Answer<xyz.heylana.app.memory.MemoryState> = call(proxy.get("memory")) { memoryOf(it) }

    /**
     * Keeps one record. [said] is the user's own words for an explicit one: the worker keeps
     * it only if the line is in them, and never one with an address or an amount in it.
     */
    suspend fun remember(
        category: String,
        content: String,
        consent: String,
        said: String?,
        source: String
    ): Answer<String?> =
        post(
            "memory",
            JSONObject().put("category", category).put("content", content).put("consent", consent)
                .put("source_turn", source).apply { if (said != null) put("said", said) }
        ) { it.optJSONObject("record")?.optString("id")?.takeIf { id -> id.isNotEmpty() } }

    suspend fun forget(id: String): Answer<xyz.heylana.app.memory.MemoryState> =
        post("memory/delete", JSONObject().put("id", id)) { memoryOf(it) }

    suspend fun wipeMemory(): Answer<xyz.heylana.app.memory.MemoryState> =
        post("memory/wipe", JSONObject()) { memoryOf(it) }

    /** Turns memory on or off for this wallet; off keeps nothing. */
    suspend fun memoryConsent(on: Boolean): Answer<xyz.heylana.app.memory.MemoryState> =
        post("memory/consent", JSONObject().put("on", on)) { memoryOf(it) }

    private fun memoryOf(json: JSONObject): xyz.heylana.app.memory.MemoryState {
        val list = json.optJSONArray("records")
        val records = List(list?.length() ?: 0) { i ->
            val r = list!!.getJSONObject(i)
            xyz.heylana.app.memory.MemoryRecord(
                id = r.optString("id"),
                category = r.optString("category"),
                content = r.optString("content"),
                consent = r.optString("consent"),
                created = r.optString("created")
            )
        }
        return xyz.heylana.app.memory.MemoryState(on = json.optBoolean("on"), records = records)
    }

    suspend fun saveProfile(callMe: String): Answer<Profile> =
        call(proxy.put("profile", JSONObject().put("call_me", cleanName(callMe)).toString())) { profileOf(it) }

    suspend fun judge(code: String): Answer<Standing> =
        post("judge", JSONObject().put("code", code)) { standingOf(it) }

    suspend fun quote(currency: String): Answer<Quote> =
        post("pay/quote", JSONObject().put("currency", currency)) {
            Quote(
                currency = it.getString("currency"),
                mint = it.getString("mint"),
                amount = it.getString("amount").toLong(),
                decimals = it.getInt("decimals"),
                tokenProgram = it.getString("token_program"),
                treasury = it.getString("treasury"),
                reference = it.getString("reference"),
                expiresAt = it.getString("expires_at"),
                priceUsd = it.optString("price_usd").ifEmpty { null }
            )
        }

    /**
     * The worker builds and simulates the transfer for a prepared send ([id]) or a Pro
     * quote ([reference]) on [cluster]. A preview comes back with no bytes; [final] asks
     * for the unsigned transaction too, which only comes back if the simulation passed.
     * Refused 409 wrong_cluster when the worker is on another cluster.
     */
    suspend fun build(
        id: String?,
        reference: String?,
        cluster: Cluster,
        final: Boolean,
        confirmation: String? = null
    ): Answer<BuiltTransfer> =
        post(
            "send/build",
            JSONObject().put("cluster", cluster.id).put("final", final).apply {
                if (id != null) put("id", id)
                if (reference != null) put("reference", reference)
                if (confirmation != null) put("confirmation", confirmation)
            }
        ) { builtOf(it) }

    /**
     * The user confirmed an R3 action — Confirm on the strip for a send ([kind] "send",
     * [subject] its id), Pay for Pro ("pay", the quote's reference), or the app's guard
     * firing a message or a reminder the model proposed (its action id, with [guardPassed]).
     * The worker answers with a short-lived confirmation token, or refuses (403) if it
     * never prepared this for this user.
     */
    suspend fun confirmation(kind: String, subject: String, guardPassed: Boolean = false): Answer<String> =
        post(
            "confirm",
            JSONObject().put("kind", kind).put("subject", subject).apply { if (guardPassed) put("guard", "allowed") }
        ) { it.getString("confirmation") }

    /** The final build of an R3 transfer: the user's confirmation first, then the bytes. */
    suspend fun confirmedBuild(kind: String, subject: String, cluster: Cluster): Answer<BuiltTransfer> =
        when (val token = confirmation(kind, subject)) {
            is Answer.Ok -> build(
                id = subject.takeIf { kind == "send" },
                reference = subject.takeIf { kind == "pay" },
                cluster = cluster,
                final = true,
                confirmation = token.value
            )
            // The worker's reason is a code word; the user is told in plain words.
            is Answer.Refused -> Answer.Refused(token.code, token.reason, NOT_CONFIRMED)
            is Answer.Unreachable -> token
        }

    /** Answers Refused(409, "not_confirmed") until the chain has confirmed it. */
    /** Without [signature], the worker looks the payment up by its reference. */
    suspend fun confirm(reference: String, signature: String?): Answer<Standing> =
        post(
            "pay/confirm",
            JSONObject().put("reference", reference).apply { if (!signature.isNullOrBlank()) put("signature", signature) }
        ) { standingOf(it) }

    private suspend fun <T> post(path: String, body: JSONObject, read: (JSONObject) -> T): Answer<T> =
        call(proxy.post(path, body.toString()), read)

    private suspend fun <T> call(request: Request, read: (JSONObject) -> T): Answer<T> =
        withContext(Dispatchers.IO) {
            if (!proxy.isConfigured) return@withContext Answer.Unreachable("not_set_up")
            try {
                Proxy.http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
                    if (!response.isSuccessful) {
                        Answer.Refused(response.code, json.optString("reason").ifEmpty { "error" }, json.optString("detail"))
                    } else {
                        Answer.Ok(read(json))
                    }
                }
            } catch (e: IOException) {
                Answer.Unreachable(e::class.simpleName ?: "IOException")
            } catch (e: Exception) {
                Answer.Unreachable("bad_reply")
            }
        }

    /**
     * Has the worker check a send; [amount] is a plain decimal, or "all". [said] is the user's
     * own words, so the worker can check the recipient in them a second time. Nothing is
     * built or signed.
     */
    suspend fun prepareSend(to: String, amount: String, token: String, said: String): Answer<SendQuote> =
        post("send/prepare", JSONObject().put("to", to).put("amount", amount).put("token", token).put("said", said)) {
            SendQuote(
                id = it.getString("id"),
                toAddress = it.getString("to_address"),
                resolvedFrom = it.optString("resolved_from").takeIf { name -> name.isNotEmpty() && name != "null" },
                amount = it.getString("amount"),
                token = it.getString("token"),
                mint = it.optString("mint").takeIf { mint -> mint.isNotEmpty() && mint != "null" },
                decimals = it.getInt("decimals"),
                tokenProgram = it.optString("token_program").takeIf { p -> p.isNotEmpty() && p != "null" },
                feeEstimate = it.optString("fee_estimate"),
                accountRent = it.optString("account_rent", "0"),
                willCreateAta = it.optBoolean("will_create_ata"),
                balance = it.optString("balance").takeIf { b -> b.isNotEmpty() && b != "null" },
                cluster = Cluster.fromWorker(it.optString("cluster").ifEmpty { null })
            )
        }

    /** Answers Refused(409, "not_confirmed") until the send has landed; then its short signature. */
    suspend fun confirmSend(id: String, signature: String?): Answer<String> =
        post("send/confirm", JSONObject().put("id", id).apply { if (signature != null) put("signature", signature) }) {
            it.optString("signature")
        }

    companion object {
        /** When the worker would not confirm an R3 action, the wallet is not opened. */
        const val NOT_CONFIRMED = "I couldn't confirm that with Heylana's server, so I didn't open the wallet."
    }

    private fun builtOf(json: JSONObject): BuiltTransfer {
        val p = json.getJSONObject("preview")
        val sim = json.getJSONObject("simulation")
        val programs = p.optJSONArray("programs")
        return BuiltTransfer(
            kind = json.optString("kind"),
            preview = TransferPreview(
                from = p.optString("from"),
                fromLabel = p.optString("from_label"),
                to = p.optString("to"),
                toLabel = p.optString("to_label"),
                amount = p.optString("amount"),
                token = p.optString("token"),
                feeSol = p.optString("fee_sol"),
                accountRentSol = p.optString("account_rent_sol", "0"),
                createsAccount = p.optBoolean("creates_account"),
                programs = List(programs?.length() ?: 0) { programs!!.getString(it) },
                cluster = p.optString("cluster")
            ),
            simulation = if (sim.optBoolean("ok")) SimulationResult.Passed
            else SimulationResult.Failed(sim.optString("reason"), sim.optString("words")),
            transaction = json.optString("transaction").takeIf { it.isNotEmpty() }
                ?.let { java.util.Base64.getDecoder().decode(it) },
            lastValidBlockHeight = json.optLong("last_valid_block_height")
        )
    }

    private fun profileOf(json: JSONObject) = Profile(
        name = json.optString("name"),
        callMe = json.optString("call_me")
    )

    private fun standingOf(json: JSONObject) = Standing(
        plan = json.optString("plan", "free"),
        used = json.optInt("used", 0),
        limit = if (json.isNull("limit")) null else json.optInt("limit"),
        skillsCap = json.optInt("skills_cap", 3),
        proUntil = json.optString("pro_until").takeIf { it.isNotEmpty() && it != "null" },
        judgeUntil = json.optString("judge_until").takeIf { it.isNotEmpty() && it != "null" },
        wallet = json.optString("wallet").takeIf { it.isNotEmpty() && it != "null" },
        cluster = Cluster.fromWorker(json.optString("cluster").ifEmpty { null }),
        voice = json.optJSONObject("voice")?.let { voice ->
            voice.optString("provider").takeIf { it.isNotEmpty() }?.let { provider ->
                VoiceInfo(
                    provider = provider,
                    skylar = voice.optString("skylar").takeIf { it.isNotEmpty() },
                    archie = voice.optString("archie").takeIf { it.isNotEmpty() },
                    ears = voice.optJSONArray("ears")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }
                        ?: listOf("deepgram")
                )
            }
        }
    )
}
