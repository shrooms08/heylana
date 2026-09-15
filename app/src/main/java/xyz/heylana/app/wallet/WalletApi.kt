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
    val wallet: String?
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

/** How a call to the worker went. */
sealed interface Answer<out T> {
    data class Ok<T>(val value: T) : Answer<T>

    /** The worker answered, and the answer was no. [reason] is its one-word code. */
    data class Refused(val code: Int, val reason: String) : Answer<Nothing>

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

    suspend fun blockhash(): Answer<String> =
        post("pay/blockhash", JSONObject()) { it.getString("blockhash") }

    /** Answers Refused(409, "not_confirmed") until the chain has confirmed it. */
    suspend fun confirm(reference: String, signature: String): Answer<Standing> =
        post("pay/confirm", JSONObject().put("reference", reference).put("signature", signature)) {
            standingOf(it)
        }

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
                        Answer.Refused(response.code, json.optString("reason").ifEmpty { "error" })
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

    private fun standingOf(json: JSONObject) = Standing(
        plan = json.optString("plan", "free"),
        used = json.optInt("used", 0),
        limit = if (json.isNull("limit")) null else json.optInt("limit"),
        skillsCap = json.optInt("skills_cap", 3),
        proUntil = json.optString("pro_until").takeIf { it.isNotEmpty() && it != "null" },
        judgeUntil = json.optString("judge_until").takeIf { it.isNotEmpty() && it != "null" },
        wallet = json.optString("wallet").takeIf { it.isNotEmpty() && it != "null" }
    )
}
